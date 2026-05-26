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
"""Materialise Java-side ``@AiTool`` definitions as native CrewAI BaseTool
subclasses on the Python side, and wire them onto the crew's agents.

The Java runtime advertises a list of ``ToolDescriptor`` records on the
``POST /v1/sessions`` body:

    {
      "name": "lookup_order",
      "description": "Look up an order by id",
      "parameters": [
        {"name": "order_id", "type": "string",
         "description": "The order id", "required": true}
      ],
      "return_type": "string"
    }

Each descriptor becomes a runtime-generated ``crewai.tools.BaseTool``
subclass with:

* ``args_schema`` — a dynamic ``pydantic.BaseModel`` whose field types are
  derived from the descriptor's parameter types (string→str, integer→int,
  number→float, boolean→bool, array/object/<unknown>→typing.Any).
* ``_run`` — POSTs the call to ``tool_callback_url`` on the Java side and
  returns the ``result`` from the response. Raises on ``error`` so CrewAI
  routes the failure back into the agent's tool-error path.

The crew's agents pick up the tools via direct mutation of their ``tools``
list (the field is a ``list[BaseTool]`` and freely mutable on the
``crewai.Agent`` model as of CrewAI 1.x — verified by inspecting
``Agent.model_fields['tools']``).
"""
from __future__ import annotations

import logging
import threading
import uuid
from typing import Any

import httpx
from pydantic import BaseModel, ConfigDict, create_model

logger = logging.getLogger(__name__)

# Default timeout for the callback round-trip. Generous because the Java
# side may park the request for human-in-the-loop approval — but bounded so
# a stuck approval doesn't permanently wedge the agent.
_DEFAULT_TIMEOUT_SECONDS = 120.0

# JSON Schema → Python type mapping. Anything not in this table falls back
# to ``typing.Any`` with an INFO log (per spec: never crash on unexpected
# shapes, surface the surprise).
_TYPE_MAP: dict[str, type] = {
    "string": str,
    "integer": int,
    "number": float,
    "boolean": bool,
    # Arrays and objects intentionally fall through to Any; deeper schema
    # construction would require recursing on item/property schemas, which
    # the wire shape doesn't carry today.
}


def _coerce_type(param_type: str | None, tool_name: str, param_name: str) -> Any:
    """Map a JSON Schema type string to a Python type for pydantic.

    Unknown types log at INFO and degrade to ``Any`` — the spec explicitly
    forbids crashing the bridge on unexpected schema shapes, because that
    would deny the entire crew run for a single misclassified parameter.
    """
    if not param_type:
        return Any
    py = _TYPE_MAP.get(param_type.lower())
    if py is not None:
        return py
    if param_type.lower() in ("array", "object"):
        return Any
    logger.info(
        "tool %s parameter %s has unknown JSON schema type %r; treating as Any",
        tool_name, param_name, param_type,
    )
    return Any


def _build_args_schema(descriptor: dict[str, Any]) -> type[BaseModel]:
    """Construct an ``args_schema: type[BaseModel]`` from a tool descriptor.

    Uses ``pydantic.create_model`` so the schema is built fresh per tool —
    avoids global state. Field names match the descriptor's parameter
    names verbatim; CrewAI's tool-call validator will use the resulting
    model to coerce LLM-supplied arguments.
    """
    tool_name = descriptor.get("name") or "unnamed"
    params = descriptor.get("parameters") or []

    fields: dict[str, Any] = {}
    for param in params:
        name = param.get("name")
        if not name:
            # Boundary safety: skip malformed entries rather than crash —
            # the Java side validates names, but we don't want a single
            # bad descriptor to break the whole tool list.
            logger.info(
                "tool %s has a parameter with no name; skipping it", tool_name,
            )
            continue
        py_type = _coerce_type(param.get("type"), tool_name, name)
        required = bool(param.get("required", False))
        description = param.get("description") or ""

        if required:
            # Ellipsis = required field with no default
            fields[name] = (py_type, ...)
        else:
            fields[name] = (py_type, None)

        # Attach description through a Field — pydantic.create_model takes
        # a (type, FieldInfo) tuple. We use the simpler (type, default)
        # form above for clarity; description is informational, CrewAI
        # exposes the descriptor's description on the tool itself.
        _ = description  # noqa: F841 — documented intent, see above

    if not fields:
        # An empty pydantic model is still valid — CrewAI will see a
        # zero-arg tool. Keep the name deterministic so logs and traces
        # are stable.
        return create_model(f"{tool_name}_ArgsSchema", __base__=BaseModel)

    # ``__config__`` allows arbitrary types so a parameter that fell back
    # to ``typing.Any`` doesn't trip pydantic's strict validation.
    model = create_model(
        f"{tool_name}_ArgsSchema",
        __config__=ConfigDict(arbitrary_types_allowed=True),
        **fields,
    )
    return model


class _RemoteToolCallError(RuntimeError):
    """Raised by ``_run`` when the Java side returns an ``{error: ...}``
    payload. CrewAI catches this as a tool failure and surfaces it to the
    agent for recovery / retry — same shape as a native Python tool that
    raised."""


