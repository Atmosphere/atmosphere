# Atmosphere Embabel Adapter

`AgentRuntime` implementation backed by Embabel `AgentPlatform`. When this JAR is on the classpath, `@AiEndpoint` can run Embabel agents and stream their output to browser clients.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-embabel</artifactId>
    <version>4.0.28</version>
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
| `AtmosphereEmbabelAutoConfiguration` | Spring Boot auto-configuration |

## Samples

- [Spring Boot AI Chat](../../samples/spring-boot-ai-chat/) -- swap to `atmosphere-embabel` dependency for Embabel support

## Full Documentation

See [docs/agent-runtimes.md](../../docs/agent-runtimes.md) for the unified capability matrix across all runtimes.

## Requirements

- Java 21+
- `atmosphere-ai` (transitive)
- Embabel Agent API 0.3.4+
- Kotlin runtime
- **Spring Boot 3.5** — Embabel framework does not yet support Spring Boot 4. Use `atmosphere-spring-boot3-starter` and the `-Pspring-boot3` Maven profile.
