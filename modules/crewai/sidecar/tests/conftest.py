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
"""Shared test fixtures.

The fixtures here intentionally do NOT import ``crewai``. We exercise
the bridge against a ``FakeCrew`` that yields deterministic chunks so
tests run in milliseconds and never make an LLM call. The real CrewAI
integration is exercised at runtime against a user-supplied crew —
that boundary is covered by the Java side's ``CrewAiAgentRuntimeBridgeTest``.
"""
from __future__ import annotations

import asyncio
from typing import Any

import pytest
from fastapi.testclient import TestClient

from atmosphere_crewai_bridge.app import create_app


class _Chunk:
    """Mimics ``crewai.types.streaming.StreamChunk`` — only the
    ``.content`` attribute is read by the bridge.
    """

    def __init__(self, content: str) -> None:
        self.content = content


class _Usage:
    """Mimics ``crewai.types.usage_metrics.UsageMetrics`` (subset)."""

    def __init__(self, prompt: int, completion: int) -> None:
        self.prompt_tokens = prompt
        self.completion_tokens = completion
        self.total_tokens = prompt + completion


class _CrewOutput:
    """Mimics ``crewai.crews.crew_output.CrewOutput`` (subset)."""

    def __init__(self, raw: str, usage: _Usage | None) -> None:
        self.raw = raw
        self.token_usage = usage

    def __str__(self) -> str:
        return self.raw


class _AsyncChunkStream:
    """Streaming output object: async-iterable, with ``.result`` exposed
    once iteration completes. Matches the shape of
    ``CrewStreamingOutput`` closely enough that the bridge's
    streaming-drain path picks it up.
    """

    def __init__(self, chunks: list[str], usage: _Usage | None) -> None:
        self._chunks = list(chunks)
        self._usage = usage
        self._idx = 0
        self._exhausted = False
        self._aclose_called = False

    def __aiter__(self) -> "_AsyncChunkStream":
        return self

    async def __anext__(self) -> _Chunk:
        if self._aclose_called:
            raise StopAsyncIteration
        if self._idx >= len(self._chunks):
            self._exhausted = True
            raise StopAsyncIteration
        chunk = _Chunk(self._chunks[self._idx])
        self._idx += 1
        # Yield control so cancellation can interleave.
        await asyncio.sleep(0)
        return chunk

    async def aclose(self) -> None:
        self._aclose_called = True

    @property
    def result(self) -> _CrewOutput:
        if not self._exhausted and not self._aclose_called:
            raise RuntimeError("Streaming has not completed yet.")
        return _CrewOutput(raw="".join(self._chunks), usage=self._usage)


class FakeCrew:
    """Stand-in for ``crewai.Crew``.

    Exposes the surface the bridge actually consumes:

    * ``stream`` attribute (settable)
    * ``kickoff_async(inputs=...)`` returning an async streaming object
    * ``kickoff(inputs=...)`` returning a plain ``_CrewOutput`` (used by
      tests that exercise the non-streaming fallback)
    """

    def __init__(self,
                 chunks: list[str] | None = None,
                 usage: tuple[int, int] = (7, 2),
                 raise_on_kickoff: Exception | None = None,
                 hold_seconds: float = 0.0) -> None:
        self.chunks = chunks if chunks is not None else ["Hello", " ", "world"]
        self.usage = _Usage(*usage)
        self.stream = False
        self._raise = raise_on_kickoff
        self._hold = hold_seconds
        self.last_inputs: dict[str, Any] | None = None

    async def kickoff_async(self,
                            inputs: dict[str, Any] | None = None) -> Any:
        self.last_inputs = inputs
        if self._raise is not None:
            raise self._raise
        if not self.stream:
            return _CrewOutput(raw="".join(self.chunks), usage=self.usage)
        if self._hold > 0:
            await asyncio.sleep(self._hold)
        return _AsyncChunkStream(self.chunks, self.usage)

    def kickoff(self, inputs: dict[str, Any] | None = None,
                input_files: Any = None) -> Any:  # noqa: ARG002
        self.last_inputs = inputs
        if self._raise is not None:
            raise self._raise
        return _CrewOutput(raw="".join(self.chunks), usage=self.usage)


# ---- Fixtures -------------------------------------------------------------

@pytest.fixture
def fake_crew() -> FakeCrew:
    """Default FakeCrew with three text chunks and a small usage block."""
    return FakeCrew()


@pytest.fixture
def app_with_crew(fake_crew: FakeCrew):
    """FastAPI app pre-wired with the fake crew factory."""

    def factory(_message: str, _history: list[dict[str, str]]) -> FakeCrew:
        return fake_crew

    return create_app(crew_factory=factory)


@pytest.fixture
def client(app_with_crew) -> TestClient:
    """Synchronous FastAPI TestClient (httpx-based)."""
    with TestClient(app_with_crew) as tc:
        yield tc


# ---- Public crew factory used by the launch sanity check ------------------

def fake_crew_factory(_message: str = "",
                      _history: list[dict[str, str]] | None = None) -> FakeCrew:
    """Module-level factory referenced by the ``--crew`` launch test.

    Returns a fresh ``FakeCrew`` per call so the launched process doesn't
    share state across sessions.
    """
    return FakeCrew()
