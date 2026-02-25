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