def build_remote_tool(descriptor: dict[str, Any],
                      callback_url: str,
                      session_id: str,
                      *,
                      client: httpx.Client | None = None,
                      timeout: float = _DEFAULT_TIMEOUT_SECONDS) -> Any:
    """Build a single CrewAI ``BaseTool`` subclass from a descriptor.

    The returned tool is a pydantic-validated CrewAI tool; ``_run`` POSTs
    a ``{call_id, name, arguments}`` body to ``callback_url`` and returns
    the ``result`` field of the response.

    Args:
        descriptor:    Tool descriptor as deserialised from the Java
                       ``POST /v1/sessions`` body.
        callback_url:  Absolute URL the Java side advertised for tool
                       callbacks (always a loopback address).
        session_id:    Opaque session id; threaded into the call body so
                       observers on the Java side can correlate.
        client:        Optional shared ``httpx.Client``. Mostly used for
                       testing so a mock transport can be injected.
        timeout:       Per-call timeout in seconds.

    Returns:
        A subclass of ``crewai.tools.BaseTool`` ready to be added to an
        agent's ``tools`` list.

    Raises:
        ValueError:    Descriptor missing a name or callback_url is empty.
    """
    # Imported lazily so test modules that don't exercise the tool path
    # don't have to pay the CrewAI import cost.
    from crewai.tools import BaseTool  # noqa: PLC0415 — intentional lazy import

    name = descriptor.get("name")
    if not name:
        raise ValueError("descriptor missing required 'name'")
    if not callback_url:
        raise ValueError(
            f"build_remote_tool requires a non-empty callback_url for tool {name!r}",
        )
    description = descriptor.get("description") or f"Tool '{name}'"
    _args_schema = _build_args_schema(descriptor)
    http_client = client
    # Closure-local aliases — Python class bodies do NOT inherit names from
    # the enclosing function scope when those names collide with the class's
    # own attribute names (here ``name``, ``description``, ``args_schema``).
    # Aliasing into mangled local names lets us reference the closure values
    # from inside the class body without triggering NameError.
    _tool_name = name
    _tool_description = description

    class _RemoteTool(BaseTool):
        # pydantic allows resetting class-level defaults via type annotations
        # — BaseTool requires name/description as required fields, so we
        # set them explicitly here.
        name: str = _tool_name  # type: ignore[assignment]
        description: str = _tool_description  # type: ignore[assignment]
        args_schema: type[BaseModel] = _args_schema  # type: ignore[assignment]

        def _run(self, **kwargs: Any) -> Any:  # noqa: D401 — CrewAI hook
            call_id = uuid.uuid4().hex
            body = {
                "call_id": call_id,
                "name": self.name,
                "arguments": kwargs,
                # session_id is metadata for the Java side's audit log /
                # observability; not part of the wire-protocol contract
                # for routing, but cheap to send.
                "session_id": session_id,
            }
            logger.debug(
                "tool %s invocation (call_id=%s) → %s", self.name, call_id,
                callback_url,
            )
            owns_client = http_client is None
            local_client = http_client or httpx.Client(timeout=timeout)
            try:
                response = local_client.post(callback_url, json=body, timeout=timeout)
            except httpx.HTTPError as exc:
                # Transport-level failure — surface as a runtime error so
                # CrewAI's tool-failure path runs (rather than silently
                # returning None).
                raise _RemoteToolCallError(
                    f"tool {self.name!r} callback transport failure: "
                    f"{type(exc).__name__}: {exc}",
                ) from exc
            finally:
                if owns_client:
                    local_client.close()

            # 5xx / 4xx (other than 200 with {error}) is a wire-level
            # failure; surface it explicitly.
            if response.status_code != 200:
                raise _RemoteToolCallError(
                    f"tool {self.name!r} callback returned HTTP "
                    f"{response.status_code}: {response.text[:500]}",
                )

            try:
                payload = response.json()
            except ValueError as exc:
                raise _RemoteToolCallError(
                    f"tool {self.name!r} callback returned non-JSON body: "
                    f"{response.text[:500]}",
                ) from exc

            if not isinstance(payload, dict):
                raise _RemoteToolCallError(
                    f"tool {self.name!r} callback returned non-object payload: "
                    f"{payload!r}",
                )

            if "error" in payload:
                # Per protocol: errors come back as HTTP 200 + {error: ...}
                # so the sidecar can route them to CrewAI as recoverable
                # failures. Raising here puts the message into CrewAI's
                # tool-error retry loop.
                raise _RemoteToolCallError(
                    f"tool {self.name!r} reported error: {payload['error']}",
                )

            return payload.get("result", "")

    # Give the generated class a recognisable name in stack traces.
    _RemoteTool.__name__ = f"AtmosphereRemoteTool_{name}"
    _RemoteTool.__qualname__ = _RemoteTool.__name__

    # Instantiate. BaseTool inherits from pydantic BaseModel so the
    # constructor accepts no positional args.
    return _RemoteTool()


