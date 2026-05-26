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
"""SSE frame encoder and CrewAI → frame bridge.

Wire shape (matching ``HttpSseSidecarClient`` byte-for-byte):

* Each frame is ``event: <name>\\ndata: <json>\\n\\n``.
* ``<json>`` is a single line — newlines inside the JSON would be
  interpreted by SSE as a multi-line data payload. We use ``json.dumps``
  with ``separators=(',',':')`` so a stray newline inside user content is
  never emitted.

Event types we produce (in order):

1. ``session`` once, with ``{"sessionId": "..."}`` — matches the field
   name the Java client reads (``HttpSseSidecarClient.updateSessionId``
   reads ``node.path("sessionId")``).
2. ``token`` zero or more times, with ``{"text": "..."}``.
3. ``usage`` zero or one, at the end, with
   ``{"input": int, "output": int, "total": int, "model": "..."}``.
4. Exactly one terminal frame: ``done`` (``{}``) on success, or
   ``error`` (``{"message": "..."}``) on failure.

Cancellation: when ``session.cancelled`` is set, the bridge stops
producing chunks, emits a final ``error`` frame with ``"cancelled"`` as
the reason (per Invariant #2 — every terminal path completes), and
returns. The Java side translates that into ``session.error()`` which
is consistent with how the cancel test exercises the runtime.
"""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

from .sessions import QUEUE_CLOSED, Session

logger = logging.getLogger(__name__)

# CrewAI imports are deferred so the module is importable when only the
# FastAPI surface is being exercised in unit tests. The real bridge fn
# imports them inside the function body.


def encode(event: str, data: dict[str, Any]) -> bytes:
    """Render one SSE frame to bytes.

    ``json.dumps`` with compact separators keeps the payload on a single
    line — a multi-line ``data:`` block would break the protocol contract
    we exchange with the Java client.
    """
    payload = json.dumps(data, separators=(",", ":"), ensure_ascii=False)
    return f"event: {event}\ndata: {payload}\n\n".encode("utf-8")


def session_frame(session_id: str) -> bytes:
    return encode("session", {"sessionId": session_id})


def token_frame(text: str) -> bytes:
    return encode("token", {"text": text})


def usage_frame(input_tokens: int, output_tokens: int,
                total_tokens: int | None = None,
                model: str | None = None) -> bytes:
    total = total_tokens if total_tokens is not None else input_tokens + output_tokens
    payload: dict[str, Any] = {
        "input": int(input_tokens),
        "output": int(output_tokens),
        "total": int(total),
    }
    if model:
        payload["model"] = model
    return encode("usage", payload)


def done_frame() -> bytes:
    return encode("done", {})


def error_frame(message: str) -> bytes:
    return encode("error", {"message": message})


async def drain(session: Session):
    """Async generator yielding raw SSE bytes from the session queue.

    Terminates when the queue-closed sentinel arrives. Idempotent in the
    sense that a second consumption attempt simply yields nothing — the
    queue has already drained.
    """
    while True:
        item = await session.queue.get()
        if item is QUEUE_CLOSED:
            return
        # Anything non-bytes on the queue is a bug — surface it loudly
        # rather than serving garbage to the wire.
        if not isinstance(item, (bytes, bytearray)):
            logger.error(
                "session %s received non-bytes item %r on event queue; "
                "dropping and ending stream", session.id, type(item).__name__,
            )
            yield error_frame("internal: malformed event on queue")
            return
        yield bytes(item)


def _safe_put(session: Session, frame: bytes) -> bool:
    """Put a frame on the session queue, honoring backpressure.

    Returns ``False`` if the cancel flag is set or the queue refused the
    item; ``True`` on success. The caller MUST stop producing further
    frames on ``False``.
    """
    if session.cancelled.is_set():
        return False
    try:
        session.queue.put_nowait(frame)
        return True
    except asyncio.QueueFull:
        # Backpressure: rather than silently waiting forever, we treat a
        # full queue (which means the SSE consumer is gone or stalled)
        # as cancellation. Invariant #3 — never ignore rejection signals.
        logger.warning(
            "session %s queue full; dropping frame and cancelling stream",
            session.id,
        )
        session.request_cancel()
        return False


