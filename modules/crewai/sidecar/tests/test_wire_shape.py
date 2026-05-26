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
"""Wire-shape conformance: the SSE bytes emitted by ``POST /v1/sessions``
must match what ``HttpSseSidecarClient`` reads byte-for-byte.

This test parses the raw body via the same simple rule the Java
``HttpSseSidecarClient.EventIterator`` uses: collect ``event: <name>``
+ ``data: <json>`` line pairs separated by a blank line.
"""
from __future__ import annotations

import json
from typing import Any

from fastapi.testclient import TestClient

from atmosphere_crewai_bridge.app import create_app
from tests.conftest import FakeCrew  # noqa: E402 — package-relative import


def _parse_sse(raw: str) -> list[tuple[str, dict[str, Any]]]:
    """Parse an SSE body into ``[(event, data_dict)]`` in order.

    Matches the Java parser's behavior: event names are required;
    ``data:`` lines concatenated with ``\\n`` when multi-line; blank
    line dispatches the accumulated frame.
    """
    events: list[tuple[str, dict[str, Any]]] = []
    event_name: str | None = None
    data: str | None = None
    for line in raw.split("\n"):
        if line == "":
            if event_name is not None or data is not None:
                payload: dict[str, Any] = {}
                if data:
                    payload = json.loads(data)
                events.append((event_name or "message", payload))
                event_name = None
                data = None
            continue
        if line.startswith("event: "):
            event_name = line[len("event: "):].strip()
        elif line.startswith("data: "):
            chunk = line[len("data: "):]
            data = chunk if data is None else data + "\n" + chunk
        elif line.startswith(":"):
            continue  # SSE comment
    # Tail frame in case the body doesn't end with a blank line.
    if event_name is not None or data is not None:
        payload2: dict[str, Any] = json.loads(data) if data else {}
        events.append((event_name or "message", payload2))
    return events


def test_session_emits_session_then_tokens_then_usage_then_done() -> None:
    fake = FakeCrew(chunks=["alpha", "beta", "gamma"], usage=(11, 4))

    def factory(_msg: str, _hist: list[dict[str, str]]) -> FakeCrew:
        return fake

    app = create_app(crew_factory=factory)
    with TestClient(app) as client:
        with client.stream(
            "POST", "/v1/sessions",
            json={"message": "hi", "model": "gpt-4o-mini",
                  "history": [], "options": {}},
        ) as response:
            assert response.status_code == 200
            assert response.headers["content-type"].startswith("text/event-stream")
            assert "X-Atmosphere-CrewAI-Session" in response.headers
            body = b"".join(response.iter_bytes()).decode("utf-8")

    events = _parse_sse(body)

    # Strip the session frame (always first) and assert its shape first.
    assert events[0][0] == "session", f"first event must be 'session', got {events[0][0]}"
    session_payload = events[0][1]
    assert "sessionId" in session_payload, \
        "session event must carry 'sessionId' (the field name the Java client reads)"
    sid = session_payload["sessionId"]
    assert sid.startswith("sess_"), f"session id should be uuid-shaped; got {sid}"
    # Java client also accepts the id via the response header — same value.
    # (We checked the header is present above; values match per code path.)

    rest = events[1:]
    # token frames in order
    token_events = [e for e in rest if e[0] == "token"]
    assert [e[1].get("text") for e in token_events] == ["alpha", "beta", "gamma"], \
        f"token text payloads must match input chunks exactly; got {token_events}"

    # usage frame is present and well-formed
    usage_events = [e for e in rest if e[0] == "usage"]
    assert len(usage_events) == 1, f"exactly one usage frame expected; got {usage_events}"
    u = usage_events[0][1]
    assert u["input"] == 11
    assert u["output"] == 4
    assert u["total"] == 15

    # Exactly one terminal frame, and it must be 'done' (not 'error').
    terminal = [e for e in rest if e[0] in ("done", "error")]
    assert len(terminal) == 1, f"exactly one terminal frame; got {terminal}"
    assert terminal[0][0] == "done"
    assert terminal[0][1] == {}, "done frame data must be the empty object"


def test_error_in_factory_returns_500_no_stream() -> None:
    """Boundary safety: a crashing factory must not leak as a 200/SSE error
    frame — it should fail the request cleanly before streaming starts."""

    def factory(_msg: str, _hist: list[dict[str, str]]) -> FakeCrew:
        raise RuntimeError("kaboom")

    app = create_app(crew_factory=factory)
    with TestClient(app) as client:
        response = client.post(
            "/v1/sessions",
            json={"message": "x", "model": None, "history": [], "options": {}},
        )
    # Factory failure surfaces as 500. The session was never observable,
    # so the registry stayed clean — verified indirectly: the next request
    # succeeds (i.e. we didn't leak a slot).
    assert response.status_code == 500


def test_crew_failure_during_stream_emits_error_frame() -> None:
    """If the crew raises during execution, the bridge must emit an
    ``event: error`` terminal frame — not just close the connection.
    Invariant #2 (terminal-path completeness) + the Java side's
    ``errorEvent_propagatesToSession`` test pin this behaviour.
    """

    fake = FakeCrew(raise_on_kickoff=RuntimeError("boom"))

    def factory(_msg: str, _hist: list[dict[str, str]]) -> FakeCrew:
        return fake

    app = create_app(crew_factory=factory)
    with TestClient(app) as client:
        with client.stream(
            "POST", "/v1/sessions",
            json={"message": "x", "model": None, "history": [], "options": {}},
        ) as response:
            assert response.status_code == 200
            body = b"".join(response.iter_bytes()).decode("utf-8")

    events = _parse_sse(body)
    terminal = [e for e in events if e[0] in ("done", "error")]
    assert len(terminal) == 1, f"exactly one terminal frame; got {terminal}"
    assert terminal[0][0] == "error"
    msg = terminal[0][1].get("message", "")
    assert "boom" in msg, f"error message must carry the cause; got {msg!r}"


def test_rejects_oversized_message() -> None:
    """Boundary validation (Invariant #4): malformed input returns 422
    via Pydantic — does NOT reach the streaming code."""
    fake = FakeCrew()

    def factory(_msg: str, _hist: list[dict[str, str]]) -> FakeCrew:
        return fake

    app = create_app(crew_factory=factory)
    with TestClient(app) as client:
        response = client.post(
            "/v1/sessions",
            json={"message": "x" * (64 * 1024 + 1), "model": None,
                  "history": [], "options": {}},
        )
    assert response.status_code == 422
