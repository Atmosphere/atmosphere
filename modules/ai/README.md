# Atmosphere AI

AI/LLM streaming module for Atmosphere. Provides `@AiEndpoint`, `@Prompt`, `StreamingSession`, the `AiSupport` SPI for auto-detected AI framework adapters, and a built-in `OpenAiCompatibleClient` that works with Gemini, OpenAI, Ollama, and any OpenAI-compatible API.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai</artifactId>
    <version>4.0.24</version>
</dependency>
```

## Minimal Example

```java
@AiEndpoint(path = "/atmosphere/ai-chat",
            systemPrompt = "You are a helpful assistant.")
public class MyAiChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // auto-detects AI framework from classpath
    }
}
```

The `@AiEndpoint` annotation replaces the boilerplate of `@ManagedService` + `@Ready` + `@Disconnect` + `@Message` for AI streaming use cases. The `@Prompt` method runs on a virtual thread, so blocking LLM API calls do not block Atmosphere's thread pool.

`session.stream(message)` auto-detects the best available `AiSupport` implementation via `ServiceLoader` — drop an adapter JAR on the classpath and it just works, analogous to `AsyncSupport` for transports.

## AiSupport SPI

The `AiSupport` interface is the AI-layer equivalent of `AsyncSupport`. Implementations are discovered via `ServiceLoader`, filtered by `isAvailable()`, and the highest `priority()` wins.

| Adapter JAR | `AiSupport` implementation | Priority |
|-------------|---------------------------|----------|
| `atmosphere-ai` (built-in) | `BuiltInAiSupport` (OpenAI-compatible) | 0 |
| `atmosphere-spring-ai` | `SpringAiSupport` | 100 |
| `atmosphere-langchain4j` | `LangChain4jAiSupport` | 100 |
| `atmosphere-adk` | `AdkAiSupport` | 100 |
| `atmosphere-embabel` | `EmbabelAiSupport` | 100 |

### AiInterceptor

Cross-cutting concerns go through `AiInterceptor`, not subclassing. Interceptors are declared on `@AiEndpoint` and executed in FIFO order for `preProcess`, LIFO for `postProcess` (matching the `AtmosphereInterceptor` convention):

```java
@AiEndpoint(path = "/ai/chat",
            interceptors = {RagInterceptor.class, GuardrailInterceptor.class})
public class MyChat { ... }

public class RagInterceptor implements AiInterceptor {
    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        String context = vectorStore.search(request.message());
        return request.withMessage(context + "\n\n" + request.message());
    }
}
```

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
        session.stream(message);  // AiRequest now carries conversation history
    }
}
```

When `conversationMemory = true`, the framework:

1. Captures each user message and the streamed assistant response (via `MemoryCapturingSession`)
2. Stores them as conversation turns per `AtmosphereResource`
3. Injects the full history into every subsequent `AiRequest`
4. Clears the history when the resource disconnects

The default implementation is `InMemoryConversationMemory`, which caps history at `maxHistoryMessages` (default 20). For external storage — Redis, a database, etc. — implement the `AiConversationMemory` SPI:

```java
public interface AiConversationMemory {
    List<ChatMessage> getHistory(String conversationId);
    void addMessage(String conversationId, ChatMessage message);
    void clear(String conversationId);
    int maxMessages();
}
```

## Key Components

| Class | Description |
|-------|-------------|
| `@AiEndpoint` | Marks a class as an AI chat endpoint with a path, system prompt, and interceptors |
| `@Prompt` | Marks the method that handles user messages |
| `AiSupport` | SPI for AI framework backends (ServiceLoader-discovered) |
| `AiRequest` | Framework-agnostic request record (message, systemPrompt, model, hints) |
| `AiInterceptor` | Pre/post processing hooks for RAG, guardrails, logging |
| `AiConversationMemory` | SPI for conversation history storage |
| `InMemoryConversationMemory` | Default in-process memory (capped at `maxHistoryMessages`) |
| `MemoryCapturingSession` | `StreamingSession` decorator that records assistant responses into memory |
| `AiStreamingSession` | `StreamingSession` wrapper that adds `stream(String)` with interceptor chain |
| `StreamingSession` | Delivers streaming texts, progress updates, and metadata to the client |
| `StreamingSessions` | Factory for creating `StreamingSession` instances |
| `OpenAiCompatibleClient` | Built-in HTTP client for OpenAI-compatible APIs (JDK HttpClient, no extra deps) |
| `AiConfig` | Configuration via environment variables or init-params |
| `ChatCompletionRequest` | Builder for chat completion requests |
| `RoutingLlmClient` | Routes prompts to different LLM backends based on content, model, cost, or latency rules |
| `AiResponseCacheListener` | Tracks cached streaming texts per session; supports coalesced aggregate events |
| `MicrometerAiMetrics` | `AiMetrics` implementation backed by Micrometer (counters, timers, gauges) |
| `TracingCapturingSession` | `StreamingSession` decorator that captures timing and reports to `AiMetrics` |

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