def _extract_usage(crew_output: Any) -> tuple[int, int, int]:
    """Pull ``(prompt_tokens, completion_tokens, total_tokens)`` from a
    CrewAI ``CrewOutput.token_usage`` if present.

    Returns ``(0, 0, 0)`` if the shape is unknown — usage events are
    optional in the wire protocol, so a missing usage object simply
    means we skip the frame.
    """
    usage = getattr(crew_output, "token_usage", None)
    if usage is None:
        return (0, 0, 0)
    # ``UsageMetrics`` exposes ``prompt_tokens``, ``completion_tokens``,
    # ``total_tokens`` as ints in CrewAI 1.x. Tolerate older/newer shapes
    # by falling back to 0 on each field.
    prompt = int(getattr(usage, "prompt_tokens", 0) or 0)
    completion = int(getattr(usage, "completion_tokens", 0) or 0)
    total = int(getattr(usage, "total_tokens", 0) or (prompt + completion))
    return (prompt, completion, total)


async def run_crew(session: Session,
                   crew: Any,
                   message: str,
                   history: list[dict[str, str]],
                   model: str | None) -> None:
    """Drive a CrewAI crew, emitting wire frames into ``session.queue``.

    Two paths:

    * If the crew exposes streaming (``crew.stream = True`` + async
      iteration over ``CrewStreamingOutput``), token frames are emitted
      as chunks arrive. This is the CrewAI 1.x happy path.
    * Otherwise the bridge falls back to a single ``kickoff`` call and
      emits one token frame carrying the full result text. From the
      transport's perspective that's still streaming — the Java side
      cares about event framing, not chunk count.

    Terminal frames are emitted by ``finally`` blocks so cancel,
    success, and unexpected-exception paths all reach a defined state
    (Invariant #2).
    """
    # Always announce the session id first so the Java side has it
    # before the first token, even if the underlying HTTP transport
    # buffers the response headers.
    if not _safe_put(session, session_frame(session.id)):
        return

    # Inputs CrewAI hands to ``Crew.kickoff(inputs=...)``: by convention
    # the project's tasks reference ``{message}`` / ``{history}`` from
    # the inputs dict. The factory contract documents this so users
    # write their crew definitions to consume those keys.
    inputs: dict[str, Any] = {"message": message}
    if history:
        # Render history as a plain string the task template can splice
        # in directly. CrewAI's templating prefers strings over lists.
        rendered = "\n".join(
            f"{entry.get('role', 'user')}: {entry.get('content', '')}"
            for entry in history
        )
        inputs["history"] = rendered
    if model:
        inputs["model"] = model

    terminal_emitted = False

    try:
        # Try the async streaming path first: it's the lowest-latency
        # way to feed tokens to the Java side. CrewAI 1.14 returns a
        # ``CrewStreamingOutput`` when ``crew.stream`` is True; we set
        # that flag here so the user's factory doesn't have to.
        streaming = await _kickoff_streaming(crew, inputs)
        if streaming is not None:
            crew_output = await _drain_stream(session, streaming)
        else:
            # Non-streaming fallback. Run kickoff on a worker thread so we
            # don't block the event loop.
            crew_output = await asyncio.to_thread(crew.kickoff, inputs)
            text = _output_text(crew_output)
            if text:
                if not _safe_put(session, token_frame(text)):
                    return

        # Usage frame (best-effort).
        if crew_output is not None:
            prompt, completion, total = _extract_usage(crew_output)
            if prompt or completion or total:
                _safe_put(
                    session,
                    usage_frame(prompt, completion, total, model=model),
                )

        if session.cancelled.is_set():
            _safe_put(session, error_frame("cancelled"))
        else:
            _safe_put(session, done_frame())
        terminal_emitted = True
    except asyncio.CancelledError:
        # Co-operative cancel from the FastAPI side.
        if not terminal_emitted:
            _safe_put(session, error_frame("cancelled"))
            terminal_emitted = True
        raise
    except Exception as exc:  # noqa: BLE001 — boundary; log and emit error
        # Boundary: convert any producer-side exception to a single
        # ``error`` frame so the wire protocol's terminal contract is
        # always honored. Invariant #2 + Invariant #4 (don't propagate
        # internal failure shapes onto the wire).
        logger.exception("session %s crew run failed: %s",
                         session.id, exc)
        if not terminal_emitted:
            _safe_put(session, error_frame(f"{type(exc).__name__}: {exc}"))
            terminal_emitted = True
    finally:
        if not terminal_emitted:
            # Defensive: a missing terminal frame would leave the Java
            # client waiting until its request timeout. Emit one.
            _safe_put(session, error_frame("stream ended without terminal frame"))
        session.terminated = True
        session.close_queue()


