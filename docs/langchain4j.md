# LangChain4j Adapter

`AiSupport` implementation backed by LangChain4j `StreamingChatLanguageModel`. When this JAR is on the classpath, `@AiEndpoint` automatically uses LangChain4j for streaming.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-langchain4j</artifactId>
    <version>4.0.13</version>
</dependency>
```

## How It Works

Drop the dependency alongside `atmosphere-ai` and the framework auto-detects it via `ServiceLoader`:

```java
@AiEndpoint(path = "/ai/chat", systemPrompt = "You are a helpful assistant")
public class MyChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // uses LangChain4j automatically
    }
}
```

The `LangChain4jAiSupport` implementation has priority 100, which takes precedence over the built-in client (priority 0).

## Direct Usage

For full control, use `LangChain4jStreamingAdapter` directly:

```java
var session = StreamingSessions.start(resource);
model.chat(ChatMessage.userMessage(prompt),
    new AtmosphereStreamingResponseHandler(session));
```

### AtmosphereStreamingResponseHandler

Bridges LangChain4j's `StreamingChatResponseHandler` to Atmosphere's `StreamingSession`:

| LangChain4j Callback | StreamingSession Action |
|----------------------|------------------------|
| `onPartialResponse(text)` | `session.send(text)` |
| `onCompleteResponse(response)` | `session.complete()` |
| `onError(throwable)` | `session.error(message)` |

## Spring Boot Auto-Configuration

`AtmosphereLangChain4jAutoConfiguration` bridges a Spring-managed `StreamingChatLanguageModel` bean to the `LangChain4jAiSupport` SPI automatically.

## Samples

- [Spring Boot LangChain4j Chat](../samples/spring-boot-langchain4j-chat/) -- complete example with LangChain4j

## See Also

- [AI Integration](ai.md) -- `AiSupport` SPI, `@AiEndpoint`, filters, routing
- [Spring AI Adapter](spring-ai.md)
- [Google ADK Adapter](adk.md)
- [Embabel Adapter](embabel.md)
