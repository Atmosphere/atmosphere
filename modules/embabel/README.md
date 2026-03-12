# Atmosphere Embabel Adapter

`AiSupport` implementation backed by Embabel `AgentPlatform`. When this JAR is on the classpath, `@AiEndpoint` can run Embabel agents and stream their output to browser clients.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-embabel</artifactId>
    <version>4.0.13</version>
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
| `EmbabelAiSupport` | `AiSupport` SPI implementation (priority 100) |
| `AtmosphereEmbabelAutoConfiguration` | Spring Boot auto-configuration |

## Samples

- [Spring Boot Embabel Chat](../../samples/spring-boot-embabel-chat/)

## Full Documentation

See [docs/embabel.md](../../docs/embabel.md) for complete documentation.

## Requirements

- Java 21+
- `atmosphere-ai` (transitive)
- Embabel Agent API 0.3.4+
- Kotlin runtime
