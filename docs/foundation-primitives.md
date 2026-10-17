# Atmosphere AI Agent Foundation Primitives

Atmosphere 4.x ships a set of named primitives — nouns every Java AI agent
needs regardless of what the agent does. Each primitive is runtime-agnostic:
it works across the twelve runtimes (Built-in, Spring AI, LangChain4j,
Google ADK, Koog, Semantic Kernel, Embabel, AgentScope, Spring AI Alibaba,
Anthropic, Cohere, CrewAI) through the same interface.

The governing analogy is Atmosphere 1.0 (2008-2013). 1.0 didn't build a chat
app; it built `AtmosphereResource`, `Broadcaster`, `CometSupport`, and the
rest — nouns every real-time Java app needed. Atmosphere 4.x does the same
for AI agents.

## The eight primitives

### `AgentState` — `org.atmosphere.ai.state`

Unified SPI for conversation history, durable facts, daily notes, working
memory, and hierarchical rules. File-backed by default with an
OpenClaw-compatible layout under `users/<userId>/agents/<agentId>/`.
`AutoMemoryStrategy` (pluggable, four built-ins) decides when turns promote
into facts or daily notes. Admin inspection endpoints expose the workspace
tree so users can see and edit what the agent remembers.

### `AgentWorkspace` — `org.atmosphere.ai.workspace`

Agent-as-artifact SPI. Adapters parse a directory into an
`AgentDefinition`. `OpenClawWorkspaceAdapter` reads the canonical OpenClaw
layout (`AGENTS.md` / `SOUL.md` / `USER.md` / `IDENTITY.md` /
`skills/`) plus Atmosphere-only extension files (`CHANNELS.md` / `MCP.md` /
`RUNTIME.md` / `PERMISSIONS.md` / `SKILLS.md`). `AtmosphereNativeWorkspaceAdapter`
accepts any directory as a fallback. Third-party adapters ship through
`ServiceLoader`.

### `ProtocolBridge` — `org.atmosphere.ai.bridge`

Inbound facade for how agents are reached. `InMemoryProtocolBridge` puts
in-JVM dispatch on equal architectural footing with wire bridges (MCP,
A2A, AG-UI, gRPC) — the Atmosphere 1.0 Broadcaster pattern applied to
agent dispatch. `ProtocolBridgeRegistry` enumerates active bridges for
admin inspection.

### `AiGateway` — `org.atmosphere.ai.gateway`

Outbound facade for every LLM call leaving Atmosphere. One admission point
for per-user rate limiting, per-user credential resolution, and unified
trace emission. Built on top of the existing router / budget / metrics
machinery so consolidation is the change, not new behavior. `CredentialResolver`
and `GatewayTraceExporter` are pluggable with `noop()` defaults.

### `AgentIdentity` — `org.atmosphere.ai.identity`

Per-user identity, permissions, credentials, audit trail, and session
sharing. `PermissionMode` (`DEFAULT` / `PLAN` / `ACCEPT_EDITS` / `BYPASS` /
`DENY_ALL`) layers over per-tool `@RequiresApproval`. `CredentialStore`
pluggable — `InMemoryCredentialStore` for tests, `AtmosphereEncryptedCredentialStore`
(AES-GCM / 256-bit key / random IV / fail-closed decryption) for production.
Read-only session share tokens for giving others view-only access to
a conversation.

### `ToolExtensibilityPoint` — `org.atmosphere.ai.extensibility`

How agents acquire new capabilities at runtime. `ToolIndex` scores tool
descriptors by token-overlap for bounded discovery and `DynamicToolSelector`
enforces `maxToolsPerRequest`. `ToolSelection` composes them through the
`ToolExtensibilityPoint` facade on every dispatch (stream and non-stream),
so the facade is the per-request composition root, not just an embedder
convenience. `McpTrustProvider` is the per-user MCP credential SPI consumed
by `McpToolSource.connectForUser` (atmosphere-mcp-client): the resolved
credential rides the outbound `Authorization: Bearer` header, and a user
who has not authorized the server is refused before any network I/O
(fail closed).

### `Sandbox` — `org.atmosphere.ai.sandbox`

