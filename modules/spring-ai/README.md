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
| `AtmosphereSpringAiAutoConfiguration` | Spring Boot auto-configuration |

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
