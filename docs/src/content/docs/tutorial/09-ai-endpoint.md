---
title: "@AiEndpoint & StreamingSession"
description: "Build an AI chat endpoint that streams LLM tokens to the browser over WebSocket or SSE"
---

Chapters 1--8 covered the transport layer: Broadcasters, rooms, interceptors, and transports. Starting here, we shift to the **AI platform** built on top of that transport layer. This chapter introduces `@AiEndpoint`, the annotation that turns a plain Java class into a streaming AI chat endpoint, and `StreamingSession`, the object that delivers tokens from an LLM to the browser in real time.

## What You Will Build

A chat endpoint that:

1. Accepts user prompts over WebSocket.
2. Streams LLM tokens back as they arrive.
3. Maintains conversation history across turns.
4. Works with Gemini, OpenAI, Ollama, or any OpenAI-compatible API -- out of the box, zero extra dependencies.

## Prerequisites

Add `atmosphere-ai` alongside the core runtime:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
```

If you are using the Spring Boot starter, `atmosphere-spring-boot-starter` already pulls in `atmosphere-ai` transitively.

## @AiEndpoint -- The Annotation

`@AiEndpoint` replaces the boilerplate combination of `@ManagedService` + `@Ready` + `@Disconnect` + `@Message` that you would otherwise need for an AI streaming use case. A single annotation configures the path, system prompt, conversation memory, tools, interceptors, filters, and model.

```java
@AiEndpoint(
    path = "/ai/chat",
    systemPrompt = "You are a helpful assistant that answers concisely.",
    conversationMemory = true,
    maxHistoryMessages = 20,
    tools = {},
    interceptors = {},
    filters = {},
    model = "",
    fallbackStrategy = FallbackStrategy.NONE
)
public class ChatBot {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

### Attribute Reference

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `path` | `String` | (required) | The URL path clients connect to (e.g., `/ai/chat`). |
| `systemPrompt` | `String` | `""` | System instructions sent to the LLM before every user message. |
| `conversationMemory` | `boolean` | `false` | When `true`, the framework records each turn and injects history into subsequent requests. |
| `maxHistoryMessages` | `int` | `20` | Maximum number of conversation turns retained in memory. Oldest messages are evicted first. |
| `tools` | `Class<?>[]` | `{}` | Classes whose `@AiTool` methods are registered for this endpoint. See [Chapter 10](/docs/tutorial/10-ai-tools/). |
| `interceptors` | `Class<?>[]` | `{}` | `AiInterceptor` implementations for pre/post processing (RAG, guardrails, logging). |
| `model` | `String` | `""` | Override the global model for this endpoint (e.g., `"gpt-4o"`, `"gemini-2.5-flash"`). Empty means use the global default. |
| `filters` | `Class<?>[]` | `{}` | Broadcast filters auto-registered on the endpoint's `Broadcaster` (e.g., `CostMeteringFilter.class`). See [Chapter 12](/docs/tutorial/12-ai-filters/). |
| `fallbackStrategy` | `FallbackStrategy` | `NONE` | How the framework handles backend failures: `NONE`, `FAILOVER`, `ROUND_ROBIN`. |

### What Happens at Startup

When `AtmosphereFramework` scans your classpath and finds `@AiEndpoint`:

1. It registers a `Broadcaster` at the given `path`.
2. It sets up the `@Ready` / `@Disconnect` lifecycle hooks internally.
3. It discovers the best `AiSupport` implementation via `ServiceLoader` (more on this in [Chapter 11](/docs/tutorial/11-ai-adapters/)).
4. If `conversationMemory = true`, it wraps the `StreamingSession` in a `MemoryCapturingSession` that records both user and assistant messages.
5. If `tools` are specified, it scans those classes for `@AiTool` methods and registers them in the `DefaultToolRegistry`.
6. If `filters` are specified, it adds them to the Broadcaster's filter chain.

## @Prompt -- Handling User Messages

The `@Prompt` annotation marks the method that receives user messages. It has a fixed signature:

```java
@Prompt
public void onPrompt(String message, StreamingSession session) {
    // message: the raw text the user sent
    // session: your handle for streaming tokens back
    session.stream(message);
}
```

### Virtual Thread Execution

Every `@Prompt` invocation runs on a **virtual thread**. This means you can make blocking calls -- HTTP requests to an LLM, database lookups, file I/O -- without worrying about thread pool exhaustion. The framework handles the scheduling.

```java
@Prompt
public void onPrompt(String message, StreamingSession session) {
    // This blocks the virtual thread, but that's fine --
    // the platform thread is released back to the pool.
    var context = vectorStore.search(message);  // blocking I/O
    session.stream(context + "\n\n" + message);
}
```

### Custom Logic Before Streaming

You are not limited to calling `session.stream()`. The `@Prompt` method is your code -- do whatever you need:

```java
@Prompt
public void onPrompt(String message, StreamingSession session) {
    // Validate input
    if (message.isBlank()) {
        session.error("Please enter a message.");
        return;
    }

    // Send a progress indicator while you prepare
    session.sendProgress("Searching knowledge base...");

    // Retrieve context from a vector store
    var context = vectorStore.search(message);

    // Send another progress indicator
    session.sendProgress("Generating response...");

    // Now stream the LLM response
    session.stream(context + "\n\nUser question: " + message);
}
```

## StreamingSession -- The Streaming API

`StreamingSession` is the primary interface for sending data from the server to the client during an AI interaction. It wraps the underlying `AtmosphereResource` and its `Broadcaster`, formatting messages according to the wire protocol.

### Core Methods

```java
public interface StreamingSession {

    /** Stream an LLM response for the given prompt. Auto-detects AiSupport. */
    void stream(String message);

    /** Send a single token to the client. */
    void send(String token);

    /** Send a progress/status update to the client. */
    void sendProgress(String message);

    /** Send arbitrary metadata as a JSON object. */
    void sendMetadata(Map<String, Object> metadata);

    /** Signal that the stream is complete. */
    void complete();

    /** Signal an error to the client. */
    void error(String message);

    /** Get the underlying AtmosphereResource. */
    AtmosphereResource resource();
}
```

### Method-by-Method Guide

**`stream(String message)`** -- The most common call. It builds an `AiRequest` from the message, injects the system prompt and conversation history (if enabled), resolves the best `AiSupport` backend, and streams tokens to the client as they arrive. The method blocks the virtual thread until streaming completes.

```java
session.stream(message);
```

**`send(String token)`** -- Sends a single token. Useful when you are driving the streaming loop yourself instead of delegating to `AiSupport`:

```java
for (var token : myCustomTokenizer.tokenize(response)) {
    session.send(token);
    Thread.sleep(50);  // simulate streaming delay
}
session.complete();
```

**`sendProgress(String message)`** -- Sends a status update that the client can display as a loading indicator. Progress messages are not part of the final response text:

```java
session.sendProgress("Searching 42,000 documents...");
session.sendProgress("Found 7 relevant passages. Generating answer...");
session.stream(message);
```

**`sendMetadata(Map<String, Object> metadata)`** -- Sends arbitrary key-value data. Useful for token counts, model information, cost estimates, or custom application data:

```java
session.sendMetadata(Map.of(
    "model", "gemini-2.5-flash",
    "estimatedTokens", 1500,
    "searchResults", 7
));
```

**`complete()`** -- Signals end-of-stream. Called automatically by `stream()`, but you must call it explicitly if you are using `send()` directly:

```java
session.send("Hello ");
session.send("world!");
session.complete();  // required when using send() directly
```

**`error(String message)`** -- Sends an error to the client and terminates the stream:

```java
try {
    session.stream(message);
} catch (RateLimitException e) {
    session.error("Rate limit exceeded. Please try again in a minute.");
}
```

## Wire Protocol

`StreamingSession` sends JSON messages over the WebSocket (or SSE) connection. The client receives these as individual frames/events:

### Token Message

Sent by `send()` and internally by `stream()` for each LLM token:

```json
{"type": "token", "content": "Hello"}
```

### Progress Message

Sent by `sendProgress()`:

```json
{"type": "progress", "message": "Searching knowledge base..."}
```

### Metadata Message

Sent by `sendMetadata()`:

```json
{"type": "metadata", "data": {"model": "gemini-2.5-flash", "tokens": 142}}
```

### Complete Message

Sent by `complete()` and automatically at the end of `stream()`:

```json
{"type": "complete"}
```

### Error Message

Sent by `error()`:

```json
{"type": "error", "message": "Rate limit exceeded"}
```

### Client-Side Handling

The `atmosphere.js` client parses these messages automatically with the `useStreaming` hook:

```tsx
import { useStreaming } from 'atmosphere.js/react';

function AiChat() {
    const { fullText, isStreaming, stats, send } = useStreaming({
        request: { url: '/ai/chat', transport: 'websocket' },
    });

    return (
        <div>
            <div className="response">{fullText}</div>
            {isStreaming && <span className="cursor blink" />}
            <input
                onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                        send(e.currentTarget.value);
                        e.currentTarget.value = '';
                    }
                }}
                placeholder="Ask something..."
                disabled={isStreaming}
            />
        </div>
    );
}
```

If you are writing a plain JavaScript client without the hooks:

```javascript
const socket = new WebSocket('ws://localhost:8080/ai/chat');

socket.onmessage = (event) => {
    const msg = JSON.parse(event.data);
    switch (msg.type) {
        case 'token':
            document.getElementById('output').textContent += msg.content;
            break;
        case 'progress':
            document.getElementById('status').textContent = msg.message;
            break;
        case 'complete':
            document.getElementById('status').textContent = '';
            break;
        case 'error':
            document.getElementById('error').textContent = msg.message;
            break;
    }
};

socket.onopen = () => {
    socket.send('Explain WebSockets in one paragraph');
};
```

## Conversation Memory

Without conversation memory, every message is stateless -- the LLM has no idea what was said before. Enable multi-turn conversations with one attribute:

```java
@AiEndpoint(
    path = "/ai/chat",
    systemPrompt = "You are a helpful assistant",
    conversationMemory = true,
    maxHistoryMessages = 20
)
public class ChatBot {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

### How It Works Internally

When `conversationMemory = true`:

1. The framework wraps your `StreamingSession` in a `MemoryCapturingSession`.
2. When the user sends a message, it is recorded as a `ChatMessage` with role `USER`.
3. The `MemoryCapturingSession` captures every token from the LLM response and, on `complete()`, stores the assembled response as a `ChatMessage` with role `ASSISTANT`.
4. On the next request, the full conversation history is injected into the `AiRequest` before it reaches the `AiSupport` backend.
5. When the `AtmosphereResource` disconnects, the conversation is cleared.

### Message Lifecycle

```
Turn 1:
  User sends "What is WebSocket?"
  → history: [{role: USER, content: "What is WebSocket?"}]
  ← LLM streams response, captured
  → history: [{role: USER, ...}, {role: ASSISTANT, content: "WebSocket is..."}]

Turn 2:
  User sends "How does it differ from SSE?"
  → AiRequest includes full history + new message
  ← LLM sees the full conversation, responds in context
  → history now has 4 messages

...

Turn 11 (maxHistoryMessages = 20):
  Oldest messages are evicted when the limit is reached
```

### AiConversationMemory SPI

The default implementation is `InMemoryConversationMemory` -- a `ConcurrentHashMap` keyed by the `AtmosphereResource` UUID. For production deployments where you need persistence across restarts or sharing across nodes, implement the `AiConversationMemory` SPI:

```java
public interface AiConversationMemory {

