---
title: "AI / LLM"
description: "@AiEndpoint, StreamingSession, AiSupport SPI, tool calling, filters"
---

# AI / LLM Integration

AI/LLM streaming module for Atmosphere. Provides `@AiEndpoint`, `@Prompt`, `StreamingSession`, the `AiSupport` SPI for auto-detected AI framework adapters, and a built-in `OpenAiCompatibleClient` that works with Gemini, OpenAI, Ollama, and any OpenAI-compatible API.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai</artifactId>
    <version>LATEST</version> <!-- check Maven Central for latest -->
</dependency>
```

## Architecture

Atmosphere has two pluggable SPI layers. `AsyncSupport` adapts web containers -- Jetty, Tomcat, Undertow. `AiSupport` adapts AI frameworks -- Spring AI, LangChain4j, Google ADK, Embabel. Same design pattern, same discovery mechanism:

| Concern | Transport layer | AI layer |
|---------|----------------|----------|
| SPI interface | `AsyncSupport` | `AiSupport` |
| What it adapts | Web containers (Jetty, Tomcat, Undertow) | AI frameworks (Spring AI, LangChain4j, ADK, Embabel) |
| Discovery | Classpath scanning | `ServiceLoader` |
| Resolution | Best available container | Highest `priority()` among `isAvailable()` |
| Initialization | `init(ServletConfig)` | `configure(LlmSettings)` |
| Core method | `service(req, res)` | `stream(AiRequest, StreamingSession)` |
| Fallback | `BlockingIOCometSupport` | `BuiltInAiSupport` (OpenAI-compatible) |

## Quick Start -- @AiEndpoint

```java
@AiEndpoint(path = "/ai/chat",
            systemPrompt = "You are a helpful assistant",
            conversationMemory = true)
public class MyChatBot {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // auto-detects Spring AI, LangChain4j, ADK, Embabel, or built-in
    }
}
```

The `@AiEndpoint` annotation replaces the boilerplate of `@ManagedService` + `@Ready` + `@Disconnect` + `@Message` for AI streaming use cases. The `@Prompt` method runs on a virtual thread.

`session.stream(message)` auto-detects the best available `AiSupport` implementation via `ServiceLoader` -- drop an adapter JAR on the classpath and it just works.

## AiSupport SPI

| Classpath JAR | Auto-detected `AiSupport` | Priority |
|---------------|--------------------------|----------|
| `atmosphere-ai` (default) | Built-in `OpenAiCompatibleClient` (Gemini, OpenAI, Ollama) | 0 |
| `atmosphere-spring-ai` | Spring AI `ChatClient` | 100 |
| `atmosphere-langchain4j` | LangChain4j `StreamingChatLanguageModel` | 100 |
| `atmosphere-adk` | Google ADK `Runner` | 100 |
| `atmosphere-embabel` | Embabel `AgentPlatform` | 100 |

## Conversation Memory

Enable multi-turn conversations with one annotation attribute:

```java
@AiEndpoint(path = "/ai/chat",
            systemPrompt = "You are a helpful assistant",
            conversationMemory = true,
            maxHistoryMessages = 20)
public class MyChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

When `conversationMemory = true`, the framework:

1. Captures each user message and the streamed assistant response (via `MemoryCapturingSession`)
2. Stores them as conversation turns per `AtmosphereResource`
3. Injects the full history into every subsequent `AiRequest`
4. Clears the history when the resource disconnects

The default implementation is `InMemoryConversationMemory` (capped at `maxHistoryMessages`, default 20). For external storage, implement the `AiConversationMemory` SPI:

```java
public interface AiConversationMemory {
    List<ChatMessage> getHistory(String conversationId);
    void addMessage(String conversationId, ChatMessage message);
    void clear(String conversationId);
    int maxMessages();
}
```

## @AiTool -- Framework-Agnostic Tool Calling

Declare tools with `@AiTool` and they work with any AI backend -- Spring AI, LangChain4j, Google ADK. No framework-specific annotations needed.

### Defining Tools

```java
public class AssistantTools {

    @AiTool(name = "get_weather",
            description = "Returns a weather report for a city")
    public String getWeather(
            @Param(value = "city", description = "City name to get weather for")
            String city) {
        return weatherService.lookup(city);
    }

    @AiTool(name = "convert_temperature",
            description = "Converts between Celsius and Fahrenheit")
    public String convertTemperature(
            @Param(value = "value", description = "Temperature value") double value,
            @Param(value = "from_unit", description = "'C' or 'F'") String fromUnit) {
        return "C".equalsIgnoreCase(fromUnit)
                ? String.format("%.1f°C = %.1f°F", value, value * 9.0 / 5.0 + 32)
                : String.format("%.1f°F = %.1f°C", value, (value - 32) * 5.0 / 9.0);
    }
}
```

### Wiring Tools to an Endpoint

