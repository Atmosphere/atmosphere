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
"""End-to-end session tests for the tool-RPC bridge.

These cover the integration seam: a real ``POST /v1/sessions`` with a
``tools`` payload triggers the materialisation path inside the app, the
generated tools land on the FakeCrew's agents, and (when invoked) they
POST to the callback URL.
"""
from __future__ import annotations

from typing import Any

import httpx
from fastapi.testclient import TestClient

from atmosphere_crewai_bridge.app import create_app
from tests.conftest import FakeCrew


class _AgentWithTools:
    """Stand-in for a CrewAI Agent that tracks the tools the bridge attaches.

    Mimics enough of the Agent surface (mutable ``tools`` list, mutable
    ``backstory``) that the bridge's ``inject_tools_into_crew`` and
    ``apply_system_prompt`` mutate it like the real thing.
    """

    def __init__(self, role: str = "test") -> None:
        self.role = role
        self.tools: list[Any] = []
        self.backstory = "default backstory"


class _CrewWithAgents(FakeCrew):
    """FakeCrew with a real-looking ``agents`` list so the tool injection
    code path lights up. Inherits the streaming/kickoff surface of FakeCrew
    so all the existing tests continue to work."""

    def __init__(self, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.agents = [_AgentWithTools(role="alpha"),
                       _AgentWithTools(role="beta")]


def _tool_descriptor(name: str = "lookup_order") -> dict[str, Any]:
    return {
        "name": name,
        "description": "Look up an order",
        "parameters": [
            {"name": "order_id", "type": "string",
             "description": "the id", "required": True},
        ],
        "return_type": "string",
    }


def test_session_with_tools_injects_them_on_each_agent() -> None:
    """When ``tools`` is non-empty AND ``tool_callback_url`` is set, the
    bridge must materialise BaseTool subclasses and attach them to every
    agent in the crew. We assert by inspecting the crew after the POST."""
    crew = _CrewWithAgents()

    def factory(_message: str, _history: list[dict[str, str]]) -> _CrewWithAgents:
        return crew

    app = create_app(crew_factory=factory)
    with TestClient(app) as client:
        with client.stream(
            "POST", "/v1/sessions",
            json={
                "message": "go",
                "model": None,
                "history": [],
                "options": {},
                "tools": [_tool_descriptor()],
                "tool_callback_url": "http://127.0.0.1:1/v1/tools/call",
                "tool_callback_token": "t0k",
            },
        ) as response:
            assert response.status_code == 200, \
                f"expected 200; got {response.status_code} body={response.text}"
            # Drain the stream so the producer finishes and the registry
            # releases — keeps the test independent.
            b"".join(response.iter_bytes())

    # Every agent must have a tool with the descriptor's name.
    for agent in crew.agents:
        names = [getattr(t, "name", "") for t in agent.tools]
        assert "lookup_order" in names, \
            f"agent {agent.role!r} must have lookup_order; got {names}"


def test_session_with_tools_calls_callback_when_tool_fires() -> None:
    """The callback URL the Java side advertises must actually be POSTed
    to when an injected tool is invoked.

    We don't stand up a real CrewAI run (no LLM); instead we exercise the
    generated tool directly to verify the end-to-end RPC plumbing works
    with an httpx mock transport injected at build time.
    """
    crew = _CrewWithAgents()

    def factory(_message: str, _history: list[dict[str, str]]) -> _CrewWithAgents:
        return crew

    app = create_app(crew_factory=factory)
    with TestClient(app) as client:
        with client.stream(
            "POST", "/v1/sessions",
            json={
                "message": "go", "model": None, "history": [], "options": {},
                "tools": [_tool_descriptor()],
                "tool_callback_url": "http://127.0.0.1:1/v1/tools/call",
                "tool_callback_token": "t0k",
            },
        ) as response:
            assert response.status_code == 200
            b"".join(response.iter_bytes())

    # Grab the materialised tool from the first agent. Drive its _run
    # against a mock transport that records the POST.
    tool = next(t for t in crew.agents[0].tools
                if getattr(t, "name", "") == "lookup_order")

    captured: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["body"] = request.read().decode("utf-8")
        return httpx.Response(200, json={"result": "ORDER:A123"})

    # Replace the tool's _run implementation's outbound client. The
    # generated tool falls back to a per-call client when none is
    # injected, so we patch the module-level httpx.Client used inside
    # _run via monkeypatching httpx itself.
    import httpx as _httpx_module
    original_client = _httpx_module.Client

    def patched_client(*args: Any, **kwargs: Any) -> _httpx_module.Client:
        kwargs.setdefault("transport", _httpx_module.MockTransport(handler))
        return original_client(*args, **kwargs)

    _httpx_module.Client = patched_client  # type: ignore[assignment]
    try:
        result = tool._run(order_id="A123")
    finally:
        _httpx_module.Client = original_client  # type: ignore[assignment]

    assert result == "ORDER:A123"
    assert captured["url"] == "http://127.0.0.1:1/v1/tools/call", \
        "tool must POST to the callback URL advertised at start time"
    assert '"name":"lookup_order"' in captured["body"]
    assert '"order_id":"A123"' in captured["body"]


def test_session_without_tools_works_unchanged() -> None:
    """Pre-tool-bridge sessions (no tools, no callback URL) must keep
    working exactly as before — the new code paths are no-ops on this
    fast path so existing factories don't regress."""
    crew = FakeCrew()

    def factory(_message: str, _history: list[dict[str, str]]) -> FakeCrew:
        return crew

    app = create_app(crew_factory=factory)
    with TestClient(app) as client:
        with client.stream(
            "POST", "/v1/sessions",
            json={"message": "go", "model": None, "history": [], "options": {}},
        ) as response:
            assert response.status_code == 200
            body = b"".join(response.iter_bytes()).decode("utf-8")
    # Stream must still carry token + done frames — no regression on the
    # baseline path.
    assert "event: token" in body, "token frames must still emit"
    assert "event: done" in body, "done frame must still close the stream"


def test_tools_without_callback_url_returns_400() -> None:
    """Boundary safety (Invariant #4): a body with ``tools`` but no
    ``tool_callback_url`` is a misconfiguration. We MUST reject it at
    the boundary rather than streaming a session the bridge cannot route
    tool calls back from."""
    crew = _CrewWithAgents()

    def factory(_message: str, _history: list[dict[str, str]]) -> _CrewWithAgents:
        return crew

    app = create_app(crew_factory=factory)
    with TestClient(app) as client:
        response = client.post(
            "/v1/sessions",
            json={
                "message": "go", "model": None, "history": [], "options": {},
                "tools": [_tool_descriptor()],
                # tool_callback_url intentionally omitted
            },
        )
    assert response.status_code == 400, \
        f"missing callback URL must surface as 400; got {response.status_code}"
    body = response.json()
    assert "tool_callback_url" in body.get("error", ""), \
        f"error payload must name the missing field; got {body}"


def test_callback_url_without_token_returns_400() -> None:
    """Boundary safety (Invariant #4): a callback URL with no token is a
    misconfiguration — the Java callback server would 401 every tool call,
    which CrewAI surfaces as an opaque agent failure several layers down.
    Reject it here where the error still names the actual problem."""
    crew = _CrewWithAgents()

    def factory(_message: str, _history: list[dict[str, str]]) -> _CrewWithAgents:
        return crew

    app = create_app(crew_factory=factory)
    with TestClient(app) as client:
        response = client.post(
            "/v1/sessions",
            json={
                "message": "go", "model": None, "history": [], "options": {},
                "tools": [_tool_descriptor()],
                "tool_callback_url": "http://127.0.0.1:1/v1/tools/call",
                # tool_callback_token intentionally omitted
            },
        )
    assert response.status_code == 400, \
        f"missing callback token must surface as 400; got {response.status_code}"
    body = response.json()
    assert "tool_callback_token" in body.get("error", ""), \
        f"error payload must name the missing field; got {body}"


def test_session_with_system_prompt_threads_into_backstory() -> None:
    """Submitting a ``system_prompt`` must mutate each agent's backstory
    so the LLM sees the Atmosphere directive in its system message."""
    crew = _CrewWithAgents()

    def factory(_message: str, _history: list[dict[str, str]]) -> _CrewWithAgents:
        return crew

    app = create_app(crew_factory=factory)
    with TestClient(app) as client:
        with client.stream(
            "POST", "/v1/sessions",
            json={
                "message": "go", "model": None, "history": [], "options": {},
                "system_prompt": "Always cite your sources.",
            },
        ) as response:
            assert response.status_code == 200
            b"".join(response.iter_bytes())

    for agent in crew.agents:
        assert "Always cite your sources." in agent.backstory, \
            f"system_prompt must reach agent {agent.role!r}; got: {agent.backstory!r}"
        assert "atmosphere:system_prompt" in agent.backstory, \
            "the injected block must be delimited so observers can identify it"