    /** Retrieve conversation history for a given ID. */
    List<ChatMessage> getHistory(String conversationId);

    /** Add a message to the conversation. */
    void addMessage(String conversationId, ChatMessage message);

    /** Clear all messages for a conversation. */
    void clear(String conversationId);

    /** Maximum messages to retain. */
    int maxMessages();
}
```

Register your implementation via `ServiceLoader`:

```
# META-INF/services/org.atmosphere.ai.memory.AiConversationMemory
com.example.RedisConversationMemory
```

Atmosphere discovers persistence backends automatically. Built-in options:

| Backend | Artifact | Notes |
|---------|----------|-------|
| In-memory (default) | `atmosphere-ai` | `ConcurrentHashMap`, lost on restart |
| Redis | `atmosphere-ai-redis` | Shared across cluster nodes, TTL-based expiry |
| SQLite | `atmosphere-ai-sqlite` | Local persistence, survives restarts |

### Custom Memory Example -- Redis

```java
public class RedisConversationMemory implements AiConversationMemory {

    private final RedisTemplate<String, List<ChatMessage>> redis;
    private final int maxMessages;

    public RedisConversationMemory(RedisTemplate<String, List<ChatMessage>> redis,
                                   int maxMessages) {
        this.redis = redis;
        this.maxMessages = maxMessages;
    }

