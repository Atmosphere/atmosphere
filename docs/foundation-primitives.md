# Atmosphere AI Agent Foundation Primitives

Atmosphere 2.x ships a set of named primitives — nouns every Java AI agent
needs regardless of what the agent does. Each primitive is runtime-agnostic:
it works across the seven hosted runtimes (Built-in, Spring AI, LangChain4j,
Google ADK, Koog, Semantic Kernel, Embabel) through the same interface.

The governing analogy is Atmosphere 1.0 (2008-2013). 1.0 didn't build a chat
app; it built `AtmosphereResource`, `Broadcaster`, `CometSupport`, and the
rest — nouns every real-time Java app needed. Atmosphere 2.x does the same
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
layout (`AGENTS.md` / `SOUL.md` / `USER.md` / `IDENTITY.md` / `MEMORY.md` /
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
descriptors by token-overlap for bounded discovery; `DynamicToolSelector`
enforces `maxToolsPerRequest` so agents with many tools don't inject every
descriptor into every prompt. `McpTrustProvider` resolves per-user MCP
credentials; the default `CredentialStoreBacked` variant reuses the
`CredentialStore` from `AgentIdentity`.

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
missed, up to the buffer's bounded capacity (oldest-evicted).

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

## Definition of "shipped" vs "complete"

Per the external review on the foundation branch:

- **Shipped** — SPI + default implementation + unit tests + zero-warning
  compile.
- **Complete** — shipped + wired into every live code path + cross-runtime
  contract tests green on all seven runtimes + security invariants
  verified.

The eight primitives above are all shipped. Completion lands as follow-up
work in three phases:

- **Phase 1.5 — wire-in pass**: `AiGateway` becomes a mandatory choke
  point through each runtime's LLM dispatch; `runId` threads through
  `StreamingSession`; `DurableSessionInterceptor` consults `RunRegistry`
  on reconnect; wire bridges for MCP / A2A / AG-UI / gRPC implement
  `ProtocolBridge`.
- **Phase 4 — cross-runtime contract test matrix**: one contract test per
  primitive, run against all seven runtimes in CI.
- **Phase 5 — security defaults**: default-deny auth on admin surfaces,
  `SandboxLimits.network` extended to `NetworkPolicy` enum
  (`NONE` / `GIT_ONLY` / `ALLOWLIST`).

## Non-goals (explicit)

- Graph workflow DSL (LangGraph territory; `@Coordinator` + `AgentFleet` +
  `CheckpointStore.fork()` already cover orchestration).
- Voice / realtime pipeline (specific modality, deferred).
- New LLM client abstraction (our clients are the seven runtimes).
- Backward-compatibility shims for deprecated SPIs, except the documented
  `AiConversationMemory` thin delegation to `AgentState`.
- Custom Atmosphere YAML manifest format (OpenClaw workspace IS the
  manifest).
- `AgentEval` as a new primitive — existing `LlmJudge` / `ResultEvaluator`
  already ship.
- Product-level positioning — samples prove the foundation; the foundation
  is the product.