async def _kickoff_streaming(crew: Any, inputs: dict[str, Any]):
    """Return a ``CrewStreamingOutput`` if the crew supports streaming,
    else ``None``.

    CrewAI 1.x: setting ``crew.stream = True`` and calling
    ``kickoff_async`` returns a ``CrewStreamingOutput`` with an async
    iterator over ``StreamChunk``. Older versions don't expose this
    surface — we detect that and return ``None`` so the caller falls
    back to plain ``kickoff``.
    """
    # ``stream`` is a pydantic-validated field; some older Crew variants
    # don't have it. ``getattr`` with default keeps us forward/backward
    # compatible without raising.
    has_stream_flag = hasattr(crew, "stream")
    kickoff_async = getattr(crew, "kickoff_async", None)
    if not has_stream_flag or kickoff_async is None:
        return None
    try:
        crew.stream = True
    except (AttributeError, TypeError) as exc:
        logger.debug("crew does not accept stream=True: %s", exc)
        return None
    try:
        result = await kickoff_async(inputs=inputs)
    except TypeError:
        # Older kickoff_async without ``inputs=`` kwarg.
        try:
            result = await kickoff_async(inputs)
        except Exception as exc:  # noqa: BLE001 — fall through to non-stream
            logger.debug("kickoff_async positional fallback failed: %s", exc)
            return None
    # If streaming is off (e.g. user's factory overrides the flag),
    # ``kickoff_async`` returns a plain ``CrewOutput`` which is NOT
    # iterable. Detect that and emit it as a single-token result.
    if not hasattr(result, "__aiter__") and not hasattr(result, "__iter__"):
        return _SingletonStream(result)
    return result


class _SingletonStream:
    """Wraps a non-streaming CrewOutput so the streaming-drain code path
    can still consume it uniformly. Yields a single chunk with the full
    output text, then exposes ``.result`` like ``CrewStreamingOutput``.
    """

    def __init__(self, crew_output: Any) -> None:
        self._crew_output = crew_output
        self._yielded = False

    def __aiter__(self):
        return self

    async def __anext__(self):
        if self._yielded:
            raise StopAsyncIteration
        self._yielded = True

        class _Chunk:
            content = _output_text(self._crew_output)  # type: ignore[misc]

        return _Chunk()

    @property
    def result(self) -> Any:
        return self._crew_output


async def _drain_stream(session: Session, streaming: Any) -> Any:
    """Iterate the CrewAI streaming output and forward each chunk as a
    ``token`` frame. Returns the final ``CrewOutput`` (or None) so the
    caller can extract usage metadata.
    """
    async for chunk in streaming:
        if session.cancelled.is_set():
            # Close the underlying iterator if it offers ``aclose`` so
            # CrewAI tears down its in-flight LLM call promptly.
            aclose = getattr(streaming, "aclose", None)
            if aclose is not None:
                try:
                    await aclose()
                except Exception as exc:  # noqa: BLE001 — best-effort
                    logger.debug(
                        "session %s aclose during cancel raised: %s",
                        session.id, exc,
                    )
            break
        text = getattr(chunk, "content", None)
        if not text:
            continue
        if not _safe_put(session, token_frame(text)):
            return None
    # Access ``.result`` if available so the caller can emit usage.
    result_prop = getattr(streaming, "result", None)
    if result_prop is None:
        return None
    try:
        return result_prop
    except RuntimeError:
        # ``CrewStreamingOutput.result`` raises when streaming wasn't
        # fully iterated (e.g. we broke out for a cancel). That's fine
        # — no usage frame.
        return None


def _output_text(crew_output: Any) -> str:
    """Pull a string from a CrewAI ``CrewOutput`` (or string/None).

    The output of ``crew.kickoff()`` is typed ``CrewOutput`` in 1.x and
    exposes ``.raw`` plus a ``__str__`` fallback. Handle both.
    """
    if crew_output is None:
        return ""
    raw = getattr(crew_output, "raw", None)
    if isinstance(raw, str) and raw:
        return raw
    if isinstance(crew_output, str):
        return crew_output
    try:
        return str(crew_output)
    except (TypeError, ValueError) as exc:
        logger.debug("could not stringify crew output: %s", exc)
        return ""
