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
"""Tool bridge unit tests.

These tests verify the materialisation step (descriptor → BaseTool) and
the callback transport in isolation. End-to-end coverage (full POST
/v1/sessions with tools wired) lives in ``test_tools_in_sessions.py``.
"""
from __future__ import annotations

from typing import Any

import httpx
import pytest

from atmosphere_crewai_bridge.tools import (
    apply_system_prompt,
    build_remote_tool,
    inject_tools_into_crew,
)


class _FakeAgent:
    """Minimal stand-in for ``crewai.Agent``: just the ``tools`` and
    ``backstory`` attributes the bridge mutates."""

    def __init__(self, tools: list[Any] | None = None, backstory: str = "") -> None:
        self.tools: list[Any] | None = tools
        self.backstory = backstory


class _FakeCrew:
    """Minimal stand-in for ``crewai.Crew`` carrying just the ``agents``
    list used by the bridge."""

    def __init__(self, agents: list[_FakeAgent]) -> None:
        self.agents = agents


def test_build_remote_tool_pydantic_schema() -> None:
    """A descriptor with mixed JSON Schema types must produce an
    args_schema whose field types match the spec mapping. This pins the
    spec: string→str, integer→int, number→float, boolean→bool."""
    descriptor = {
        "name": "lookup",
        "description": "Look up something.",
        "parameters": [
            {"name": "q", "type": "string", "description": "query", "required": True},
            {"name": "limit", "type": "integer", "description": "max", "required": False},
            {"name": "score", "type": "number", "description": "thr", "required": False},
            {"name": "fuzzy", "type": "boolean", "description": "f", "required": False},
        ],
        "return_type": "string",
    }
    tool = build_remote_tool(descriptor, "http://127.0.0.1:9999/cb", "sess_x",
                             callback_token="t0k")

    schema = tool.args_schema
    fields = schema.model_fields
    assert set(fields.keys()) == {"q", "limit", "score", "fuzzy"}, \
        f"every descriptor parameter must surface as a schema field; got {fields.keys()}"
    # Annotations must match the spec mapping verbatim — pydantic stores
    # them on the FieldInfo.annotation attribute.
    assert fields["q"].annotation is str, fields["q"]
    assert fields["limit"].annotation is int, fields["limit"]
    assert fields["score"].annotation is float, fields["score"]
    assert fields["fuzzy"].annotation is bool, fields["fuzzy"]
    # Required field must have ellipsis as default (= required); optional
    # parameters have None as default.
    assert fields["q"].is_required(), "required=True must produce a required field"
    assert not fields["limit"].is_required(), "required=False must be optional"


def test_build_remote_tool_unknown_type_falls_back_to_any(caplog) -> None:
    """Unknown schema types must NOT crash — they must log at INFO and
    degrade to typing.Any so the crew run still proceeds."""
    descriptor = {
        "name": "exotic",
        "description": "Tool with an unknown param type.",
        "parameters": [
            {"name": "weird", "type": "uuid", "description": "", "required": False},
        ],
    }
    import logging
    with caplog.at_level(logging.INFO):
        tool = build_remote_tool(descriptor, "http://127.0.0.1:9999/cb", "sess_x",
                             callback_token="t0k")
    assert any("unknown JSON schema type" in r.message for r in caplog.records), \
        "unknown schema types must log at INFO so observers see the surprise"
    # The schema field exists; its type is permissive.
    assert "weird" in tool.args_schema.model_fields


def test_remote_tool_posts_to_callback() -> None:
    """The generated tool must POST to the supplied callback URL with the
    documented ``{call_id, name, arguments}`` body shape."""
    captured: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["json"] = request.read().decode("utf-8")
        return httpx.Response(200, json={"result": "ok"})

    transport = httpx.MockTransport(handler)
    client = httpx.Client(transport=transport)
    try:
        descriptor = {
            "name": "lookup_order",
            "description": "Look up an order",
            "parameters": [
                {"name": "order_id", "type": "string", "description": "",
                 "required": True},
            ],
        }
        tool = build_remote_tool(
            descriptor, "http://127.0.0.1:8765/v1/tools/call", "sess_x",
            callback_token="t0k", client=client,
        )
        result = tool._run(order_id="A123")

        assert result == "ok"
        assert captured["url"] == "http://127.0.0.1:8765/v1/tools/call"

        import json
        body = json.loads(captured["json"])
        assert body["name"] == "lookup_order"
        assert body["arguments"] == {"order_id": "A123"}
        assert "call_id" in body and body["call_id"], \
            "call_id must be present so the Java side can correlate"
        assert body["session_id"] == "sess_x"
    finally:
        client.close()


def test_remote_tool_propagates_error() -> None:
    """If the callback returns ``{error: ...}`` (HTTP 200) the tool must
    raise — CrewAI catches the raise as a tool failure and surfaces it
    to the agent for retry."""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"error": "boom: not found"})

    client = httpx.Client(transport=httpx.MockTransport(handler))
    try:
        tool = build_remote_tool(
            {"name": "x", "description": "y",
             "parameters": [{"name": "p", "type": "string", "required": True}]},
            "http://127.0.0.1:1/cb", "sess_x", callback_token="t0k",
            client=client,
        )
        with pytest.raises(RuntimeError) as exc:
            tool._run(p="value")
        assert "boom: not found" in str(exc.value), \
            f"error message must carry the Java-supplied reason; got {exc.value}"
    finally:
        client.close()