```java
@AiEndpoint(path = "/ai/chat",
            systemPrompt = "You are a helpful assistant",
            conversationMemory = true,
            tools = AssistantTools.class)
public class MyChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // tools are automatically available to the LLM
    }
}
```

### How It Works

```
@AiTool methods
    ↓ scan at startup
DefaultToolRegistry (global)
    ↓ selected per-endpoint via tools = {...}
AiRequest.withTools(tools)
    ↓ bridged to backend-native format
LangChain4jToolBridge / SpringAiToolBridge / AdkToolBridge
    ↓ LLM decides to call a tool
ToolExecutor.execute(args) → result fed back to LLM
    ↓
StreamingSession → WebSocket → browser
```

The tool bridge layer converts `@AiTool` to the native format at runtime:

| Backend | Bridge Class | Native Format |
|---------|-------------|---------------|
| LangChain4j | `LangChain4jToolBridge` | `ToolSpecification` |
| Spring AI | `SpringAiToolBridge` | `ToolCallback` |
| Google ADK | `AdkToolBridge` | `BaseTool` |

### @AiTool vs Native Annotations

| | `@AiTool` (Atmosphere) | `@Tool` (LangChain4j) | `FunctionCallback` (Spring AI) |
|--|------------------------|----------------------|-------------------------------|
| Portable | Any backend | LangChain4j only | Spring AI only |
| Parameter metadata | `@Param` annotation | `@P` annotation | JSON Schema |
| Registration | `ToolRegistry` (global) | Per-service | Per-ChatClient |

To swap the AI backend, change only the Maven dependency -- no tool code changes:

```xml
<!-- Use LangChain4j -->
<artifactId>atmosphere-langchain4j</artifactId>

<!-- Or Spring AI -->
<artifactId>atmosphere-spring-ai</artifactId>

<!-- Or Google ADK -->
<artifactId>atmosphere-adk</artifactId>
```

See the [spring-boot-ai-tools](../samples/spring-boot-ai-tools/) sample.

## AiInterceptor

Cross-cutting concerns (RAG, guardrails, logging) go through `AiInterceptor`, not subclassing:

```java
@AiEndpoint(path = "/ai/chat", interceptors = {RagInterceptor.class, LoggingInterceptor.class})
public class MyChat { ... }

public class RagInterceptor implements AiInterceptor {
    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        String context = vectorStore.search(request.message());
        return request.withMessage(context + "\n\n" + request.message());
    }
}
```

## Filters, Routing, and Middleware

The AI module includes filters and middleware that sit between the `@Prompt` method and the LLM:

| Class | What it does |
|-------|-------------|
| `PiiRedactionFilter` | Buffers messages to sentence boundaries, redacts email/phone/SSN/CC |
| `ContentSafetyFilter` | Pluggable `SafetyChecker` SPI -- block, redact, or pass |
| `CostMeteringFilter` | Per-session/broadcaster message counting with budget enforcement |
| `RoutingLlmClient` | Route by content, model, cost, or latency rules |
| `FanOutStreamingSession` | Concurrent N-model streaming: AllResponses, FirstComplete, FastestTokens |
| `TokenBudgetManager` | Per-user/org budgets with graceful degradation |
| `AiResponseCacheInspector` | Cache control for AI messages in `BroadcasterCache` |
| `AiResponseCacheListener` | Aggregate per-session events instead of per-message noise |

### Cost and Latency Routing

`RoutingLlmClient` supports cost-based and latency-based routing rules:

```java
var router = RoutingLlmClient.builder(defaultClient, "gemini-2.5-flash")
        .route(RoutingRule.costBased(5.0, List.of(
                new ModelOption(openaiClient, "gpt-4o", 0.01, 200, 10),
                new ModelOption(geminiClient, "gemini-flash", 0.001, 50, 5))))
        .route(RoutingRule.latencyBased(100, List.of(
                new ModelOption(ollamaClient, "llama3.2", 0.0, 30, 3),
                new ModelOption(openaiClient, "gpt-4o-mini", 0.005, 80, 7))))
        .build();
```

## Direct Adapter Usage

You can bypass `@AiEndpoint` and use adapters directly:

**Spring AI:**
```java
var session = StreamingSessions.start(resource);
springAiAdapter.stream(chatClient, prompt, session);
```

**LangChain4j:**
```java
var session = StreamingSessions.start(resource);
model.chat(ChatMessage.userMessage(prompt),
    new AtmosphereStreamingResponseHandler(session));
```

**Google ADK:**
```java
var session = StreamingSessions.start(resource);
adkAdapter.stream(new AdkRequest(runner, userId, sessionId, prompt), session);
```

**Embabel:**
```kotlin
val session = StreamingSessions.start(resource)
embabelAdapter.stream(AgentRequest("assistant") { channel ->
    agentPlatform.run(prompt, channel)
}, session)
```

## Browser -- React

