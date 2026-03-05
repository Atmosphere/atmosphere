---
title: "Google ADK"
description: "AiSupport backed by Google ADK Runner"
---

# Google ADK Adapter

Bridges [Google Agent Development Kit (ADK)](https://github.com/google/adk-java) agent streams to Atmosphere's real-time broadcast infrastructure. ADK agents can push streaming tokens to WebSocket, SSE, and gRPC browser clients.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-adk</artifactId>
    <version>4.0.8-SNAPSHOT</version>
</dependency>
```

## Architecture

```
Browser <- WS/SSE/gRPC -> Broadcaster <- AdkEventAdapter <- Flowable<Event> <- Runner <- LlmAgent
```

## Quick Start

### AiSupport SPI (auto-detected)

```java
@AiEndpoint(path = "/ai/chat", systemPrompt = "You are a helpful assistant")
public class MyChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // uses ADK automatically when atmosphere-adk is on classpath
    }
}
```

### Direct Adapter Usage

```java
// Build an ADK agent
LlmAgent agent = LlmAgent.builder()
    .name("assistant")
    .model("gemini-2.0-flash")
    .instruction("You are a helpful assistant.")
    .build();

Runner runner = Runner.builder()
    .agent(agent)
    .appName("my-app")
    .build();

// Stream to an AtmosphereResource
var session = StreamingSessions.start(resource);
adkAdapter.stream(new AdkRequest(runner, userId, sessionId, prompt), session);
```

### Low-Level Event Bridge

Bridge ADK events directly to a Broadcaster:

```java
Flowable<Event> events = runner.runAsync(userId, sessionId,
    Content.fromParts(Part.fromText(prompt)));
AdkEventAdapter.bridge(events, broadcaster);
```

### ADK Broadcast Tool

Give your ADK agent a tool that can push messages to browser clients:

```java
AdkBroadcastTool broadcastTool = new AdkBroadcastTool(broadcaster);

LlmAgent agent = LlmAgent.builder()
    .name("notifier")
    .model("gemini-2.0-flash")
    .instruction("Use the broadcast tool to send updates to users.")
    .tools(broadcastTool)
    .build();
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `AdkEventAdapter` | Subscribes to `Flowable<Event>` and forwards tokens to `StreamingSession` |
| `AdkBroadcastTool` | ADK `BaseTool` that broadcasts messages via Atmosphere `Broadcaster` |
| `AdkStreamingAdapter` | `AiStreamingAdapter` SPI impl bridging ADK Runner to StreamingSession |
| `AdkAiSupport` | `AiSupport` SPI implementation (priority 100) |

## Samples

- [Spring Boot ADK Chat](../samples/spring-boot-adk-chat/) -- complete ADK chat example
- [Spring Boot ADK Tools](../samples/spring-boot-adk-tools/) -- ADK tools with broadcasting

## See Also

- [AI Integration](ai.md) -- `AiSupport` SPI, `@AiEndpoint`, filters, routing
- [Spring AI Adapter](spring-ai.md)
- [LangChain4j Adapter](langchain4j.md)
- [Embabel Adapter](embabel.md)
- [Module README](../modules/adk/README.md)
