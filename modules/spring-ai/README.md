# Atmosphere Spring AI Adapter

`AgentRuntime` implementation backed by Spring AI `ChatClient`. When this JAR is on the classpath, `@AiEndpoint` automatically uses Spring AI for LLM streaming.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-ai</artifactId>
    <version>${project.version}</version>
</dependency>
```

## Quick Start

```java
@AiEndpoint(path = "/ai/chat", systemPrompt = "You are a helpful assistant")
public class MyChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // uses Spring AI ChatClient automatically
    }
}
```

For direct usage:

```java
var session = StreamingSessions.start(resource);
springAiAdapter.stream(chatClient, prompt, session);
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `SpringAiStreamingAdapter` | Bridges Spring AI `ChatClient` to `StreamingSession` |
| `SpringAiAgentRuntime` | `AgentRuntime` SPI implementation (priority 100) |
| `SpringAiEmbeddingRuntime` | `EmbeddingRuntime` SPI wrapping Spring AI `EmbeddingModel` (priority 200) |
| `SpringAiToolBridge` | Translates Atmosphere `ToolDefinition` to Spring AI `ToolCallback` with HITL approval gating |
| `SpringAiAdvisors` | Per-request bridge for the Spring AI `Advisor` chain — RAG / memory / guardrails / observability |
| `AtmosphereSpringAiAutoConfiguration` | Spring Boot auto-configuration |

## Per-Request Advisors (`SpringAiAdvisors`)

Spring AI's advisor chain (`QuestionAnswerAdvisor`, `MessageChatMemoryAdvisor`,
`SafeGuardAdvisor`, `SimpleLoggerAdvisor`, …) is the framework's main extension
point. `ChatClient.Builder.defaultAdvisors(...)` covers the static case, but
some advisors are scoped to a single request (per-user audit, scoped guardrails,
session-bound memory) and must not mutate the shared builder. `SpringAiAdvisors`
is the per-request slot:

```java
var safeGuard = SafeGuardAdvisor.builder()
        .sensitiveWords(List.of("badword"))
        .failureResponse("I cannot answer that.")
        .build();

var ctx = SpringAiAdvisors.attach(baseContext, safeGuard, new SimpleLoggerAdvisor());
runtime.execute(ctx, session);
```

`SpringAiAgentRuntime.execute` reads the list via `SpringAiAdvisors.from(context)`
and appends it to the prompt spec via `promptSpec.advisors(perRequestAdvisors)`
before `.stream()`. The list rides on `AgentExecutionContext.metadata()` under
`SpringAiAdvisors.METADATA_KEY` so the `modules/ai` core stays free of any
`spring-ai-client-chat` dependency.

## Samples

- [Spring Boot AI Chat](../../samples/spring-boot-ai-chat/) -- swap to `atmosphere-spring-ai` dependency for Spring AI support

## Full Documentation

See the [atmosphere-ai capability matrix](../ai/README.md#capability-matrix) and
<https://atmosphere.github.io/docs/reference/ai/> for the unified capability matrix
across all runtimes.

## Requirements

- Java 21+
- `atmosphere-ai` (transitive)
- Spring AI 2.0.0-M2+
