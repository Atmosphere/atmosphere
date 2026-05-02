# Atmosphere Embabel Adapter

`AgentRuntime` implementation backed by Embabel `AgentPlatform`. When this JAR is on the classpath, `@AiEndpoint` can run Embabel agents and stream their output to browser clients.

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
| `EmbabelAgentRuntime` | `AgentRuntime` SPI implementation (priority 100) |
| `EmbabelEmbeddingRuntime` | `EmbeddingRuntime` SPI wrapping Embabel `EmbeddingService` (priority 170) |
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

## Samples

- [Spring Boot AI Chat](../../samples/spring-boot-ai-chat/) -- swap to `atmosphere-embabel` dependency for Embabel support

## Full Documentation

See the [atmosphere-ai capability matrix](../ai/README.md#capability-matrix) and
<https://atmosphere.github.io/docs/reference/ai/> for the unified capability matrix
across all runtimes.

## Human-in-the-Loop (HITL) — not honored

Embabel delegates tool / function execution to its own `AgentPlatform` on a
separate runtime that Atmosphere does not control, so Atmosphere's
`@RequiresApproval` annotation and the unified
`ToolExecutionHelper.executeWithApproval` gate are **not** enforced on this
runtime. Tools annotated with `@RequiresApproval` run without the approval
parking/timeout flow that every other runtime (Built-in, LangChain4j, Spring AI,
Koog, ADK) provides.

If you need HITL gating on an Embabel-backed flow today, enforce it at the
Embabel `@AgentAction` level (inside the Embabel agent itself) or route the
sensitive tool through a different runtime. This gap is tracked for future work
as part of the Phase 0.5 follow-ups.

## Requirements

- Java 21+
- `atmosphere-ai` (transitive)
- Embabel Agent API 0.3.4+
- Kotlin runtime
- **Spring Boot 3.5** — Embabel framework does not yet support Spring Boot 4. Use `atmosphere-spring-boot3-starter` and the `-Pspring-boot3` Maven profile.
