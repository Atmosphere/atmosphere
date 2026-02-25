# atmosphere-adk — Google ADK Integration

Bridges [Google Agent Development Kit (ADK)](https://github.com/google/adk-java) agent
streams to Atmosphere's real-time broadcast infrastructure. ADK agents can now push
streaming tokens to WebSocket, SSE, and gRPC browser clients.

## Architecture

```
Browser ← WS/SSE/gRPC → Broadcaster ← AdkEventAdapter ← Flowable<Event> ← Runner ← LlmAgent
```

## Quick Start

### 1. Add Dependencies

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-adk</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
<dependency>
    <groupId>com.google.adk</groupId>
    <artifactId>google-adk</artifactId>
    <version>0.2.0</version>
</dependency>
```

### 2. Stream ADK Events to Browsers

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

// Run the agent and bridge events to an Atmosphere Broadcaster
Flowable<Event> events = runner.runAsync(userId, sessionId, Content.fromParts(Part.fromText(prompt)));
AdkEventAdapter.bridge(events, broadcaster);
// All connected WebSocket/SSE/gRPC clients on the broadcaster now receive streaming tokens
```

### 3. Let ADK Agents Broadcast to Browsers

Give your ADK agent a tool that can push messages to browser clients:

```java
// Create a broadcast tool for a specific topic
AdkBroadcastTool broadcastTool = new AdkBroadcastTool(broadcaster);

// Or create one that can target any topic
AdkBroadcastTool broadcastTool = new AdkBroadcastTool(broadcasterFactory);

LlmAgent agent = LlmAgent.builder()
    .name("notifier")
    .model("gemini-2.0-flash")
    .instruction("Use the broadcast tool to send updates to users.")
    .tools(broadcastTool)
    .build();
```

### 4. Use the AiStreamingAdapter SPI

If you prefer the atmosphere-ai adapter pattern:

```java
AdkStreamingAdapter adapter = new AdkStreamingAdapter();
StreamingSession session = StreamingSessions.start(resource);
adapter.stream(new AdkRequest(runner, userId, sessionId, "Tell me about Java 25"), session);
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `AdkEventAdapter` | Subscribes to `Flowable<Event>` and forwards tokens to `StreamingSession` |
| `AdkBroadcastTool` | ADK `BaseTool` that broadcasts messages via Atmosphere `Broadcaster` |
| `AdkStreamingAdapter` | `AiStreamingAdapter` SPI impl bridging ADK Runner to StreamingSession |

## Reuse from atmosphere-ai

This module depends on `atmosphere-ai` and reuses its streaming infrastructure:

- **`StreamingSession`** — the SPI interface for token delivery
- **`BroadcasterStreamingSession`** — topic-based streaming (no `AtmosphereResource` needed)
- **`StreamingSessions`** — factory for creating sessions

No streaming code is duplicated. The ADK module adds only the ADK-specific bridge logic.

## Wire Protocol

Tokens delivered to browsers use the same JSON format as all atmosphere-ai adapters:

```json
{"type":"token","data":"Hello","sessionId":"abc-123","seq":1}
{"type":"token","data":" world","sessionId":"abc-123","seq":2}
{"type":"complete","sessionId":"abc-123","seq":3}
```