Pluggable isolated execution for untrusted code, shell commands, and data
transforms. `DockerSandboxProvider` is the production default (shells out
to `docker` CLI; resource limits via `--cpus` / `--memory` / timeouts).
`InProcessSandboxProvider` is a dev-only reference backend — not a security
boundary. `@SandboxTool` binds a tool method to a sandbox backend; no
silent fallback if the requested backend is unavailable. Default limits:
1 CPU · 512 MB · 5 min wall · no network.

### `AgentResumeHandle` — `org.atmosphere.ai.resume`

Run ID + registry + bounded event replay buffer for mid-stream reconnect.
Closes the gap where `DurableSessionInterceptor` reattached rooms and
broadcasters on reconnect but not in-flight agent runs. Clients that
disconnect mid-stream reattach via `runId` and receive the events they
missed, up to the buffer's bounded capacity (oldest-evicted). On WebSocket,
SSE and streaming, a reattach to a run still in flight also receives the
run's later events through its terminal frame, unless it is a member of the
run's `broadcastReply` room, which the room's broadcast delivers them to
already (the session broadcasting a room run's reply belongs to the run, not
to the prompter's connection: it goes on broadcasting when that connection is
lost or closed, and is closed by the run's `complete`, `error` or `cancelled`
frame — a run is cancelled when its prompter left or its grace expired, whether
a model call or the `@Prompt` method itself writes the reply, and what is sent
after that is dropped; an `@Prompt` method that returns with the session still
open leaves the run going to the session's own terminal frame while the
prompter is connected or within its grace, and the prompter gone for good
cancels it then — or the idle sweep does, when the session stays idle
(`cancelled` `sweep`) or is still open the session TTL after the method
returned, however often it writes (`cancelled` `timeout`), whatever became of
the prompter's connection — or the destroy of the endpoint that started it
(`cancelled` `operator`), which leaves other endpoints' runs, their reattach
graces and the shared sweeper and grace timer to them; the prompter's WebSocket, SSE or streaming reattach
registers the run's session under the connection it reattached on, so an
operator's cancel addressed to that connection reaches the run, and that
connection departing ends it only once nobody else listens and the connection
the run started on is gone too;
at most 1,024 such runs per registry are kept live
(`atmosphere.ai.max-runs-open-after-prompt`), a run past that ending at the
method's return, as `complete`; the room
receives that frame too, the run's replay ends with it, and a room destroyed
meanwhile is not recreated by it, its frames going to a room created again
under the same id); a long-polling poll gets the
buffered events its tracking id was not sent yet — as a replay, or in a
long-polling reply of its own; after an event that does not count as sent
(one it never received, a `broadcastReply` room frame not kept for it, or a
reply of a tracking id the run forgot: it remembers 16 tracking ids, a
`broadcastReply` room's run as many as the room takes long-polling members
(1,024 by default), the least recently served forgotten first, and a client
gone for good frees its place; init-param
`org.atmosphere.ai.longPolling.maxReplayPositionsPerRun` overrides both; a
forgotten tracking id is sent the retained events again),
every later event counts as not sent — in one reply, at once, and a
later poll with nothing new waits: for the run's next event while the run is
going (for polls of up to `RunEventReplayBuffer.MAX_WAITING_POLLS` (16) tracking
ids per run by default, init-param `org.atmosphere.ai.longPolling.maxWaitingPollsPerRun`
for the runs an AI endpoint starts,
which take none of the streaming reattaches' places; the poll of the prompter's
own long-polling client in a `broadcastReply` room waits on the run too, but is
written the room's frames, which reach its polls already, not the run's), else for a
reply or its suspend timeout. Every member of a `broadcastReply` room is handed
the run id with the room's reply; a member other than the prompter (its tracking
id is not the one that sent the prompt and, when the run has an authenticated
owner, it is not authenticated as that user) that reattaches with it, on any
transport, is sent what it missed, but neither restarts nor holds off the run's
reattach grace, and does not wait on the run. A reloaded page, which
atmosphere.js gives a new tracking id, is still the prompter in an
authenticated room; in an anonymous `broadcastReply` room it is not: it gets
the replay, but the run is cancelled when the grace ends. A run that already
finished stays replayable for the
registry TTL (at most `RunRegistry.MAX_RETAINED_COMPLETED` finished runs are
kept). The events are the text and terminal frame the client received,
whether the `@Prompt` method sent them or the runtime streamed them for
`session.stream(...)`. A lost connection — a page reload included, over
WebSocket, WebTransport, SSE, long-polling and streaming — leaves a model call still in flight
going for the reattach grace (system property `atmosphere.ai.reattach.grace`
or env `LLM_REATTACH_GRACE`, default 30s): a
reattach within it by the run's own client gets the rest of the run through
`complete`. When the grace expires — or at once when the client closed the connection on purpose — the
call is cancelled, and a reattach gets the tokens streamed until then, then the
run's `cancelled` frame.