def build_remote_tools(descriptors: list[dict[str, Any]],
                       callback_url: str,
                       session_id: str,
                       *,
                       client: httpx.Client | None = None) -> list[Any]:
    """Convenience wrapper: materialise a whole list of descriptors."""
    tools: list[Any] = []
    for descriptor in descriptors or []:
        try:
            tools.append(build_remote_tool(
                descriptor, callback_url, session_id, client=client,
            ))
        except (ValueError, RuntimeError) as exc:
            # Skip the bad descriptor but keep the rest — a single
            # malformed tool shouldn't deny the entire crew run.
            logger.warning(
                "skipping malformed tool descriptor %r: %s", descriptor, exc,
            )
    return tools


def inject_tools_into_crew(crew: Any, tools: list[Any]) -> None:
    """Append ``tools`` to every agent in ``crew``.

    Iterates ``crew.agents`` (the documented mutable list on
    ``crewai.Crew`` as of 1.x) and extends each agent's ``tools`` field.
    If an agent has no ``tools`` attribute, we set a fresh list rather
    than skip — covers BaseAgent subclasses that don't declare the field
    upfront.

    The function is a no-op when ``tools`` is empty so the no-tools path
    stays allocation-free.
    """
    if not tools:
        return
    agents = getattr(crew, "agents", None)
    if not agents:
        logger.warning(
            "crew has no agents; %d remote tool(s) will not be reachable",
            len(tools),
        )
        return
    for agent in agents:
        existing = getattr(agent, "tools", None)
        if existing is None:
            # Some BaseAgent variants leave ``tools`` as None — replace
            # with a fresh list rather than mutating None.
            try:
                agent.tools = list(tools)
            except (AttributeError, TypeError) as exc:
                logger.warning(
                    "could not set tools on agent %r: %s", agent, exc,
                )
            continue
        # Mutate in place: append our tools so any pre-configured
        # agent-local tools survive (covers users wiring tools both in
        # their crew factory AND via @AiTool).
        try:
            existing.extend(tools)
        except AttributeError as exc:
            logger.warning(
                "could not extend tools on agent %r: %s", agent, exc,
            )


# ---- system prompt threading ----------------------------------------------

# Sentinel marker delimiting the Java-supplied system prompt block inside
# each agent's backstory. Stable across calls so we can de-duplicate when
# the same crew gets reused.
_SYSTEM_PROMPT_MARKER_START = "<!-- atmosphere:system_prompt -->"
_SYSTEM_PROMPT_MARKER_END = "<!-- /atmosphere:system_prompt -->"

# Guard against concurrent mutations on the same crew (rare, but the
# sidecar runs an event loop where multiple sessions could theoretically
# touch shared agents).
_PROMPT_LOCK = threading.Lock()


def apply_system_prompt(crew: Any, system_prompt: str | None) -> None:
    """Prepend ``system_prompt`` to every agent's backstory.

    CrewAI 1.x does not expose a single "system_prompt" field on the
    crew — each agent has its own ``role`` + ``goal`` + ``backstory``
    triple that the LLM sees as the system message. The cleanest place
    to weave in an Atmosphere-supplied system prompt is the agent's
    ``backstory`` field (mutable, fed into the system prompt by
    CrewAI's prompt builder).

    Idempotency: if a previous run already injected an Atmosphere block,
    it gets replaced rather than stacked. The markers
    ``<!-- atmosphere:system_prompt --> ... <!-- /atmosphere:system_prompt -->``
    delimit the injected region so factory authors can see what came
    from the framework versus what the user wrote.
    """
    if not system_prompt or not system_prompt.strip():
        return
    agents = getattr(crew, "agents", None)
    if not agents:
        logger.debug(
            "crew has no agents; skipping system_prompt application",
        )
        return
    block = (
        f"{_SYSTEM_PROMPT_MARKER_START}\n"
        f"{system_prompt.strip()}\n"
        f"{_SYSTEM_PROMPT_MARKER_END}"
    )
    with _PROMPT_LOCK:
        for agent in agents:
            existing = getattr(agent, "backstory", "") or ""
            cleaned = _strip_existing_block(existing)
            new_backstory = block + ("\n\n" + cleaned if cleaned else "")
            try:
                agent.backstory = new_backstory
            except (AttributeError, TypeError) as exc:
                logger.warning(
                    "could not set backstory on agent %r: %s", agent, exc,
                )


def _strip_existing_block(text: str) -> str:
    """Remove any prior Atmosphere system_prompt block from ``text``.

    Keeps everything outside the markers intact, so a user-written
    backstory survives repeated calls without accumulating duplicate
    Atmosphere prefixes.
    """
    if _SYSTEM_PROMPT_MARKER_START not in text:
        return text
    start = text.find(_SYSTEM_PROMPT_MARKER_START)
    end = text.find(_SYSTEM_PROMPT_MARKER_END)
    if end < 0 or end < start:
        return text
    end_with_marker = end + len(_SYSTEM_PROMPT_MARKER_END)
    return (text[:start] + text[end_with_marker:]).strip()
