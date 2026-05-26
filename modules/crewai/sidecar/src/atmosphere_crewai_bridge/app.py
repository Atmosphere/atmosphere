# Copyright 2008-2026 Async-IO.org
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""FastAPI application exposing the CrewAI sidecar wire protocol.

The three endpoints documented in ``modules/crewai/README.md``:

* ``GET /health`` — 200 + JSON ``{"status":"ok","version":"<pkg>"}``
* ``POST /v1/sessions`` — 200 + ``text/event-stream`` with framed events
* ``DELETE /v1/sessions/{id}`` — 204 (idempotent)

Boundary safety (Invariant #4): request bodies are validated by a
Pydantic ``BaseModel``; malformed input becomes a 422 from FastAPI
rather than reaching the streaming code. Backpressure (Invariant #3):
``SessionRegistry`` enforces a hard cap; full → 503.
"""
from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from typing import Any, Literal

from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field, field_validator

from . import __version__
from .crew_loader import load_crew_factory
from .sessions import Session, SessionRegistry
from .stream import drain, run_crew
from .tools import apply_system_prompt, build_remote_tools, inject_tools_into_crew

logger = logging.getLogger(__name__)

# How many characters of free-form text we'll accept per request.
# Generous, but bounded so a misbehaving client can't push gigabytes.
_MAX_MESSAGE_CHARS = 64 * 1024
_MAX_HISTORY_ENTRIES = 256
_MAX_HISTORY_CHARS_PER_ENTRY = 16 * 1024


class _HistoryEntry(BaseModel):
    role: Literal["user", "assistant", "system"] = "user"
    content: str = ""

    @field_validator("content")
    @classmethod
    def _bound_content(cls, value: str) -> str:
        if len(value) > _MAX_HISTORY_CHARS_PER_ENTRY:
            raise ValueError(
                f"history entry content exceeds {_MAX_HISTORY_CHARS_PER_ENTRY} chars"
            )
        return value


class _ParameterDescriptor(BaseModel):
    name: str
    type: str = "string"
    description: str = ""
    required: bool = False


class _ToolDescriptor(BaseModel):
    name: str
    description: str = ""
    parameters: list[_ParameterDescriptor] = Field(default_factory=list)
    return_type: str = "string"


class _StartRequest(BaseModel):
    message: str = Field(default="", description="User prompt")
    model: str | None = Field(default=None, description="Optional model id")
    history: list[_HistoryEntry] = Field(default_factory=list)
    options: dict[str, Any] = Field(default_factory=dict)
    system_prompt: str | None = Field(
        default=None,
        description="Optional system-prompt directive prepended to each agent's "
                    "backstory by the bridge before kickoff.",
    )
    tools: list[_ToolDescriptor] = Field(
        default_factory=list,
        description="Atmosphere @AiTool descriptors. When non-empty, "
                    "tool_callback_url must also be set.",
    )
    tool_callback_url: str | None = Field(
        default=None,
        description="Loopback URL the bridge POSTs to when a tool fires.",
    )

    @field_validator("message")
    @classmethod
    def _bound_message(cls, value: str) -> str:
        if len(value) > _MAX_MESSAGE_CHARS:
            raise ValueError(
                f"message exceeds {_MAX_MESSAGE_CHARS} chars"
            )
        return value

    @field_validator("history")
    @classmethod
    def _bound_history(cls, value: list[_HistoryEntry]) -> list[_HistoryEntry]:
        if len(value) > _MAX_HISTORY_ENTRIES:
            raise ValueError(
                f"history exceeds {_MAX_HISTORY_ENTRIES} entries"
            )
        return value


def create_app(crew_factory: Callable[[str, list[dict[str, str]]], Any] | None = None,
               *,
               registry: SessionRegistry | None = None) -> FastAPI:
    """Build a fresh FastAPI app.

    The crew factory + registry are dependency-injected so tests can
    swap them out without touching the module-level singleton. The
    console script entry point uses :func:`create_app` once at startup
    and passes the result to uvicorn.
    """
    app = FastAPI(
        title="Atmosphere CrewAI Bridge",
        version=__version__,
        description=(
            "Sidecar service speaking the Atmosphere CrewAI runtime "
            "wire protocol (HTTP + SSE). See modules/crewai/README.md "
            "for the protocol contract."
        ),
    )

    app.state.crew_factory = crew_factory
    app.state.registry = registry or SessionRegistry()

    @app.get("/health")
    async def health() -> JSONResponse:
        # Invariant #5 (Runtime Truth): we report ``ok`` only when the
        # process is actually serving requests. There's no separate
        # readiness gate today — being alive is being ready. If we ever
        # add async startup (e.g. warming up the crew), this becomes a
        # confirmed-state check.
        return JSONResponse(
            {"status": "ok", "version": __version__},
            status_code=200,
        )

    @app.post("/v1/sessions")
    async def start_session(body: _StartRequest,
                            request: Request) -> Response:
        factory: Callable[[str, list[dict[str, str]]], Any] | None = (
            request.app.state.crew_factory
        )
        if factory is None:
            # No crew configured = misconfiguration, not a wire-protocol
            # error. Return a 503 with a JSON body so the Java side
            # surfaces a readable error message (Invariant #5).
            raise HTTPException(
                status_code=503,
                detail="sidecar started without a crew factory",
            )

        # Tool-bridge precondition (Boundary Safety, Invariant #4):
        # advertising tools without a callback URL is a misconfiguration
        # — the bridge can't materialise tools it cannot route back. Fail
        # before acquiring registry capacity so a misconfigured client
        # cannot exhaust the sidecar.
        if body.tools and not body.tool_callback_url:
            return JSONResponse(
                {"error": "tool_callback_url required when tools are present"},
                status_code=400,
            )

        registry: SessionRegistry = request.app.state.registry
        session = registry.acquire()
        if session is None:
            # Capacity exhausted — backpressure signal. Surface 503 so
            # the Java client doesn't infinitely retry.
            return JSONResponse(
                {"error": "session registry at capacity",
                 "capacity": registry.capacity},
                status_code=503,
            )

        # Build the crew. If the user's factory throws, fail fast with
        # a 500 — the session id was never observable so the registry
        # entry is safe to drop here.
        try:
            crew = factory(
                body.message,
                [entry.model_dump() for entry in body.history],
            )
        except Exception as exc:  # noqa: BLE001 — boundary
            registry.release(session.id)
            logger.exception(
                "crew factory raised for session %s: %s", session.id, exc,
            )
            raise HTTPException(
                status_code=500,
                detail=f"crew factory failed: {type(exc).__name__}: {exc}",
            ) from exc

        # Wire Java-side tools and system prompt onto the crew before
        # kickoff. Both operations are no-ops when the corresponding
        # field is absent — Invariant #4 (validate at the boundary) and
        # Invariant #2 (cleanup-on-exception runs in the producer task's
        # finally below).
        if body.tools:
            try:
                remote_tools = build_remote_tools(
                    [tool.model_dump() for tool in body.tools],
                    body.tool_callback_url or "",
                    session.id,
                )
                inject_tools_into_crew(crew, remote_tools)
            except Exception as exc:  # noqa: BLE001 — boundary
                registry.release(session.id)
                logger.exception(
                    "tool injection failed for session %s: %s", session.id, exc,
                )
                raise HTTPException(
                    status_code=500,
                    detail=f"tool injection failed: {type(exc).__name__}: {exc}",
                ) from exc
        if body.system_prompt:
            try:
                apply_system_prompt(crew, body.system_prompt)
            except Exception as exc:  # noqa: BLE001 — boundary; do not block on
                # a system-prompt warm-up failure, just log. The crew
                # would still run with its original backstory.
                logger.warning(
                    "system_prompt application failed for session %s: %s",
                    session.id, exc,
                )

        producer_task = asyncio.create_task(
            run_crew(
                session=session,
                crew=crew,
                message=body.message,
                history=[entry.model_dump() for entry in body.history],
                model=body.model,
            ),
            name=f"crewai-bridge:run:{session.id}",
        )

        # Hand the producer a hook to remove itself from the registry
        # once it terminates. We do NOT await the task here — its life
        # is bound to the response generator below.
        producer_task.add_done_callback(
            _make_release_callback(registry, session.id)
        )

        async def body_iter():
            try:
                async for frame in drain(session):
                    yield frame
            finally:
                # If the client disconnected mid-stream, cancel the
                # producer so it doesn't keep burning LLM tokens for a
                # gone consumer (Invariant #2).
                if not producer_task.done():
                    session.request_cancel()
                    # Give the producer a beat to emit its terminal
                    # frame; if it doesn't, drop it.
                    try:
                        await asyncio.wait_for(producer_task, timeout=2.0)
                    except (asyncio.TimeoutError, asyncio.CancelledError):
                        producer_task.cancel()

        headers = {
            "Cache-Control": "no-cache",
            # The Java client falls back to this header if it doesn't
            # see a ``session`` SSE frame first — mirror what the Java
            # test fixture does so the contract is the same on both sides.
            "X-Atmosphere-CrewAI-Session": session.id,
        }
        return StreamingResponse(
            body_iter(),
            media_type="text/event-stream",
            headers=headers,
        )

    @app.delete("/v1/sessions/{session_id}")
    async def cancel_session(session_id: str) -> Response:
        # Idempotent (Invariant #2): unknown / already-cancelled both
        # return 204 — the Java side may DELETE multiple times.
        registry: SessionRegistry = app.state.registry
        registry.cancel(session_id)
        return Response(status_code=204)

    return app


def _make_release_callback(registry: SessionRegistry,
                           session_id: str) -> Callable[[asyncio.Task[Any]], None]:
    def _cb(task: asyncio.Task[Any]) -> None:
        registry.release(session_id)
        # Pull task exception (if any) so the event loop's
        # ``Task exception was never retrieved`` warning is silenced —
        # the producer already logged it via ``run_crew``.
        if not task.cancelled():
            exc = task.exception()
            if exc is not None:
                logger.debug(
                    "session %s producer task ended with %s",
                    session_id, type(exc).__name__,
                )

    return _cb


# Module-level app: used when uvicorn is pointed at
# ``atmosphere_crewai_bridge.app:app`` (e.g. for ``--reload`` workflows).
# Defaults to no crew factory; the CLI overrides via ``app.state`` after
# loading the user's spec. Tests prefer ``create_app(...)`` directly.
app = create_app()


def configure(crew_spec: str) -> None:
    """Load a crew spec and attach it to the module-level :data:`app`.

    Intended for use from the CLI entry point — production code should
    prefer :func:`create_app` so each app has explicit state.
    """
    factory = load_crew_factory(crew_spec)
    app.state.crew_factory = factory