    @Override
    public List<ChatMessage> getHistory(String conversationId) {
        var history = redis.opsForValue().get("conv:" + conversationId);
        return history != null ? history : List.of();
    }

    @Override
    public void addMessage(String conversationId, ChatMessage message) {
        var key = "conv:" + conversationId;
        var history = new ArrayList<>(getHistory(conversationId));
        history.add(message);
        while (history.size() > maxMessages) {
            history.removeFirst();
        }
        redis.opsForValue().set(key, history, Duration.ofHours(24));
    }

    @Override
    public void clear(String conversationId) {
        redis.delete("conv:" + conversationId);
    }

    @Override
    public int maxMessages() {
        return maxMessages;
    }
}
```

## Built-in OpenAI-Compatible Client

When no third-party AI adapter (Spring AI, LangChain4j, etc.) is on the classpath, `@AiEndpoint` uses the **built-in `OpenAiCompatibleClient`**. This is a lightweight HTTP client that speaks the OpenAI chat completions API, which is supported by Gemini, OpenAI, Ollama, and many other providers.

### Configuration

The client is configured through environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `LLM_MODE` | `remote` (cloud API) or `local` (Ollama on localhost) | `remote` |
| `LLM_MODEL` | Model name: `gemini-2.5-flash`, `gpt-4o`, `o3-mini`, `llama3.2`, etc. | `gemini-2.5-flash` |
| `LLM_API_KEY` | API key for the provider (or `GEMINI_API_KEY` for Gemini specifically) | -- |
| `LLM_BASE_URL` | Override the endpoint URL (auto-detected from model name if not set) | auto |

### Auto-Detection of Provider

The client infers the API endpoint from the model name:

| Model prefix | Provider | Base URL |
|-------------|----------|----------|
| `gemini-*` | Google | `https://generativelanguage.googleapis.com/v1beta/openai` |
| `gpt-*`, `o1-*`, `o3-*` | OpenAI | `https://api.openai.com/v1` |
| `claude-*` | Anthropic | `https://api.anthropic.com/v1` |
| `llama*`, `mistral*` (local mode) | Ollama | `http://localhost:11434/v1` |
| Any (with custom `LLM_BASE_URL`) | Custom | The provided URL |

### Running with Different Providers

**Gemini (default):**
```bash
export LLM_API_KEY=AIza...
export LLM_MODEL=gemini-2.5-flash
java -jar myapp.jar
```

**OpenAI:**
```bash
export LLM_API_KEY=sk-...
export LLM_MODEL=gpt-4o
java -jar myapp.jar
```

**Ollama (local):**
```bash
# No API key needed
export LLM_MODE=local
export LLM_MODEL=llama3.2
java -jar myapp.jar
```

### Spring Boot Configuration

In a Spring Boot application, you can also use `application.properties`:

```properties
atmosphere.ai.llm-mode=remote
atmosphere.ai.llm-model=gemini-2.5-flash
atmosphere.ai.llm-api-key=${GEMINI_API_KEY}
```

## AiInterceptor -- Pre/Post Processing

For cross-cutting concerns like RAG (Retrieval-Augmented Generation), guardrails, or logging, use `AiInterceptor` instead of cluttering your `@Prompt` method:

```java
public interface AiInterceptor {

    /** Called before the prompt is sent to the LLM. */
    AiRequest preProcess(AiRequest request, AtmosphereResource resource);

    /** Called after the LLM response is complete. */
    default void postProcess(AiResponse response, AtmosphereResource resource) {
        // optional
    }
}
```

### RAG Interceptor Example

```java
public class RagInterceptor implements AiInterceptor {

    private final VectorStore vectorStore;

    public RagInterceptor(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        // Search for relevant documents
        var results = vectorStore.search(request.message(), 5);

        // Build context from search results
        var context = results.stream()
                .map(doc -> "- " + doc.content())
                .collect(Collectors.joining("\n"));

        // Prepend context to the user's message
        var augmented = """
                Use the following context to answer the question.
                If the context doesn't contain relevant information, say so.

                Context:
                %s

                Question: %s
                """.formatted(context, request.message());

        return request.withMessage(augmented);
    }
}
```

### Logging Interceptor Example

```java
public class LoggingInterceptor implements AiInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        log.info("AI request from {}: {} chars",
                resource.uuid(), request.message().length());
        return request;  // pass through unchanged
    }

    @Override
    public void postProcess(AiResponse response, AtmosphereResource resource) {
        log.info("AI response to {}: {} tokens, {}ms first-token latency",
                resource.uuid(), response.tokenCount(), response.firstTokenLatencyMs());
    }
}
```

### Registering Interceptors

Wire interceptors to an endpoint via the annotation:

```java
@AiEndpoint(
    path = "/ai/chat",
    systemPrompt = "You are a helpful assistant",
    interceptors = {RagInterceptor.class, LoggingInterceptor.class}
)
public class ChatBot {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

Interceptors execute in declaration order: `RagInterceptor.preProcess` runs first, then `LoggingInterceptor.preProcess`, then the LLM call, then `LoggingInterceptor.postProcess`, then `RagInterceptor.postProcess`.

## Putting It All Together

Here is a complete endpoint that uses conversation memory, interceptors, and error handling:

```java
@AiEndpoint(
    path = "/ai/assistant",
    systemPrompt = """
        You are a senior software engineer. You give concise, accurate answers.
        When you don't know something, you say so.
        Format code examples with markdown fences.
        """,
    conversationMemory = true,
    maxHistoryMessages = 30,
    interceptors = {RagInterceptor.class, LoggingInterceptor.class}
)
public class AssistantEndpoint {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        if (message.isBlank()) {
            session.error("Message cannot be empty.");
            return;
        }

