# Atmosphere AI

AI/LLM streaming module for Atmosphere. Provides `@AiEndpoint`, `@Prompt`, `StreamingSession`, and a built-in `OpenAiCompatibleClient` that works with Gemini, OpenAI, Ollama, and any OpenAI-compatible API.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai</artifactId>
    <version>4.0.2</version>
</dependency>
```

## Minimal Example

```java
@AiEndpoint(path = "/atmosphere/ai-chat",
            systemPrompt = "You are a helpful assistant.")
public class MyAiChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        var settings = AiConfig.get();
        var request = ChatCompletionRequest.builder(settings.model())
                .system("You are a helpful assistant.")
                .user(message)
                .build();
        settings.client().streamChatCompletion(request, session);
    }
}
```

The `@AiEndpoint` annotation replaces the boilerplate of `@ManagedService` + `@Ready` + `@Disconnect` + `@Message` for AI streaming use cases. The `@Prompt` method runs on a virtual thread, so blocking LLM API calls do not block Atmosphere's thread pool.

## Key Components

| Class | Description |
|-------|-------------|
| `@AiEndpoint` | Marks a class as an AI chat endpoint with a path and optional system prompt |
| `@Prompt` | Marks the method that handles user messages |
| `StreamingSession` | Streams tokens, progress updates, and metadata to the client |
| `StreamingSessions` | Factory for creating `StreamingSession` instances |
| `OpenAiCompatibleClient` | Built-in HTTP client for OpenAI-compatible APIs (JDK HttpClient, no extra deps) |
| `AiConfig` | Configuration via environment variables or init-params |
| `ChatCompletionRequest` | Builder for chat completion requests |
| `RoutingLlmClient` | Routes prompts to different LLM backends based on content, model, cost, or latency rules |
| `AiResponseCacheListener` | Tracks cached tokens per session; supports coalesced aggregate events |

## Configuration

Set environment variables or use Atmosphere init-params:

```bash
# Gemini (default)
export LLM_MODE=remote
export LLM_MODEL=gemini-2.5-flash
export LLM_API_KEY=AIza...

# OpenAI
export LLM_MODEL=gpt-4o-mini
export LLM_BASE_URL=https://api.openai.com/v1
export LLM_API_KEY=sk-...

# Ollama (local)
export LLM_MODE=local
export LLM_MODEL=llama3.2
```

## StreamingSession Wire Protocol

The client receives JSON messages over WebSocket/SSE:

- `{"type":"token","content":"Hello"}` -- a single token
- `{"type":"progress","message":"Thinking..."}` -- status update
- `{"type":"complete"}` -- stream finished
- `{"type":"error","message":"..."}` -- stream failed

## Cache Listener Coalescing

The `AiResponseCacheListener` fires per-token by default, which can be noisy under load. Coalesced listeners fire **once per session** when it completes or errors, providing aggregate metrics.

```java
var listener = new AiResponseCacheListener();
listener.addCoalescedListener(event -> {
    log.info("Session {} finished: {} tokens in {}ms (status: {})",
            event.sessionId(), event.totalTokens(),
            event.elapsedMs(), event.status());
});
broadcaster.getBroadcasterConfig()
        .getBroadcasterCache()
        .addBroadcasterCacheListener(listener);
```

| Class | Description |
|-------|-------------|
| `CoalescedCacheEvent` | Record: `sessionId`, `broadcasterId`, `totalTokens`, `status`, `elapsedMs` |
| `CoalescedCacheEventListener` | `@FunctionalInterface` — receives one event per completed session |

Per-token tracking is unchanged; coalesced events are purely additive. Listener exceptions are isolated — a failing listener does not prevent others from firing.

## Cost and Latency Routing

`RoutingLlmClient` supports cost-based and latency-based routing rules alongside the existing content-based and model-based rules. Each rule uses `ModelOption` records that carry cost, latency, and capability metadata.

```java
var router = RoutingLlmClient.builder(defaultClient, "gemini-2.5-flash")
        // Route expensive requests to the cheapest model that fits the budget
        .route(RoutingRule.costBased(5.0, List.of(
                new ModelOption(openaiClient, "gpt-4o", 0.01, 200, 10),
                new ModelOption(geminiClient, "gemini-flash", 0.001, 50, 5))))
        // Route latency-sensitive requests to the fastest capable model
        .route(RoutingRule.latencyBased(100, List.of(
                new ModelOption(ollamaClient, "llama3.2", 0.0, 30, 3),
                new ModelOption(openaiClient, "gpt-4o-mini", 0.005, 80, 7))))
        .build();
```

**CostBased** filters models where `costPerToken * request.maxTokens() <= maxCost`, then picks the highest-capability model. Sends `routing.model` and `routing.cost` metadata.

**LatencyBased** filters models where `averageLatencyMs <= maxLatencyMs`, then picks the highest-capability model. Sends `routing.model` and `routing.latency` metadata.

Rules are evaluated in order; first match wins. If no model fits the constraint, the rule is skipped and the next rule is tried.

## Sample

- [Spring Boot AI Chat](../../samples/spring-boot-ai-chat/) -- streaming LLM responses with Gemini/OpenAI/Ollama

## AI-MCP Bridge

When used together with `atmosphere-mcp`, MCP tool methods can receive a `StreamingSession` backed by a `Broadcaster` — enabling AI agents to stream tokens to browser clients without needing a direct WebSocket connection.

```java
@McpTool(name = "ask_ai", description = "Ask the AI and stream to a topic")
public String askAi(
        @McpParam(name = "question") String question,
        @McpParam(name = "topic") String topic,
        StreamingSession session) {
    // session broadcasts to all clients on the topic
    session.send("Thinking...", StreamingSession.MessageType.PROGRESS);
    settings.client().streamChatCompletion(request, session);
    return "streaming";
}
```

The `BroadcasterStreamingSession` class wraps a `Broadcaster` and emits the same wire format as `DefaultStreamingSession` — the browser client sees identical JSON messages regardless of whether tokens originate from a direct WebSocket connection or an MCP tool call.

See [atmosphere-mcp README](../mcp/README.md) for injectable parameter details.

## Requirements

- Java 21+
- `atmosphere-runtime` (transitive)
