# Atmosphere Spring AI Alibaba Adapter

`AgentRuntime` implementation backed by [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba)
(Alibaba Cloud AI) `ReactAgent`. Spring AI Alibaba extends Spring AI with
multi-agent orchestration patterns (`SequentialAgent`, `ParallelAgent`,
`RoutingAgent`, `LoopAgent`) and a graph runtime; this adapter exposes
them through `@AiEndpoint`.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-ai-alibaba</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-agent-framework</artifactId>
    <version>${spring-ai-alibaba.version}</version>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
    <version>${spring-ai-alibaba-spring-ai.version}</version>
</dependency>
```

Or use the CLI to scaffold a working sample (Spring Boot 3.5 only — see
[Requirements](#requirements)):

```bash
atmosphere new my-app --template ai-chat --runtime spring-ai-alibaba --force
cd my-app && ./mvnw -Pspring-boot3 spring-boot:run
```

## Buffered streaming — important

Spring AI Alibaba's `ReactAgent.call(List<Message>) → AssistantMessage`
is **synchronous** as of v1.1.2.2; there is no native `Flux<...>` /
streaming agent method. This adapter wraps `call(...)` and delivers the
full reply as a single `session.send(text)` chunk followed by
`session.complete()`. The Atmosphere transport still frames that chunk
as a streamed message to the client, so the UI sees a streaming
response — but **there are no incremental token deltas from the LLM**.

If token-by-token streaming matters, drive Spring AI's
`StreamingChatModel` directly via `atmosphere-spring-ai`. You will lose
Spring AI Alibaba's multi-agent / graph orchestration patterns; that's
the trade.

## Quick Start

The Spring Boot autoconfig auto-builds a `ReactAgent` whenever a Spring
AI `ChatModel` bean is on the context (typically supplied by
`spring-ai-starter-model-openai` from the standard `llm.*` properties):

```yaml
llm:
  api-key: ${OPENAI_API_KEY}
  base-url: https://api.openai.com/v1
  model: gpt-4o-mini
```

Override the system prompt with
`atmosphere.spring-ai-alibaba.system-prompt` (falls back to
`llm.system-prompt`).

For full control, declare your own `ReactAgent` `@Bean` — the autoconfig
detects it via `@ConditionalOnMissingBean` and skips the default:

```java
@Bean
public ReactAgent reactAgent(ChatModel chatModel) throws Exception {
    return ReactAgent.builder()
            .name("research-agent")
            .model(chatModel)
            .systemPrompt("You are a precise research assistant.")
            .saver(new MemorySaver())
            // .tools(...)         // Spring AI FunctionCallbacks
            // .subAgents(...)     // multi-agent composition
            .build();
}
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `SpringAiAlibabaAgentRuntime` | `AgentRuntime` SPI implementation (priority 100) |
| `SpringAiAlibabaRunnableConfig` | Per-request bridge for overriding the `RunnableConfig` passed to `agent.call(messages, config)` (thread continuation, checkpoint resume, stream mode, metadata, store) |
| `AtmosphereSpringAiAlibabaAutoConfiguration` | Spring Boot auto-configuration |

## Capabilities

See the [capability matrix](../ai/README.md#capability-matrix) for the
authoritative cross-runtime view. Honest declared set:

- **Text streaming (buffered)** — see warning above.
- **System prompt** — threaded two ways defensively:
  `ReactAgent.setSystemPrompt(context.systemPrompt())` is called per
  request, and `assembleMessages` also injects a Spring AI
  `SystemMessage` into the `List<Message>` dispatched to `call(...)`.
- **Structured output** — surfaced via Atmosphere's pipeline-level
  `StructuredOutputCapturingSession` (auto-enabled for every
  `SYSTEM_PROMPT`-capable runtime). A single buffered chunk is still a
  complete final frame the wrapper can parse.
- **Conversation memory** — `assembleMessages` threads
  `context.history()` into the `List<Message>` `ReactAgent` receives.
- **Per-request retry** — inherited via
  `AbstractAgentRuntime.executeWithOuterRetry`. The base class wraps
  `doExecute(...)` so any `RuntimeException` thrown before the first
  `session.send(...)` is retried up to `policy.maxRetries()`.
- **Tool calling / approval** — `SpringAiAlibabaToolBridge` bridges Atmosphere
  `ToolDefinition`s into Spring AI Alibaba's tool surface and routes every
  invocation through `ToolExecutionHelper.executeWithApproval`, so
  `@RequiresApproval` fires the same cross-runtime approval/parking flow.
- **Vision / audio / multi-modal** — `attachMediaToTrailingUserMessage` maps
  Atmosphere `Content.Image` / `Content.Audio` parts onto the trailing user
  message before dispatch.
- **Token usage** — `ReactAgent.call(...)` returns a usage-less
  `AssistantMessage`, so a per-dispatch `UsageCollector` captures token counts
  and reports them via `StreamingSession.usage(TokenUsage)`.
- **Planning** — the harness PLANNING primitive delegates to Alibaba's native
  `TodoListInterceptor`: the framework's own model-facing `write_todos` tool
  (plus its todo-usage prompt guidance) maintains the plan, and
  `SpringAiAlibabaPlanBridge` bridges it back to Atmosphere — every todo write
  persists through `AgentPlanStore` (bounds enforced; over-limit plans come
  back to the model as a clear tool-error reply) and emits
  `AiEvent.PlanUpdate` for the console. Because Alibaba stores todos in
  per-invocation graph state and this adapter rebuilds the agent per request,
  the previous turn's live plan re-hydrates through the system prompt at
  each dispatch. Alibaba's `TodoStatus` (`PENDING` / `IN_PROGRESS` /
  `COMPLETED`) maps 1:1 onto the same-named `PlanStatus` values; the
  framework has no `ABANDONED` state or plan-goal field, so the stored goal
  is carried forward across writes. Native surface applies under
  `atmosphere.ai.planning=AUTO|NATIVE`; `BUILTIN` keeps the portable
  `write_todos` floor instead (never both).