        if (message.length() > 10_000) {
            session.error("Message too long. Maximum 10,000 characters.");
            return;
        }

        session.sendProgress("Thinking...");

        try {
            session.stream(message);
        } catch (Exception e) {
            session.error("Something went wrong: " + e.getMessage());
        }
    }
}
```

### The Browser Client

```tsx
import { useStreaming } from 'atmosphere.js/react';
import { useState, useRef } from 'react';

function Assistant() {
    const [input, setInput] = useState('');
    const [messages, setMessages] = useState<Array<{role: string, text: string}>>([]);
    const messagesEndRef = useRef<HTMLDivElement>(null);

    const { fullText, isStreaming, progress, send } = useStreaming({
        request: { url: '/ai/assistant', transport: 'websocket' },
        onComplete: (text) => {
            setMessages(prev => [...prev, { role: 'assistant', text }]);
        },
    });

    const handleSend = () => {
        if (!input.trim() || isStreaming) return;
        setMessages(prev => [...prev, { role: 'user', text: input }]);
        send(input);
        setInput('');
    };

    return (
        <div className="chat">
            <div className="messages">
                {messages.map((m, i) => (
                    <div key={i} className={`message ${m.role}`}>{m.text}</div>
                ))}
                {isStreaming && (
                    <div className="message assistant streaming">
                        {progress || fullText || '...'}
                    </div>
                )}
                <div ref={messagesEndRef} />
            </div>
            <div className="input-area">
                <input
                    value={input}
                    onChange={(e) => setInput(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && handleSend()}
                    placeholder="Ask something..."
                    disabled={isStreaming}
                />
                <button onClick={handleSend} disabled={isStreaming}>
                    Send
                </button>
            </div>
        </div>
    );
}
```

## Per-Endpoint Model Override

Each endpoint can target a different model:

```java
@AiEndpoint(path = "/ai/fast", model = "gemini-2.5-flash",
            systemPrompt = "Be concise")
public class FastChat { /* ... */ }

@AiEndpoint(path = "/ai/reasoning", model = "o3-mini",
            systemPrompt = "Think step by step")
public class ReasoningChat { /* ... */ }

@AiEndpoint(path = "/ai/local", model = "llama3.2",
            systemPrompt = "You are a local assistant")
public class LocalChat { /* ... */ }
```

The `model` attribute overrides the global `LLM_MODEL` environment variable for that specific endpoint only.

## Sample Application

The `spring-boot-ai-chat` sample demonstrates everything in this chapter:

```bash
cd samples/spring-boot-ai-chat
export LLM_API_KEY=your-api-key
../../mvnw spring-boot:run
```

Open `http://localhost:8080` in your browser and start chatting. The sample includes:

- `@AiEndpoint` with conversation memory
- The built-in `OpenAiCompatibleClient`
- A React frontend using `useStreaming`
- Configurable model via environment variables

## What's Next

The endpoint we built uses `session.stream()` which auto-detects the AI backend. But what if you want the LLM to call functions -- look up weather, query a database, execute code? That's **tool calling**, and [Chapter 10](/docs/tutorial/10-ai-tools/) shows how `@AiTool` makes it work with any AI framework.

## Key Takeaways

- `@AiEndpoint` replaces `@ManagedService` boilerplate for AI streaming use cases.
- `@Prompt` methods run on virtual threads -- blocking calls are safe.
- `StreamingSession` has five message types: `token`, `progress`, `metadata`, `complete`, `error`.
- `conversationMemory = true` enables multi-turn conversations with automatic history management.
- The built-in `OpenAiCompatibleClient` works with Gemini, OpenAI, and Ollama via environment variables.
- `AiConversationMemory` is a pluggable SPI for external storage (Redis, SQLite).
- `AiInterceptor` provides pre/post hooks for RAG, guardrails, and logging.