- `{"type":"streaming-text","content":"Hello"}` -- a single streaming text
- `{"type":"progress","message":"Thinking..."}` -- status update
- `{"type":"complete"}` -- stream finished
- `{"type":"error","message":"..."}` -- stream failed

## Cache Listener Coalescing

The `AiResponseCacheListener` fires per-streaming-text by default, which can be noisy under load. Coalesced listeners fire **once per session** when it completes or errors, providing aggregate metrics.

```java
var listener = new AiResponseCacheListener();
listener.addCoalescedListener(event -> {
    log.info("Session {} finished: {} streaming texts in {}ms (status: {})",
            event.sessionId(), event.totalStreamingTexts(),
            event.elapsedMs(), event.status());
});
broadcaster.getBroadcasterConfig()
        .getBroadcasterCache()
        .addBroadcasterCacheListener(listener);
```

| Class | Description |
|-------|-------------|
| `CoalescedCacheEvent` | Record: `sessionId`, `broadcasterId`, `totalStreamingTexts`, `status`, `elapsedMs` |
| `CoalescedCacheEventListener` | `@FunctionalInterface` — receives one event per completed session |

Per-streaming-text tracking is unchanged; coalesced events are purely additive. Listener exceptions are isolated — a failing listener does not prevent others from firing.

## Observability with Micrometer

`MicrometerAiMetrics` provides production-grade observability by implementing the `AiMetrics` SPI with [Micrometer](https://micrometer.io). Add `micrometer-core` to your classpath (it's an optional/provided dependency):

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
</dependency>
```

Wire it up:

```java
var metrics = new MicrometerAiMetrics(meterRegistry, "spring-ai");
```

### Metrics Recorded

| Metric | Type | Description |
|--------|------|-------------|
| `atmosphere.ai.prompts.total` | Counter | Total prompt requests |
| `atmosphere.ai.streaming_texts.total` | Counter | Total streaming text chunks |
| `atmosphere.ai.errors.total` | Counter | Errors by type (`timeout`, `rate_limit`, `server_error`, `unknown`) |
| `atmosphere.ai.prompt.duration` | Timer | Time from prompt to first streaming text (TTFT) |
| `atmosphere.ai.response.duration` | Timer | Full response wall-clock time |
| `atmosphere.ai.tool.duration` | Timer | Tool call execution time |
| `atmosphere.ai.active_sessions` | Gauge | Currently active streaming sessions |
| `atmosphere.ai.cost` | Summary | Cost per request |

All metrics are tagged with `model` and `provider`.

### TracingCapturingSession

`TracingCapturingSession` is a `StreamingSession` decorator that automatically captures timing and reports to any `AiMetrics` implementation:

- **Time to first streaming text (TTFT)** — latency from session start to first `send()` call
- **Total duration** — wall-clock time from start to `complete()` or `error()`
- **Streaming text count** — number of `send()` calls
- **Error classification** — categorizes errors as `timeout`, `rate_limit`, `server_error`, or `unknown`
- **Active session tracking** — calls `sessionStarted()`/`sessionEnded()` for gauge updates

```java
var session = new TracingCapturingSession(delegate, metrics, "gpt-4");
session.send("Hello");    // captures first-token time, increments count
session.send(" world");   // increments count
session.complete();        // reports TTFT, total duration, token usage, ends session
```

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

**CostBased** filters models where `costPerStreamingText * request.maxStreamingTexts() <= maxCost`, then picks the highest-capability model. Sends `routing.model` and `routing.cost` metadata.

**LatencyBased** filters models where `averageLatencyMs <= maxLatencyMs`, then picks the highest-capability model. Sends `routing.model` and `routing.latency` metadata.

Rules are evaluated in order; first match wins. If no model fits the constraint, the rule is skipped and the next rule is tried.

## Samples

- [Spring Boot AI Chat](../../samples/spring-boot-ai-chat/) -- works with all backends (swap one Maven dependency)
- [Spring Boot AI Tools](../../samples/spring-boot-ai-tools/) -- framework-agnostic tool calling
- [Dentist Agent](../../samples/spring-boot-dentist-agent/) -- full `@Agent` with commands, tools, and multi-channel

## AI-MCP Bridge

When used together with `atmosphere-mcp`, MCP tool methods can receive a `StreamingSession` backed by a `Broadcaster` — enabling AI agents to stream texts to browser clients without needing a direct WebSocket connection.

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

The `BroadcasterStreamingSession` class wraps a `Broadcaster` and emits the same wire format as `DefaultStreamingSession` — the browser client sees identical JSON messages regardless of whether streaming texts originate from a direct WebSocket connection or an MCP tool call.

See [atmosphere-mcp README](../mcp/README.md) for injectable parameter details.

## Full Documentation

See [docs/ai.md](../../docs/ai.md) for complete documentation.

## Requirements

- Java 21+
- `atmosphere-runtime` (transitive)
