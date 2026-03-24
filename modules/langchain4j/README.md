# Atmosphere LangChain4j Adapter

`AiSupport` implementation backed by LangChain4j `StreamingChatLanguageModel`. When this JAR is on the classpath, `@AiEndpoint` automatically uses LangChain4j for LLM streaming.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-langchain4j</artifactId>
    <version>4.0.23</version>
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
| `AtmosphereStreamingResponseHandler` | `StreamingChatResponseHandler` that forwards streaming texts to `StreamingSession` |
| `LangChain4jAiSupport` | `AiSupport` SPI implementation (priority 100) |
| `AtmosphereLangChain4jAutoConfiguration` | Spring Boot auto-configuration |

## Samples

- [Spring Boot AI Chat](../../samples/spring-boot-ai-chat/) -- swap to `atmosphere-langchain4j` dependency for LangChain4j support

## Full Documentation

See [docs/langchain4j.md](../../docs/langchain4j.md) for complete documentation.

## Requirements

- Java 21+
- `atmosphere-ai` (transitive)
- LangChain4j 1.0.0-beta3+
