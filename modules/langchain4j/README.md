# Atmosphere LangChain4j Adapter

`AgentRuntime` implementation backed by LangChain4j `StreamingChatLanguageModel`. When this JAR is on the classpath, `@AiEndpoint` automatically uses LangChain4j for LLM streaming.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-langchain4j</artifactId>
    <version>${project.version}</version>
</dependency>
```

## Quick Start

```java
@AiEndpoint(path = "/ai/chat", systemPrompt = "You are a helpful assistant")
public class MyChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // uses LangChain4j automatically
    }
}
```

For direct usage:

```java
var session = StreamingSessions.start(resource);
model.chat(ChatMessage.userMessage(prompt),
    new AtmosphereStreamingResponseHandler(session));
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `LangChain4jStreamingAdapter` | Bridges LangChain4j models to `StreamingSession` |
| `ToolAwareStreamingResponseHandler` | `StreamingChatResponseHandler` with multi-round tool calling + lifecycle events |
| `LangChain4jAgentRuntime` | `AgentRuntime` SPI implementation (priority 100) |
| `LangChain4jEmbeddingRuntime` | `EmbeddingRuntime` SPI wrapping LC4j `EmbeddingModel` (priority 190) |
| `LangChain4jToolBridge` | Translates Atmosphere `ToolDefinition` to LC4j `ToolSpecification` with HITL approval gating |
| `AtmosphereLangChain4jAutoConfiguration` | Spring Boot auto-configuration |

## Samples

- [Spring Boot AI Chat](../../samples/spring-boot-ai-chat/) -- swap to `atmosphere-langchain4j` dependency for LangChain4j support

## Full Documentation

See the [atmosphere-ai capability matrix](../ai/README.md#capability-matrix) and
<https://atmosphere.github.io/docs/reference/ai/> for the unified capability matrix
across all runtimes.

## Requirements

- Java 21+
- `atmosphere-ai` (transitive)
- LangChain4j 1.12.2+
