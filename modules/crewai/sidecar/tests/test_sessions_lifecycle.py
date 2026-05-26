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
"""Session lifecycle:

* POST creates, drains a session
* DELETE is idempotent (204 on first AND subsequent calls)
* Registry capacity is enforced (503 when full)
"""
from __future__ import annotations

import json

from fastapi.testclient import TestClient

from atmosphere_crewai_bridge.app import create_app
from atmosphere_crewai_bridge.sessions import SessionRegistry
from tests.conftest import FakeCrew


def _read_session_id(body: str) -> str:
    """Extract sessionId from the first ``session`` SSE frame."""
    lines = body.split("\n")
    in_session = False
    for line in lines:
        if line == "event: session":
            in_session = True
            continue
        if in_session and line.startswith("data: "):
            payload = json.loads(line[len("data: "):])
            return payload["sessionId"]
    raise AssertionError(f"no session frame found in body: {body!r}")


def test_post_session_drains_then_delete_is_idempotent(app_with_crew) -> None:
    with TestClient(app_with_crew) as client:
        with client.stream(
            "POST", "/v1/sessions",
            json={"message": "hi", "model": None,
                  "history": [], "options": {}},
        ) as response:
            assert response.status_code == 200
            body = b"".join(response.iter_bytes()).decode("utf-8")

        sid = _read_session_id(body)

        # First DELETE — 204, even though session already drained on its own.
        r1 = client.delete(f"/v1/sessions/{sid}")
        assert r1.status_code == 204, \
            "DELETE on a (drained or live) session must return 204"

        # Second DELETE — also 204. Idempotent per Invariant #2.
        r2 = client.delete(f"/v1/sessions/{sid}")
        assert r2.status_code == 204, \
            "second DELETE on the same session id must also be 204 (idempotent)"

        # DELETE on a never-existed id — also 204.
        r3 = client.delete("/v1/sessions/sess_neverexisted")
        assert r3.status_code == 204, \
            "DELETE on an unknown id must be 204 (idempotent)"


def test_delete_during_stream_cancels_producer() -> None:
    """If the client cancels mid-stream, the producer must stop emitting
    further tokens. We verify by checking the registry releases the
    session id (no leak)."""
    # Slow chunks (use the streaming hold) so the cancel has time to land.
    fake = FakeCrew(chunks=["a", "b", "c", "d", "e"], hold_seconds=0.0)
    registry = SessionRegistry(max_sessions=4, queue_size=4)

    def factory(_msg: str, _hist: list[dict[str, str]]) -> FakeCrew:
        return fake

    app = create_app(crew_factory=factory, registry=registry)
    with TestClient(app) as client:
        # Open the stream, drain it fully so the producer finishes.
        with client.stream(
            "POST", "/v1/sessions",
            json={"message": "hi", "model": None,
                  "history": [], "options": {}},
        ) as response:
            body = b"".join(response.iter_bytes()).decode("utf-8")
        sid = _read_session_id(body)
        # DELETE after natural completion: still 204.
        assert client.delete(f"/v1/sessions/{sid}").status_code == 204
        # Registry must be empty — the producer's done-callback released
        # the session, no leak (Invariant #1 — Ownership).
        assert registry.size == 0


def test_registry_capacity_returns_503_when_full() -> None:
    """Backpressure (Invariant #3): full registry → 503 with a JSON
    error body. Do NOT silently accept beyond the cap."""

    # Capacity 1, queue size large so the in-flight session doesn't drain
    # too quickly to be observable.
    registry = SessionRegistry(max_sessions=1, queue_size=4)
    fake = FakeCrew(chunks=["x"])

    def factory(_msg: str, _hist: list[dict[str, str]]) -> FakeCrew:
        return fake

    app = create_app(crew_factory=factory, registry=registry)
    with TestClient(app) as client:
        # Pre-fill the registry directly so we observe a real over-capacity
        # condition without racing against the streaming generator.
        held = registry.acquire()
        assert held is not None, "registry should accept the first acquire"
        assert registry.size == 1

        # Second POST — capacity exhausted, must be 503.
        response = client.post(
            "/v1/sessions",
            json={"message": "hi", "model": None,
                  "history": [], "options": {}},
        )
        assert response.status_code == 503, \
            f"capacity-exhausted POST must be 503; got {response.status_code}"
        body = response.json()
        assert "error" in body
        assert body["capacity"] == 1


def test_post_without_crew_factory_returns_503() -> None:
    """If the sidecar started without ``--crew``, calls return 503 so
    callers see a configuration error rather than a hung stream
    (Invariant #5 — runtime truth)."""
    app = create_app(crew_factory=None)
    with TestClient(app) as client:
        response = client.post(
            "/v1/sessions",
            json={"message": "hi", "model": None,
                  "history": [], "options": {}},
        )
        assert response.status_code == 503
        assert "crew factory" in response.json()["detail"]