def test_remote_tool_propagates_http_error() -> None:
    """Wire-level failures (non-200 status, malformed JSON) must surface
    as a runtime error, never silently return None."""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="internal server error")

    client = httpx.Client(transport=httpx.MockTransport(handler))
    try:
        tool = build_remote_tool(
            {"name": "x", "description": "y", "parameters": []},
            "http://127.0.0.1:1/cb", "sess_x", callback_token="t0k",
            client=client,
        )
        with pytest.raises(RuntimeError) as exc:
            tool._run()
        assert "HTTP 500" in str(exc.value)
    finally:
        client.close()


def test_build_remote_tool_rejects_empty_callback_url() -> None:
    """A descriptor without a callback URL must fail loudly at build
    time — silently no-op'ing would mean the tool runs but lands nowhere."""
    with pytest.raises(ValueError, match="callback_url"):
        build_remote_tool(
            {"name": "x", "description": "y", "parameters": []},
            "", "sess_x", callback_token="t0k",
        )


def test_build_remote_tool_rejects_empty_callback_token() -> None:
    """Without the token every callback 401s inside CrewAI's retry loop and
    surfaces as an opaque agent failure. Fail at build time where the reason
    is still legible."""
    with pytest.raises(ValueError, match="callback_token"):
        build_remote_tool(
            {"name": "x", "description": "y", "parameters": []},
            "http://127.0.0.1:1/cb", "sess_x", callback_token="",
        )


def test_remote_tool_sends_auth_token_header() -> None:
    """The Java callback server refuses any call without a matching
    ``X-Atmosphere-Tool-Token``. This pins the header onto the wire: if the
    header silently stopped being sent, every tool call would 401 at runtime
    and only an integration test would notice."""
    captured: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["token"] = request.headers.get("X-Atmosphere-Tool-Token")
        return httpx.Response(200, json={"result": "ok"})

    client = httpx.Client(transport=httpx.MockTransport(handler))
    try:
        tool = build_remote_tool(
            {"name": "x", "description": "y", "parameters": []},
            "http://127.0.0.1:1/cb", "sess_x",
            callback_token="s3cret-token", client=client,
        )
        assert tool._run() == "ok"
        assert captured["token"] == "s3cret-token", (
            "the per-run token must ride every callback in the "
            "X-Atmosphere-Tool-Token header"
        )
    finally:
        client.close()


def test_inject_tools_into_crew_appends_to_each_agent() -> None:
    """Every agent in ``crew.agents`` must receive the tool list. Pre-
    existing tools survive (extend, not overwrite)."""
    existing_a = object()
    existing_b = object()
    new_tool = object()

    crew = _FakeCrew(agents=[
        _FakeAgent(tools=[existing_a]),
        _FakeAgent(tools=[existing_b]),
    ])

    inject_tools_into_crew(crew, [new_tool])

    assert crew.agents[0].tools == [existing_a, new_tool], \
        "must extend (not replace) pre-existing tools"
    assert crew.agents[1].tools == [existing_b, new_tool]


def test_inject_tools_handles_none_tools_field() -> None:
    """Agents whose ``tools`` attribute is None must get a fresh list,
    not a TypeError."""
    new_tool = object()
    crew = _FakeCrew(agents=[_FakeAgent(tools=None)])

    inject_tools_into_crew(crew, [new_tool])

    assert crew.agents[0].tools == [new_tool]


def test_inject_tools_noop_when_no_tools() -> None:
    """The empty-tools fast path must be a no-op so the pre-tool-bridge
    behaviour is preserved exactly."""
    agent = _FakeAgent(tools=[object()])
    original_tools = list(agent.tools)
    crew = _FakeCrew(agents=[agent])

    inject_tools_into_crew(crew, [])

    assert agent.tools == original_tools, "no-tools path must not mutate"


def test_apply_system_prompt_prepends_block_to_backstory() -> None:
    """The Atmosphere system prompt must be added as a delimited block
    in front of each agent's backstory so the framework prefix is
    distinguishable from user content."""
    crew = _FakeCrew(agents=[
        _FakeAgent(backstory="You are a careful researcher."),
    ])
    apply_system_prompt(crew, "Follow the project maintainer's policy strictly.")

    backstory = crew.agents[0].backstory
    assert "atmosphere:system_prompt" in backstory
    assert "Follow the project maintainer's policy strictly." in backstory
    assert "You are a careful researcher." in backstory
    # Atmosphere block comes first (system prompt overrides backstory).
    assert backstory.index("Follow") < backstory.index("careful")


def test_apply_system_prompt_idempotent() -> None:
    """Calling apply_system_prompt twice with different prompts must
    REPLACE the prior block, not stack — otherwise re-using a crew would
    drift over time."""
    crew = _FakeCrew(agents=[_FakeAgent(backstory="base")])
    apply_system_prompt(crew, "first prompt")
    apply_system_prompt(crew, "second prompt")

    backstory = crew.agents[0].backstory
    assert "second prompt" in backstory
    assert "first prompt" not in backstory, \
        "re-applying must replace the prior block, not accumulate"
    assert backstory.count("atmosphere:system_prompt") == 2, \
        "open + close marker == 2 occurrences exactly"


def test_apply_system_prompt_noop_on_empty() -> None:
    crew = _FakeCrew(agents=[_FakeAgent(backstory="unchanged")])
    apply_system_prompt(crew, None)
    apply_system_prompt(crew, "   ")
    assert crew.agents[0].backstory == "unchanged"
