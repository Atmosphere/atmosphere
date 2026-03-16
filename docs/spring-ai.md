# Spring AI Adapter

`AiSupport` implementation backed by Spring AI `ChatClient`. When this JAR is on the classpath, `@AiEndpoint` automatically uses Spring AI for streaming.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-ai</artifactId>
    <version>4.0.15</version>
</dependency>
```

## How It Works

Drop the dependency alongside `atmosphere-ai` and the framework auto-detects it via `ServiceLoader`:

```java
@AiEndpoint(path = "/ai/chat", systemPrompt = "You are a helpful assistant")
public class MyChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // uses Spring AI ChatClient automatically
    }
}
```

No code changes needed -- the `SpringAiSupport` implementation has priority 100, which takes precedence over the built-in client (priority 0).

## Direct Usage

For full control, use `SpringAiStreamingAdapter` directly:

```java
var session = StreamingSessions.start(resource);
springAiAdapter.stream(chatClient, prompt, session);
```

With advisors:

```java
springAiAdapter.stream(chatClient, prompt, session, myAdvisor);
```

With a customizer:

```java
springAiAdapter.stream(chatClient, prompt, session, spec -> {
    spec.system("Custom system prompt");
});
```

## Spring Boot Auto-Configuration

`AtmosphereSpringAiAutoConfiguration` provides:

- `SpringAiStreamingAdapter` bean
- `SpringAiSupport` bridge bean (connects Spring-managed `ChatClient` to the SPI)

The `ChatClient` bean must be configured separately via Spring AI's own starter.

## Samples

- [Spring Boot Spring AI Chat](../samples/spring-boot-spring-ai-chat/) -- complete example with Spring AI adapter

## See Also

- [AI Integration](ai.md) -- `AiSupport` SPI, `@AiEndpoint`, filters, routing
- [LangChain4j Adapter](langchain4j.md)
- [Google ADK Adapter](adk.md)
- [Embabel Adapter](embabel.md)
