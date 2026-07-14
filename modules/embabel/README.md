# Atmosphere Embabel Adapter

`AgentRuntime` implementation backed by Embabel `AgentPlatform`. When this JAR is on the classpath, `@AiEndpoint` can run Embabel agents and stream their output to browser clients.

Use this adapter when Embabel's goal-oriented action planning (GOAP) is already your agent layer. Atmosphere keeps Embabel in charge of native planning and adds the service layer around it — real-time client transports (WebSocket, SSE, long-polling, gRPC), `@Agent`/`@AiEndpoint` dispatch, governance and HITL approval, durable sessions and session-tape replay, and MCP/A2A/AG-UI exposure of the same agent. It runs on top of Embabel; it does not replace it. **Spring Boot 3 only today:** this adapter requires `atmosphere-spring-boot3-starter` and the `-Pspring-boot3` profile.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-embabel</artifactId>
    <version>${project.version}</version>
</dependency>
```

## Quick Start

```java
@AiEndpoint(path = "/ai/chat", systemPrompt = "You are a helpful assistant")
public class MyChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // uses Embabel when atmosphere-embabel is on classpath
    }
}
```

For direct usage (Kotlin):

```kotlin
val session = StreamingSessions.start(resource)
embabelAdapter.stream(AgentRequest("assistant") { channel ->
    agentPlatform.run(prompt, channel)
}, session)
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `EmbabelStreamingAdapter` | Bridges Embabel agents to `StreamingSession` |
| `AtmosphereOutputChannel` | Routes Embabel `OutputChannelEvent` to `StreamingSession` |
| `EmbabelGoapPlanBridge` | Read-only `AgenticEventListener` mirroring the GOAP plan into `AiEvent.PlanUpdate` frames |
| `EmbabelAgentRuntime` | `AgentRuntime` SPI implementation (priority 100) |
| `EmbabelEmbeddingRuntime` | `EmbeddingRuntime` SPI wrapping Embabel `EmbeddingService` (priority 170) |
| `EmbabelPromptRunner` | Per-request bridge for stacking framework-native customizers (`withTemperature`, `withModel`, `withGuardrails`, ...) on the runtime's pre-configured `PromptRunner` |
| `AtmosphereEmbabelAutoConfiguration` | Spring Boot auto-configuration |

## Native Streaming (Atmosphere-Native Path)