- **Cancellation, budget enforcement, confidence scores, passivation** —
  provided by the shared `AbstractAgentRuntime` base and the AI pipeline.

## Per-Request RunnableConfig (`SpringAiAlibabaRunnableConfig`)

By default the runtime calls `agent.call(messages)` (no `RunnableConfig`).
Alibaba's `RunnableConfig` is the natural per-invocation handle for the
`ReactAgent` graph: it carries `threadId` (for memory thread continuation
across calls against a checkpointed graph), `checkPointId` (resume from a
specific checkpoint), `streamMode` (`VALUES` / `UPDATES` / `MESSAGES`),
arbitrary `metadata` + `context` maps, and a `Store` for cross-thread
state. Attach a per-request `RunnableConfig` via
`SpringAiAlibabaRunnableConfig.attach`:

```java
var ctx = SpringAiAlibabaRunnableConfig.attach(baseContext,
        RunnableConfig.builder()
                .threadId("user-42-session-7")
                .streamMode(CompiledGraph.StreamMode.VALUES)
                .build());
runtime.execute(ctx, session);
```

When attached, the runtime dispatches `agent.call(messages, config)`;
when absent, it falls back to the no-arg overload — preserving prior
behavior.

## Streaming Bridge

| Spring AI Alibaba return | Atmosphere session call |
|---|---|
| `AssistantMessage.getText()` (non-empty) | `session.send(text)` |
| `call(...)` returns normally              | `session.complete()` |
| `GraphRunnerException`                    | `session.error(t)` + propagate as `IllegalStateException` |

## Capabilities NOT declared (and why)

- `PROMPT_CACHING` — would need threading `CacheHint` into Spring AI
  Alibaba's per-request `RunnableConfig`; the framework does not
  expose a portable cache-control primitive at the agent level today.
- `VIRTUAL_FILESYSTEM` — Alibaba ships a `FilesystemBackend` SPI
  (`read`/`write`/`edit`/`lsInfo`/`globInfo`/`grepRaw`) that looks like the
  perfect seam for exposing Atmosphere's bounded `AgentFileSystem`, but in
  `spring-ai-alibaba-agent-framework` 1.1.2.3 no model-facing tool consumes
  it: `FilesystemInterceptor.Builder` carries a `backend` field with **no
  setter**, its tool callbacks (`ReadFileTool`, `WriteFileTool`, ...) operate
  on the raw host disk via `java.nio` directly, and `FileSystemTools` only
  takes `rootDir`/`virtualMode`/`maxFileSizeMb` (real-disk sandbox — routing
  the model around Atmosphere's file-count/total-bytes bounds would violate
  the harness FILESYSTEM contract). The only `backend(...)` seam in the jar
  is `LargeResultEvictionInterceptor` — tool-result eviction, not a file
  surface. Until upstream wires the SPI into its tools, the portable
  built-in file-tool floor (`ls`/`read_file`/`write_file`/`edit_file`/
  `glob`/`grep`) serves this runtime through the standard tool bridge.

`TOOL_CALL_DELTA`, `AGENT_ORCHESTRATION`, and `MODEL_ENUMERATION` are
likewise not declared — see the
[capability matrix](../ai/README.md#capability-matrix) for the authoritative
cross-runtime view.

## Requirements

- **Spring Boot 3.5 only.** Spring AI Alibaba 1.1.2.3 transitively
  pulls Spring AI 1.1.8 which references Spring Boot 3.x autoconfigure
  classes (e.g. `RestClientAutoConfiguration`) that don't exist in
  Spring Boot 4 — same situation as `atmosphere-embabel`. The CLI
  overlay must be applied with `-Pspring-boot3` and the parent
  Atmosphere profile pins the SB 3.5 starter accordingly. A Spring
  Boot 4-compatible release of upstream Spring AI Alibaba would lift
  this restriction; until then the SB4 profile is unsupported on this
  adapter.
- Java 21+
- `atmosphere-ai` (transitive)
- Spring AI Alibaba 1.1.2.3+
- Spring AI 1.1.8 (pinned via `spring-ai-alibaba-spring-ai.version` to
  avoid clashing with Atmosphere's broader Spring AI 2.0.0 baseline
  used in `atmosphere-spring-ai`)
