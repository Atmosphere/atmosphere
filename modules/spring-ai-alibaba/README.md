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
is **synchronous** as of v1.1.2.0; there is no native `Flux<...>` /
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

## Streaming Bridge

| Spring AI Alibaba return | Atmosphere session call |
|---|---|
| `AssistantMessage.getText()` (non-empty) | `session.send(text)` |
| `call(...)` returns normally              | `session.complete()` |
| `GraphRunnerException`                    | `session.error(t)` + propagate as `IllegalStateException` |

## Capabilities NOT declared (and why)

- `TOKEN_USAGE` — `ReactAgent.call(...)` returns `AssistantMessage`,
  which has no surface for the `ChatResponse` usage metadata. The
  agent framework's `CompiledGraph` captures usage internally but does
  not expose it through the `call(...)` API as of v1.1.2.0; reading
  the graph's run state is brittle across versions.
- `TOOL_CALLING` / `TOOL_APPROVAL` — Spring AI Alibaba's tool surface
  bridges Spring AI `FunctionCallback`s. A dedicated
  `SpringAiAlibabaToolBridge` (one that satisfies the cross-runtime
  `TOOL_APPROVAL` invariant by routing through
  `ToolExecutionHelper.executeWithApproval`) is the prerequisite to
  declaring these honestly. This first cut leaves them off.
- `VISION` / `AUDIO` / `MULTI_MODAL` — Atmosphere's `Content.Image` /
  `Content.Audio` parts need mapping to Spring AI's `Media` types on
  the `UserMessage`. Same approach as
  `LangChain4jAgentRuntime.doExecuteWithHandle`, scaled down for
  buffered dispatch.
- `PROMPT_CACHING` — would need threading `CacheHint` into Spring AI
  Alibaba's per-request `RunnableConfig`; the framework does not
  expose a portable cache-control primitive at the agent level today.
- `PER_REQUEST_RETRY` — `AbstractAgentRuntime.executeWithOuterRetry`
  is inherited but the per-request retry policy is not yet threaded
  into the Spring AI Alibaba dispatch path.

## Requirements

- **Spring Boot 3.5 only.** Spring AI Alibaba 1.1.2.0 transitively
  pulls Spring AI 1.1.2 which references Spring Boot 3.x autoconfigure
  classes (e.g. `RestClientAutoConfiguration`) that don't exist in
  Spring Boot 4 — same situation as `atmosphere-embabel`. The CLI
  overlay must be applied with `-Pspring-boot3` and the parent
  Atmosphere profile pins the SB 3.5 starter accordingly. A Spring
  Boot 4-compatible release of upstream Spring AI Alibaba would lift
  this restriction; until then the SB4 profile is unsupported on this
  adapter.
- Java 21+
- `atmosphere-ai` (transitive)
- Spring AI Alibaba 1.1.2.0+
- Spring AI 1.1.2 (pinned via `spring-ai-alibaba-spring-ai.version` to
  avoid clashing with Atmosphere's broader Spring AI 2.0.0-Mx baseline
  used in `atmosphere-spring-ai`)