The Atmosphere-native dispatch path (used when `context.agentId()` does not
match a deployed `@Agent`) drives Embabel via
[`StreamingPromptRunnerBuilder`](https://github.com/embabel/embabel-agent),
acquiring a Reactor `Flux<String>` of token-level chunks and forwarding each
chunk to `StreamingSession.send()`. This is the path that makes
`TEXT_STREAMING` an honest capability declaration on the Atmosphere-native
side — without it, the Atmosphere-native path was buffering the model
response and emitting it as a single end-of-call burst.

When the configured Embabel LLM service does not implement
`com.embabel.agent.spi.streaming.StreamingLlmOperations` (true for some
tool-only or non-OpenAI-compatible providers), the path falls back to the
blocking `PromptRunner.generateText(prompt)` call so dispatch still
completes — no silent capability drop.

The deployed-agent dispatch path (used when `context.agentId()` matches a
deployed `@Agent`) continues to route through `AgentPlatform.runAgentFrom`
and stream events via `AtmosphereOutputChannel`; the Reactor `Flux` path
applies only to the direct-LLM fallback.

## Per-Request PromptRunner Customizer (`EmbabelPromptRunner`)

By default the Atmosphere-native dispatch path builds a `PromptRunner` via
`Ai.withDefaultLlm()` and threads system prompt, history, tools, and image
parts onto it. To stack Embabel-native modifiers per request without
growing the unified `AgentRuntime` SPI with framework-specific knobs,
attach a `UnaryOperator<PromptRunner>` customizer via
`EmbabelPromptRunner.attach`:

```java
var ctx = EmbabelPromptRunner.attach(baseContext,
        runner -> runner.withTemperature(0.2).withModel("gpt-4o"));
runtime.execute(ctx, session);
```

Or in Kotlin:

```kotlin
val ctx = EmbabelPromptRunner.attach(baseContext) { runner ->
    runner.withTemperature(0.2).withModel("gpt-4o")
}
runtime.execute(ctx, session)
```

The customizer fires **after** the runtime's default wiring, so callers
override or extend behavior without losing the system prompt + history
the runtime already installed. Only fires on the Atmosphere-native
dispatch path — the deployed-`@Agent` path bypasses `PromptRunner`
entirely (the deployed agent owns its own configuration). The bridge is
exclusive (one customizer per request); compose multiple customizers
into one before attaching if needed.

## Native Planning (GOAP Plan Observation)

GOAP plan observation is an **explicit opt-in** (`atmosphere.ai.planning=native`);
`EmbabelAgentRuntime` deliberately does **not** declare `AiCapability.PLANNING`
(see "Why the capability is not declared" below). When opted in, the
deployed-agent dispatch path (`executeDeployedAgent`) registers an
`EmbabelGoapPlanBridge` (an Embabel `AgenticEventListener`) on the
`ProcessOptions`, and every `AgentProcessPlanFormulatedEvent` /
`ReplanRequestedEvent` is mirrored into an `AiEvent.PlanUpdate` frame so the
console renders the live plan — executed `ActionInvocation`s as completed
steps, the freshly formulated plan's actions as pending steps. When an
`AgentPlanStore` is in the dispatch scope the mirrored plan is also persisted
under the same `(agentId, conversationId)` key the `write_todos` floor uses,
so the admin plan endpoint and the console Workspace tab's stored view work
for the native surface too (best-effort: a store failure is WARN-logged and
never aborts the run).

Two honesty caveats, by design:

- **The plan is framework-computed, read-only.** Embabel's plan is a
  deterministic A* GOAP plan derived from `@Action` pre/post-conditions,
  re-planned after every action — the model never authors or updates it.
  The emitted plan goal carries a `GOAP:` marker so consoles show it is
  framework-computed, not a model-maintained todo list.
- **Deployed-agent path only.** The Atmosphere-native fallback path drives a
  direct `PromptRunner` with no planner, so no plan surface exists there
  (the same path asymmetry as `TOKEN_USAGE`).

**Why the capability is not declared.** Each native surface here is real on
exactly one of the two dispatch paths — the GOAP bridge only on the
deployed-agent path, `AtmosphereFileTools` only on the Atmosphere-native
path. A static `AiCapability` declaration makes the harness presets suppress
the portable floors on *every* path (AUTO picks one surface, never both), so
the uncovered path would have no plan / file surface at all while the console
reported `ACTIVE(native:embabel)` — a Runtime-Truth violation (Invariant #5).
Under the `AUTO` default the portable floors therefore own the surface on all
paths; `atmosphere.ai.planning=native` attaches the GOAP bridge instead
(deployed path only, and the floor is skipped — never both).

## Native Virtual Filesystem (Embabel FileTools)

The Embabel `FileTools` surface is an **explicit opt-in**
(`atmosphere.ai.filesystem=native`); `AiCapability.VIRTUAL_FILESYSTEM` is
deliberately **not** declared for the same path-asymmetry reason as PLANNING
above (the deployed-agent path cannot receive per-process tools, so a
declaration would leave it with no file surface while suppressing the
built-in floor). When opted in and the harness FILESYSTEM primitive
scopes an `AgentFileSystem` onto the session, `executeAtmosphereNative`
attaches `AtmosphereFileTools` — Embabel's own `FileTools` tool surface
(`createFile` / `writeFile` / `editFile` / `appendFile` / `delete` /
`createDirectory` / `readFile`, plus the native read helpers `listFiles` /
`findFiles` / `fileSize` / `fileCount`) — rooted at the **same**
conversation-scoped directory (`files/{conversationId}/`) the built-in tool
floor and the console Workspace tab read. The per-run `FileChangeLog` audit
is mirrored onto the wire as one `ai.embabel.file_changes` metadata frame
(one net entry per path — Embabel's `DefaultFileChangeLog` contract).

Honesty notes, by design:

- **Bounds are enforced, not bypassed.** A raw `FileTools.readWrite(root)`
  would skip `AgentFileSystem.Limits` (Embabel's default write methods hit
  the disk directly). `AtmosphereFileTools` keeps Embabel's tool surface but
  routes every mutation — and the model-facing `readFile` — back through the
  `WorkspaceAgentFileSystem`, so the per-file / file-count / total-byte caps
  and traversal guards hold identically to the built-in floor. `editFile`
  follows the store's exactly-once-match contract (the built-in `edit_file`
  semantics) instead of Embabel's default replace-all; its tool description
  says so.
- **Atmosphere-native path only.** Embabel's `ProcessOptions` has no
  per-process tool surface, so a deployed `@Agent` dispatch cannot receive
  the bridged tools — deployed agents own their own tool configuration (the
  same path asymmetry as `TOOL_CALLING` / `VISION`). The runtime logs a WARN
  and emits `ai.embabel.dropped_features` when a deployed dispatch would
  silently lose a scoped file store.
- **Real-disk stores only.** Embabel's `FileTools` contract requires a disk
  root; the shipped `WorkspaceAgentFileSystem` (and the
  `AgentFileSystemProvider` views derived from it) qualifies. A custom
  `AgentFileSystem` implementation without a disk root is skipped loudly —
  force `atmosphere.ai.filesystem=builtin` to restore a file surface there.

Activation follows `atmosphere.ai.filesystem` (`FilesystemMode`): under
`AUTO` (default) and `NATIVE` the native surface owns the file tools and the
harness skips the built-in eight-tool floor; under `BUILTIN` only the portable
floor registers — never both. Pinned by `EmbabelAgentRuntimeVfsTest`
(round-trips both directions through the shared directory).

## Native Tool-Loop Enforcement (`ToolLoopPolicy`)

A per-request `ToolLoopPolicy` (`ai.toolLoop.policy` metadata, e.g.
`ToolLoopPolicy.strict(3)`) is enforced **inside** Embabel's own tool loop on
the Atmosphere-native path. Embabel 0.5.0's `PromptRunner` exposes
`withToolLoopInspectors` / `withToolLoopTransformers`, and `EmbabelToolLoopBridge`
registers on both:

- **Transformer seam (the genuine stop).** Embabel's `DefaultToolLoop` /
  `ParallelToolLoop` continue only while the transformed LLM response still
  carries tool calls. At the cap the bridge returns a plain `AssistantMessage`
  with the tool calls stripped, so no further model→tool round dispatches. The
  replacement text is guaranteed non-blank (stripped response's own text → last
  non-blank assistant text → a fixed cap notice) so Embabel's `EmptyResponsePolicy`
  does not re-enter the loop.
- **Inspector seam (a backstop).** `ToolLoopInspector` is observe-only by
  contract (Embabel swallows inspector exceptions), so it cannot stop the loop;
  it mirrors the wire-layer `ToolLoopGuard.onModelStart` — if a dispatch somehow
  runs past the cap, a `FAIL` policy still aborts via `session.error(...)`.

Breach reporting matches the wire guard exactly: `OnMaxIterations.FAIL` fires a
single `ToolLoopExhaustedException` frame; `COMPLETE_WITHOUT_TOOLS` — a no-op
for the wire guard, which sits outside the loop — is honored natively here, the
loop completing with the model's last text. The cross-runtime `ToolLoopGuard`
from `execute()` stays installed as defense-in-depth. This is the
Atmosphere-native path only: the deployed-agent path
(`AgentPlatform.runAgentFrom`) exposes no per-request `PromptRunner` seam and
keeps the wire guard alone — the same path asymmetry documented for
`TOKEN_USAGE`. Pinned by `EmbabelToolLoopBridgeTest`.

## Samples

- [Spring Boot AI Chat](../../samples/spring-boot-ai-chat/) -- swap to `atmosphere-embabel` dependency for Embabel support

## Full Documentation

See the [atmosphere-ai capability matrix](../ai/README.md#capability-matrix) and
<https://atmosphere.github.io/docs/reference/ai/> for the unified capability matrix
across all runtimes.

## Human-in-the-Loop (HITL)

On the Atmosphere-native dispatch path, `EmbabelAgentRuntime` bridges Atmosphere
`ToolDefinition`s into Embabel's `PromptRunner.withTools(...)` and routes every
tool invocation through the unified `ToolExecutionHelper.executeWithApproval`
gate, so `@RequiresApproval` fires the same approval parking/timeout flow as every
other tool-calling runtime (Built-in, LangChain4j, Spring AI, Koog, ADK).
`EmbabelAgentRuntime` declares `TOOL_APPROVAL` on that basis.

A deployed Embabel `@Agent` that runs entirely inside its own `AgentPlatform`
owns its tool execution — for those flows, enforce gating at the Embabel
`@AgentAction` level, or route the sensitive tool through the Atmosphere-native
dispatch path so the approval gate applies.

## Requirements

- Java 21+
- `atmosphere-ai` (transitive)
- Embabel Agent API 0.5.0+
- Kotlin runtime
- **Spring Boot 3.5** — Embabel framework does not yet support Spring Boot 4. Use `atmosphere-spring-boot3-starter` and the `-Pspring-boot3` Maven profile.