## Proof samples

Two samples prove the primitives work end-to-end. They exercise every
primitive between them.

### `samples/spring-boot-personal-assistant`

Primary coordinator plus a scheduler / research / drafter crew dispatched
over `InMemoryProtocolBridge`. Exercises `AgentState`, `AgentWorkspace`,
`AgentIdentity`, `ToolExtensibilityPoint`, `AiGateway`, and
`ProtocolBridge`. Ships an OpenClaw-compatible workspace under
`src/main/resources/agent-workspace/`.

### `samples/spring-boot-coding-agent`

Clones a Git repository into a Docker sandbox, reads files, proposes a
patch. Exercises `Sandbox` and `AgentResumeHandle`. Runs with the
in-process fallback when Docker is not available; production deployments
pin the Docker provider.

Note for sample authors: `StreamingSession.stream(String)` dispatches the
argument to the LLM as a fresh user turn; use `StreamingSession.send(String)`
when the intent is to push literal text (log lines, command output, file
bytes) to the client unchanged. The coding-agent flow uses `send()` + an
explicit `complete()` so the real README content reaches the UI instead of
being routed through the LLM.

## OpenAI API compatibility

The Built-in runtime speaks OpenAI Chat Completions and works against any
endpoint that exposes the OpenAI wire shape — OpenAI itself, local proxies
(Embacle, Ollama), cloud providers that ship an OpenAI-compatible
surface. Some of those endpoints are stricter than OpenAI itself on
tool-call round trips: OpenAI treats `tool_calls` on assistant messages
and `name` on tool messages as optional, but strict endpoints require
both. `OpenAiCompatibleClient` now serializes both unconditionally —
additive for OpenAI, required for the stricter crowd. The JSON wire shape
is pinned by `ChatMessageSerializationTest` so future refactors cannot
silently regress either side.

## Non-goals (explicit)

- Graph workflow DSL (LangGraph territory; `@Coordinator` + `AgentFleet` +
  `CheckpointStore.fork()` already cover orchestration).
- Voice / realtime pipeline (specific modality, deferred).
- New LLM client abstraction (the `AgentRuntime` adapters remain the LLM client layer).
- Backward-compatibility shims for deprecated SPIs, except the documented
  `AiConversationMemory` thin delegation to `AgentState`.
- Custom Atmosphere YAML manifest format (OpenClaw workspace IS the
  manifest).
- `AgentEval` as a new primitive — existing `LlmJudge` / `ResultEvaluator`
  already ship.
- Product-level positioning — samples prove the foundation; the foundation
  is the product.

## Prompt regression workflow

`modules/ai-test` includes `GoldenEvalBaseline` for pinning LLM-as-judge prompts
and verdict parsing in tests. The helper writes the exact judge prompt, raw
judge response fixture, parsed verdict, and quality scores as JSON so prompt
template changes produce a small diff instead of silently changing eval
behavior.

```java
var baseline = GoldenEvalBaseline.intent(
    "support-answer",
    "How do I reconnect?",
    "Use Atmosphere reconnect with bounded replay.",
    "Explains reconnect behavior",
    "{\"verdict\": true}");

baseline.write(Path.of("src/test/resources/golden/support-answer.json"));

var golden = GoldenEvalBaseline.read(Path.of("src/test/resources/golden/support-answer.json"));
golden.assertMatches(GoldenEvalBaseline.intent(
    "support-answer",
    "How do I reconnect?",
    "Use Atmosphere reconnect with bounded replay.",
    "Explains reconnect behavior",
    "{\"verdict\": true}"));
```

Use this next to `LlmJudge` tests whenever prompt text is part of the contract.
The golden file captures deterministic fixtures; live model judging remains an
integration test choice and should be separated from template-regression tests.