```tsx
import { useStreaming } from 'atmosphere.js/react';

function AiChat() {
  const { fullText, isStreaming, stats, routing, send } = useStreaming({
    request: { url: '/ai/chat', transport: 'websocket' },
  });

  return (
    <div>
      <button onClick={() => send('Explain WebSockets')} disabled={isStreaming}>
        Ask
      </button>
      <p>{fullText}</p>
      {stats && <small>{stats.totalTokens} tokens</small>}
      {routing.model && <small>Model: {routing.model}</small>}
    </div>
  );
}
```

## AI in Rooms -- Virtual Members

```java
var client = AiConfig.get().client();
var assistant = new LlmRoomMember("assistant", client, "gpt-5",
    "You are a helpful coding assistant");

Room room = rooms.room("dev-chat");
room.joinVirtual(assistant);
// Now when any user sends a message, the LLM responds in the same room
```

## StreamingSession Wire Protocol

The client receives JSON messages over WebSocket/SSE:

- `{"type":"token","content":"Hello"}` -- a single token
- `{"type":"progress","message":"Thinking..."}` -- status update
- `{"type":"complete"}` -- stream finished
- `{"type":"error","message":"..."}` -- stream failed

## Configuration

Configure the built-in client with environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `LLM_MODE` | `remote` (cloud) or `local` (Ollama) | `remote` |
| `LLM_MODEL` | `gemini-2.5-flash`, `gpt-5`, `o3-mini`, `llama3.2`, ... | `gemini-2.5-flash` |
| `LLM_API_KEY` | API key (or `GEMINI_API_KEY` for Gemini) | -- |
| `LLM_BASE_URL` | Override endpoint (auto-detected from model name) | auto |

## Key Components

| Class | Description |
|-------|-------------|
| `@AiEndpoint` | Marks a class as an AI chat endpoint with a path, system prompt, and interceptors |
| `@Prompt` | Marks the method that handles user messages |
| `@AiTool` | Marks a method as an AI-callable tool (framework-agnostic) |
| `@Param` | Describes a tool parameter's name, description, and required flag |
| `AiSupport` | SPI for AI framework backends (ServiceLoader-discovered) |
| `AiRequest` | Framework-agnostic request record (message, systemPrompt, model, hints) |
| `AiInterceptor` | Pre/post processing hooks for RAG, guardrails, logging |
| `AiConversationMemory` | SPI for conversation history storage |
| `StreamingSession` | Streams tokens, progress updates, and metadata to the client |
| `StreamingSessions` | Factory for creating `StreamingSession` instances |
| `OpenAiCompatibleClient` | Built-in HTTP client for OpenAI-compatible APIs |
| `RoutingLlmClient` | Routes prompts to different LLM backends based on rules |
| `ToolRegistry` | Global registry for `@AiTool` definitions |
| `ModelRouter` | SPI for intelligent model routing and failover |
| `AiGuardrail` | SPI for pre/post-LLM safety inspection |
| `AiMetrics` | SPI for AI observability (tokens, latency, cost) |
| `ConversationPersistence` | SPI for durable conversation storage (Redis, SQLite) |
| `RetryPolicy` | Exponential backoff with circuit-breaker semantics |

## Samples

- [Spring Boot AI Chat](../samples/spring-boot-ai-chat/) -- built-in client with Gemini/OpenAI/Ollama
- [Spring Boot Spring AI Chat](../samples/spring-boot-spring-ai-chat/) -- Spring AI adapter
- [Spring Boot LangChain4j Chat](../samples/spring-boot-langchain4j-chat/) -- LangChain4j adapter
- [Spring Boot ADK Chat](../samples/spring-boot-adk-chat/) -- Google ADK adapter
- [Spring Boot Embabel Chat](../samples/spring-boot-embabel-chat/) -- Embabel agent adapter
- [Spring Boot AI Tools](../samples/spring-boot-ai-tools/) -- framework-agnostic `@AiTool` pipeline
- [Spring Boot LangChain4j Tools](../samples/spring-boot-langchain4j-tools/) -- LangChain4j-native `@Tool` with PII/cost filters
- [Spring Boot ADK Tools](../samples/spring-boot-adk-tools/) -- Google ADK with `@AiTool` bridge
- [Spring Boot Spring AI Routing](../samples/spring-boot-spring-ai-routing/) -- cost/latency model routing

## See Also

- [Spring AI Adapter](spring-ai.md)
- [LangChain4j Adapter](langchain4j.md)
- [Google ADK Adapter](adk.md)
- [Embabel Adapter](embabel.md)
- [MCP Server](mcp.md) -- AI-MCP bridge for tool-driven streaming
- [Rooms & Presence](rooms.md) -- AI virtual members in rooms
- [atmosphere.js](client-javascript.md) -- `useStreaming` React/Vue/Svelte hooks
- [Module README](../modules/ai/README.md)
