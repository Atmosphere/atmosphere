# Embabel Adapter

`AiSupport` implementation backed by [Embabel](https://github.com/embabel/embabel-agent) `AgentPlatform`. When this JAR is on the classpath, `@AiEndpoint` can run Embabel agents and stream their output to browser clients.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-embabel</artifactId>
    <version>4.0.8-SNAPSHOT</version>
</dependency>
```

## Quick Start

### AiSupport SPI (auto-detected)

```java
@AiEndpoint(path = "/ai/chat", systemPrompt = "You are a helpful assistant")
public class MyChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // uses Embabel when atmosphere-embabel is on classpath
    }
}
```

The agent name can be specified in `AiRequest.hints()["agentName"]`.

### Direct Adapter Usage

```kotlin
val session = StreamingSessions.start(resource)
embabelAdapter.stream(AgentRequest("assistant") { channel ->
    agentPlatform.run(prompt, channel)
}, session)
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `EmbabelStreamingAdapter` | `AiStreamingAdapter` bridging Embabel agents to StreamingSession |
| `AtmosphereOutputChannel` | Routes Embabel `OutputChannelEvent` to `StreamingSession` |
| `EmbabelAiSupport` | `AiSupport` SPI implementation (priority 100) |
| `AgentRequest` | Wraps agent name and runner function |

### Event Mapping

| Embabel Event | StreamingSession Action |
|---------------|------------------------|
| `MessageOutputChannelEvent` | `session.send(content)` |
| `ContentOutputChannelEvent` | `session.send(content)` |
| `ProgressOutputChannelEvent` | `session.progress(message)` |
| `LoggingOutputChannelEvent` (INFO+) | `session.progress(message)` |

## Spring Boot Auto-Configuration

`AtmosphereEmbabelAutoConfiguration` bridges a Spring-managed `AgentPlatform` bean to the SPI automatically.

## Samples

- [Spring Boot Embabel Chat](../samples/spring-boot-embabel-chat/) -- Embabel agent chat example

## See Also

- [AI Integration](ai.md) -- `AiSupport` SPI, `@AiEndpoint`, filters, routing
- [Spring AI Adapter](spring-ai.md)
- [LangChain4j Adapter](langchain4j.md)
- [Google ADK Adapter](adk.md)
