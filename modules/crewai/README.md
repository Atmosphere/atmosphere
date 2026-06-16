# atmosphere-crewai

`AgentRuntime` for the [CrewAI](https://www.crewai.com/) multi-agent
framework. Java does not embed Python — instead the runtime talks to a
**CrewAI sidecar process** over HTTP + SSE. This is the same pattern
many JVM teams already use to bring Python-native agent frameworks
(CrewAI, AutoGen, LlamaIndex) into Java services without rewriting the
agent logic.

## What ships

This module covers both halves of the bridge:

- ✅ `CrewAiAgentRuntime` implementing `AgentRuntime`
- ✅ `HttpSseSidecarClient` driving HTTP + SSE against a sidecar URL
- ✅ Process-less cancellation (DELETE on the sidecar session id)
- ✅ Bidirectional tool-RPC bridge: `ToolCallbackServer` on the Java
  side exposes a loopback-only HTTP endpoint that the sidecar POSTs to
  whenever CrewAI invokes a Java `@AiTool`. Java tools route through
  `ToolExecutionHelper.executeWithApproval` so approval gates,
  validation, and governance apply identically to the in-process
  tool path.
- ✅ Python sidecar (`atmosphere-crewai-bridge`, in `sidecar/`) speaking
  the wire protocol and materialising Java tool descriptors as native
  `crewai.tools.BaseTool` subclasses with `pydantic.create_model`-built
  argument schemas.
- ✅ System-prompt threading: `context.systemPrompt()` is prepended to
  every agent's `backstory` inside a delimited
  `<!-- atmosphere:system_prompt --> ... <!-- /atmosphere:system_prompt -->`
  block so the directive is distinguishable from user-supplied prose.
- ✅ Bridge test suite (`CrewAiAgentRuntimeBridgeTest` + `CrewAiToolBridgeTest`)
  running against an in-process `com.sun.net.httpserver.HttpServer` that
  speaks the documented wire protocol — 18 Java tests, all passing.
- ✅ Sidecar test suite (`sidecar/tests/`) covering wire shape, session
  lifecycle, tool materialisation, callback transport, and end-to-end
  system-prompt injection — 26 Python tests, all passing.

The runtime stays **unavailable out of the box** — `isAvailable()`
returns `false` until you point `ATMOSPHERE_CREWAI_SIDECAR_URL` at a
running sidecar that responds OK to `GET /health`. This is deliberate
per Correctness Invariant #5 (Runtime Truth): the runtime never
advertises availability based on the classpath.

Capabilities declared (9): `TEXT_STREAMING`, `TOKEN_USAGE`,
`AGENT_ORCHESTRATION`, `CANCELLATION`, `TOOL_CALLING`, `SYSTEM_PROMPT`,
`TOOL_APPROVAL`, `STRUCTURED_OUTPUT`, `PER_REQUEST_RETRY`. History is
forwarded on every start, but `CONVERSATION_MEMORY` is not declared
because the runtime does not own a memory store — that surface stays
inside the sidecar's crew.

## When to use this module

Pick this runtime when you want to run a CrewAI crew (multi-agent,
role-based, sequential-or-parallel task graph) from a Java / Spring
Boot / Quarkus service, and:

- The agent logic is already written in Python and should stay there.
- You want Atmosphere's transport (WebSocket / SSE / long-poll) to
  carry the crew's tokens straight to the browser as they're produced.
- You want JVM ownership of session lifecycle: cancel, timeout,
  budget, governance — Java decides, Python executes.

## Quickstart

Add the dependency:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-crewai</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
```

Install and run the sidecar from source (`sidecar/README.md` has the
full install/run guide):

```bash
pipx install ./modules/crewai/sidecar
atmosphere-crewai-bridge --host 127.0.0.1 --port 8765 --crew my_crew:Crew
```

Point Atmosphere at it:

```bash
export ATMOSPHERE_CREWAI_SIDECAR_URL=http://127.0.0.1:8765
./mvnw spring-boot:run -pl samples/spring-boot-ai-chat
```

The runtime registers via `ServiceLoader` at priority 50. When the
sidecar URL is set and `/health` responds OK, `isAvailable()` flips to
`true` and the framework dispatches `@Agent` calls through CrewAI.

## Configuration reference

| System property | Env var | Default | Description |
|---|---|---|---|
| `atmosphere.crewai.sidecar.url` | `ATMOSPHERE_CREWAI_SIDECAR_URL` | — | Base URL of the CrewAI sidecar (e.g. `http://127.0.0.1:8765`). When unset, the runtime stays unavailable. |
| `atmosphere.crewai.sidecar.health.timeout.ms` | `ATMOSPHERE_CREWAI_SIDECAR_HEALTH_TIMEOUT_MS` | `2000` | Health-probe timeout. A failed probe leaves the runtime unavailable. |
| `atmosphere.crewai.sidecar.request.timeout.ms` | `ATMOSPHERE_CREWAI_SIDECAR_REQUEST_TIMEOUT_MS` | `60000` | Per-request timeout for `POST /v1/sessions` and SSE drain. |

System properties win over env vars when both are set.

## Capability inventory

Declared by `CrewAiAgentRuntime.capabilities()`. The honest floor —
every entry maps to a code path `drainStream()` actually exercises:

| Capability | Status | Notes |
|---|---|---|
| `TEXT_STREAMING` | ✅ | SSE `event: token` → `session.send()` |
| `TOKEN_USAGE` | ✅ | SSE `event: usage` → `session.usage()` |
| `AGENT_ORCHESTRATION` | ✅ | CrewAI is fundamentally a multi-agent orchestrator; the sidecar owns the crew graph and surfaces a single stream back |
| `CANCELLATION` | ✅ | `executeWithHandle().cancel()` issues `DELETE /v1/sessions/{id}` (idempotent) |
| `TOOL_CALLING` | ✅ | Java `@AiTool` descriptors travel on the start request; the sidecar materialises them as `crewai.tools.BaseTool` subclasses; tool calls round-trip via the loopback `ToolCallbackServer` |
| `TOOL_APPROVAL` | ✅ | `ToolCallbackServer` routes every invocation through `ToolExecutionHelper.executeWithApproval` so `@RequiresApproval` gates fire identically to in-JVM tool paths |
| `SYSTEM_PROMPT` | ✅ | `context.systemPrompt()` is prepended to each agent's backstory inside a delimited Atmosphere block (idempotent) |
| `STRUCTURED_OUTPUT` | ✅ | Pipeline-layer schema injection via SYSTEM_PROMPT — `AiPipeline.StructuredOutputCapturingSession` captures the typed return |
| `PER_REQUEST_RETRY` | ✅ | `AbstractAgentRuntime.executeWithOuterRetry` wraps `doExecute` per the standard `RetryPolicy` on `AgentExecutionContext` |
| `CONVERSATION_MEMORY` | ❌ | History is forwarded on every start, but the runtime owns no Java-side store; the sidecar's crew handles per-task memory natively |
| `VISION` / `AUDIO` / `MULTI_MODAL` | ❌ | The current sidecar wire shape carries text-only `history[].content` strings; multi-modal would require a `content[]` array shape on both sides |
| `PROMPT_CACHING` / `TOOL_CALL_DELTA` / `BUDGET_ENFORCEMENT` / `CONFIDENCE_SCORES` / `PASSIVATION` | ❌ | Not currently declared |

## Sidecar wire protocol

Documented here so an alternative sidecar (e.g. AutoGen, LlamaIndex)
can speak the same shape and reuse `HttpSseSidecarClient`.

### `GET /health`

200 OK with any body when the sidecar is alive. Used by the runtime to
gate `isAvailable()`.

### `POST /v1/sessions`

Request:

```json
{
  "message": "user prompt",
  "model": "gpt-4o",
  "history": [
    {"role": "user", "content": "..."},
    {"role": "assistant", "content": "..."}
  ],
  "options": {},
  "system_prompt": "You are a careful research assistant.",
  "tools": [
    {
      "name": "lookup_order",
      "description": "Look up an order by id",
      "parameters": [
        {"name": "order_id", "type": "string",
         "description": "the id", "required": true}
      ],
      "return_type": "string"
    }
  ],
  "tool_callback_url": "http://127.0.0.1:54321/v1/tools/call"
}
```

`system_prompt`, `tools`, and `tool_callback_url` are optional. When
`tools` is empty (or absent) and `system_prompt` is null, the body
shape is identical to the pre-tool-bridge protocol so older sidecars
stay forward-compatible. The bridge rejects `tools` without
`tool_callback_url` with `400 Bad Request` (Correctness Invariant #4 —
Boundary Safety).

### Tool callback — `POST <tool_callback_url>`

When CrewAI invokes a remote tool, the sidecar POSTs to the loopback
callback URL the Java side advertised:

```json
{
  "call_id": "uuid-...",
  "name": "lookup_order",
  "arguments": {"order_id": "A123"},
  "session_id": "sess_..."
}
```

Response (HTTP 200 always, even for tool-execution errors so the
sidecar can route them back to CrewAI as recoverable failures):

```json
{"result": "<formatted tool result>"}
```

or

```json
{"error": "<message>"}
```

Non-2xx responses are reserved for transport-layer failures
(malformed JSON → 400, pool saturation → 503).

Response: 200 OK with `Content-Type: text/event-stream`. The session
id is exposed two ways and the runtime accepts whichever arrives first:

- `X-Atmosphere-CrewAI-Session` response header (preferred — available
  before the first SSE frame, so cancel is wired immediately).
- `event: session` frame with `data: {"sessionId": "..."}` (fallback
  when the sidecar cannot set the header before the stream starts).

SSE event types:

```
event: session
data: {"sessionId":"sess_abc123"}

event: token
data: {"text":"hello"}

event: token
data: {"text":" world"}

event: usage
data: {"input": 12, "output": 3, "total": 15}

event: done
data: {}
```

On error:

```
event: error
data: {"message":"..."}
```

### `DELETE /v1/sessions/{id}`

204 No Content. Cancels the in-flight crew. The runtime issues this
when `ExecutionHandle.cancel()` fires. Sidecars MUST make this
idempotent — the runtime is permitted to call it multiple times.

## What's NOT in this module

Per [`feedback_no_per_runtime_samples.md`](../../.claude/memory/feedback_no_per_runtime_samples.md):

- **No `spring-boot-crewai-chat` sample.** When the sidecar package
  ships, the existing `samples/spring-boot-ai-chat` will pick CrewAI
  up via `ServiceLoader` as long as `ATMOSPHERE_CREWAI_SIDECAR_URL` is
  set. One sample, many runtimes.

## Architecture

```
┌──────────────────────┐         HTTP+SSE          ┌──────────────────────┐
│  Java / Atmosphere   │ ────POST /v1/sessions───► │  CrewAI sidecar      │
│                      │ ◄──── event: token  ───── │  (Python, FastAPI)   │
│  @Agent methods      │ ◄──── event: usage  ───── │                      │
│  StreamingSession    │ ◄──── event: done   ───── │  crewai.Crew()       │
│                      │ ────DELETE on cancel────► │  .kickoff()          │
└──────────────────────┘                           └──────────────────────┘
        │                                                     │
        │ session.send() → Atmosphere transport               │ tool RPC over loopback
        ▼                                                     ▼
┌──────────────────────┐                           ┌──────────────────────┐
│  Browser             │                           │  @AiTool Java method │
│  (WS / SSE / poll)   │                           │  (callback)          │
└──────────────────────┘                           └──────────────────────┘
```

The bridge is deliberately thin. CrewAI owns the crew graph, the LLM
calls, and the agent reasoning. Atmosphere owns the transport, the
session lifecycle, the cancellation primitive, and the tool execution
boundary (via `ToolCallbackServer`).
