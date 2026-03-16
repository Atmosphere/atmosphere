---
title: "@AiEndpoint & StreamingSession"
description: "Build an AI chat endpoint that streams LLM texts to the browser over WebSocket or SSE"
sidebar:
  order: 9
---

This chapter introduces Atmosphere's **AI platform**. If you completed the [Getting Started](/docs/tutorial/02-getting-started/) guide, you already have a running Atmosphere application -- that is all you need to start streaming LLM texts. The core concepts ([Broadcaster](/docs/tutorial/05-broadcaster/), [Rooms](/docs/tutorial/06-rooms/), [Interceptors](/docs/tutorial/08-interceptors/)) are useful background but not prerequisites for `@AiEndpoint`.

`@AiEndpoint` turns a plain Java class into a streaming AI chat endpoint, and `StreamingSession` delivers streaming texts from an LLM to the browser in real time.

## What You Will Build

A chat endpoint that:

1. Accepts user messages over WebSocket or SSE
2. Sends them to an LLM (Gemini, GPT, Claude, or a local Ollama model)
3. Streams the response back text-by-text
4. Handles connect/disconnect lifecycle automatically
5. Runs the LLM call on a virtual thread so it never blocks the transport

## @AiEndpoint Annotation

`@AiEndpoint` eliminates the boilerplate of `@ManagedService` + `@Ready` + `@Disconnect` + `@Message` for AI streaming use cases. The annotated class must have exactly one method annotated with `@Prompt`.

### Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `path` | `String` | (required) | The URL path for this AI endpoint |
| `timeout` | `long` | `120_000` | Max inactive timeout in milliseconds before the connection is closed |
| `systemPrompt` | `String` | `""` | Inline system prompt text |
| `systemPromptResource` | `String` | `""` | Classpath path to a system prompt file (e.g., `"prompts/system-prompt.md"`). Takes precedence over `systemPrompt` |
| `interceptors` | `Class<? extends AiInterceptor>[]` | `{}` | AI interceptors applied to every prompt (FIFO for `preProcess`, LIFO for `postProcess`) |
| `conversationMemory` | `boolean` | `false` | Enable automatic multi-turn conversation memory per client |
| `maxHistoryMessages` | `int` | `20` | Max messages retained in conversation memory per client (10 turns) |
| `tools` | `Class<?>[]` | `{}` | Tool provider classes with `@AiTool`-annotated methods |
| `excludeTools` | `Class<?>[]` | `{}` | Tool classes to exclude (only relevant when `tools` is empty, meaning all tools available) |
| `fallbackStrategy` | `String` | `"NONE"` | Fallback strategy for model routing when the primary backend fails |
| `guardrails` | `Class<? extends AiGuardrail>[]` | `{}` | Guardrail classes that inspect requests before the LLM call and responses after |
| `contextProviders` | `Class<? extends ContextProvider>[]` | `{}` | RAG context augmentation providers |
| `requires` | `AiCapability[]` | `{}` | Backend capabilities this endpoint requires (fails fast at startup if not satisfied) |
| `model` | `String` | `""` | Override the model name for this endpoint (otherwise uses `AiConfig.get().model()`) |
| `filters` | `Class<? extends BroadcastFilter>[]` | `{}` | Broadcast filters for this endpoint's Broadcaster |

The framework automatically:

- Configures broadcaster cache and inactive timeout
- Logs connect/disconnect events
- Creates a `StreamingSession` per message
- Invokes the `@Prompt` method on a virtual thread

## The @Prompt Annotation

`@Prompt` marks the method that handles incoming user messages. It accepts two method signatures:

```java
// Minimal: message + session
@Prompt
public void onPrompt(String message, StreamingSession session) { ... }

// With resource access
@Prompt
public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) { ... }
```

The method is invoked on a virtual thread, so it may perform blocking I/O (HTTP calls to LLM APIs) without blocking the Atmosphere thread pool.

## Complete Example: AI Chat

This is the `AiChat` class from the `spring-boot-ai-chat` sample:

```java
@AiEndpoint(path = "/atmosphere/ai-chat",
        systemPromptResource = "prompts/system-prompt.md",
        requires = {AiCapability.TEXT_STREAMING, AiCapability.SYSTEM_PROMPT},
        conversationMemory = true)
public class AiChat {

    private static final Logger logger = LoggerFactory.getLogger(AiChat.class);

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Client {} connected (broadcaster: {})",
                resource.uuid(), resource.getBroadcaster().getID());
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("Client {} disconnected", event.getResource().uuid());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("Received prompt: {}", message);

        var settings = AiConfig.get();
        if (settings == null || settings.client().apiKey() == null
                || settings.client().apiKey().isBlank()) {
            DemoResponseProducer.stream(message, session);
            return;
        }

        session.stream(message);
    }
}
```

Key observations:

1. **`@Ready` and `@Disconnect`** work the same as in `@ManagedService` -- they handle connection lifecycle.
2. **`@Prompt`** receives the user's raw message and a `StreamingSession`.
3. **`session.stream(message)`** sends the message to the resolved AI backend and streams the response back. This is the simplest way to invoke the LLM -- the framework resolves the correct adapter (Spring AI, LangChain4j, ADK, or built-in) automatically.
4. **Demo fallback** -- if no API key is configured, the sample uses `DemoResponseProducer` to simulate streaming. This pattern is useful for local development without an API key.
5. **System prompt** -- loaded once at startup from `prompts/system-prompt.md` on the classpath via `PromptLoader`.

## StreamingSession API

`StreamingSession` is the core SPI interface that all AI framework adapters push streaming texts through. It extends `AutoCloseable` and is thread-safe.

```java
public interface StreamingSession extends AutoCloseable {

    String sessionId();

    void send(String streamingText);

    void sendMetadata(String key, Object value);

    void progress(String message);

    void complete();

    void complete(String summary);

    void error(Throwable t);

    boolean isClosed();

    void emit(AiEvent event);

    void sendContent(Content content);

    void stream(String message);
}
```

### Method Reference

| Method | Description |
|--------|-------------|
| `sessionId()` | Unique identifier for this streaming session |
| `send(streamingText)` | Send a text chunk to the client (typically a single streaming text from the LLM) |
| `emit(event)` | Emit a structured `AiEvent` (tool calls, agent steps, entities, etc.) |
| `sendMetadata(key, value)` | Send structured metadata alongside the stream (e.g., model name, usage stats) |
| `progress(message)` | Send a human-readable progress update (e.g., "Thinking...", "Searching documents...") |
| `complete()` | Signal that the stream has completed successfully |
| `complete(summary)` | Signal completion with an aggregated final response |
| `error(throwable)` | Signal that the stream has failed |
| `isClosed()` | Whether this session has been completed or errored |
| `sendContent(content)` | Send multi-modal content (text, images, files) |
| `stream(message)` | Send the user message to the resolved AI backend and stream the response back |

### send() vs. stream()

These two methods serve fundamentally different purposes:

- **`send(streamingText)`** -- pushes a single streaming text/chunk to the client. You call this yourself when you are manually generating or forwarding streaming texts. All AI adapter implementations call `send()` internally.
- **`stream(message)`** -- sends the user's message to the AI backend resolved by the `@AiEndpoint` infrastructure and streams the response automatically. This is a one-call shortcut that handles the entire LLM round-trip.

In the `AiChat` example, `session.stream(message)` is used because the framework knows how to route to the correct AI backend. If you wanted to handle the LLM call yourself, you would call `session.send()` for each streaming text.

### Multi-modal Content

The `sendContent(Content)` method supports sending different content types:

```java
// Text content (delegates to send())
session.sendContent(new Content.Text("Hello world"));

// Binary content types require overriding sendContent() in your session implementation
```

The wire protocol for content uses structured JSON:

```json
{"type":"content","contentType":"text","data":"...","sessionId":"...","seq":1}
{"type":"content","contentType":"image","mimeType":"image/png","data":"<base64>","sessionId":"...","seq":2}
```

## Wire Protocol

Every message from `StreamingSession` is a JSON object written directly to the WebSocket (or SSE) connection:

```json
{"type":"streaming-text","data":"Hello","sessionId":"abc-123","seq":1}
{"type":"streaming-text","data":" world","sessionId":"abc-123","seq":2}
{"type":"progress","data":"Thinking...","sessionId":"abc-123","seq":3}
{"type":"metadata","data":"{\"model\":\"gemini-2.5-flash\"}","sessionId":"abc-123","seq":4}
{"type":"complete","data":"","sessionId":"abc-123","seq":5}
```

### Message Types

| Type | Description |
|------|-------------|
| `streaming-text` | A single streaming text/chunk from the LLM |
| `progress` | A human-readable status update (e.g., "Searching documents...") |
| `metadata` | Structured metadata (model name, usage stats) |
| `complete` | Stream finished successfully |
| `error` | Stream failed -- `data` contains the error message |

The `seq` field is a monotonically increasing counter for deduplication on reconnect.

## Structured Events (AiEvent)

`StreamingSession.emit(AiEvent)` sends structured events to the client alongside text. This enables rich real-time UIs that show tool activity, agent steps, and progressive entity rendering.

`AiEvent` is a sealed interface with 13 event types:

| Event Type | Description |
|-----------|-------------|
| `TextDelta(text)` | A streaming text chunk |
| `TextComplete(fullText)` | Full text response complete |
| `ToolStart(toolName, arguments)` | A tool invocation has started |
| `ToolResult(toolName, result)` | A tool has returned a result |
| `ToolError(toolName, error)` | A tool invocation failed |
| `AgentStep(stepName, description, data)` | An agent workflow step |
| `StructuredField(fieldName, value, schemaType)` | A parsed field from structured output |
| `EntityStart(typeName, jsonSchema)` | Structured entity streaming started |
| `EntityComplete(typeName, entity)` | Entity fully assembled |
| `RoutingDecision(from, to, reason)` | Backend routing changed |
| `Progress(message, percentage)` | Progress update |
| `Error(message, code, recoverable)` | Structured error |
| `Complete(summary, usage)` | Stream complete with usage stats |

### Example: emitting tool events

```java
session.emit(new AiEvent.ToolStart("get_weather", Map.of("city", "Montreal")));
// ... tool executes ...
session.emit(new AiEvent.ToolResult("get_weather", Map.of("temp", 22)));
session.emit(new AiEvent.TextDelta("The weather in Montreal is 22°C."));
session.emit(new AiEvent.Complete(null, Map.of()));
```

Events are serialized as JSON frames:

```json
{"event":"tool-start","data":{"toolName":"get_weather","arguments":{"city":"Montreal"}},"sessionId":"abc","seq":1}
{"event":"tool-result","data":{"toolName":"get_weather","result":{"temp":22}},"sessionId":"abc","seq":2}
```

The `useStreaming` React hook exposes events via `aiEvents`:

```tsx
const { fullText, aiEvents, send } = useStreaming({ request });

// aiEvents contains: [{ event: "tool-start", data: {...} }, ...]
```

## Capability Validation

Use `requires` to declare which backend capabilities your endpoint needs. The framework validates at startup and fails fast with a clear error if the backend can't satisfy them:

```java
@AiEndpoint(path = "/tools-chat",
    requires = {AiCapability.TOOL_CALLING, AiCapability.CONVERSATION_MEMORY})
```

Available capabilities: `TEXT_STREAMING`, `TOOL_CALLING`, `STRUCTURED_OUTPUT`, `VISION`, `AUDIO`, `MULTI_MODAL`, `CONVERSATION_MEMORY`, `SYSTEM_PROMPT`, `AGENT_ORCHESTRATION`.

## Memory Strategies

Beyond the default sliding-window memory, Atmosphere provides pluggable `MemoryStrategy` implementations:

| Strategy | Description |
|----------|-------------|
| `MessageWindowStrategy` | Last N messages (default) |
| `TokenWindowStrategy` | Last N estimated tokens (chars/4 approximation) |
| `SummarizingStrategy` | Condenses old messages into a summary, preserves recent window |

## Option B: @ManagedService (Manual Approach)

The `@AiEndpoint` annotation handles lifecycle, session creation, and virtual thread dispatch automatically. For more control, you can use `@ManagedService` directly:

```java
@ManagedService(path = "/ai-chat")
public class AiChat {
    @Inject private AtmosphereResource resource;

    @Message
    public void onMessage(String prompt) {
        var settings = AiConfig.get();
        var session = StreamingSessions.start(resource);

        var request = ChatCompletionRequest.builder(settings.model())
                .system("You are a helpful assistant.")
                .user(prompt)
                .build();

        Thread.startVirtualThread(() -> settings.client().streamChatCompletion(request, session));
    }
}
```

Key differences from `@AiEndpoint`:

- You create the `StreamingSession` yourself via `StreamingSessions.start(resource)`.
- You build the `ChatCompletionRequest` manually with model name, system prompt, and user message.
- You launch the LLM call on a virtual thread explicitly with `Thread.startVirtualThread()`.
- You have full control over `@Ready`, `@Disconnect`, and error handling.

Both approaches produce the same wire protocol on the client side.

## AiConfig

`AiConfig` provides global LLM configuration. It can be set programmatically, from environment variables, or from Atmosphere init-params.

### Programmatic Configuration

```java
AiConfig.configure("remote", "gemini-2.5-flash", apiKey, null);
```

Parameters: `mode` ("remote" or "local"), `model` name, `apiKey`, and optional `baseUrl` (null for auto-detection).

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `LLM_MODE` | `remote` (cloud API) or `local` (Ollama) | `remote` |
| `LLM_MODEL` | Model name (e.g., `gemini-2.5-flash`, `gpt-4o`, `llama3.2`) | `gemini-2.5-flash` |
| `LLM_API_KEY` | API key (also checks `OPENAI_API_KEY`, `GEMINI_API_KEY`) | (none) |
| `LLM_BASE_URL` | Override the API endpoint | (auto-detected) |

Auto-detection resolves the base URL from the model name:

- Models starting with `gpt-` or `o1`/`o3` route to `https://api.openai.com/v1`
- All other remote models route to `https://generativelanguage.googleapis.com/v1beta/openai`
- Local mode routes to `http://localhost:11434/v1` (Ollama)

### Atmosphere Init-Params

```java
@ManagedService(path = "/ai-chat", atmosphereConfig = {
    AiConfig.LLM_MODEL + "=gemini-2.5-flash",
    AiConfig.LLM_MODE + "=remote",
    AiConfig.LLM_API_KEY + "=AIza..."
})
```

### Reading Configuration

```java
var settings = AiConfig.get();
if (settings != null) {
    String model = settings.model();        // e.g., "gemini-2.5-flash"
    String baseUrl = settings.baseUrl();    // resolved API endpoint
    boolean local = settings.isLocal();     // true if mode is "local"
    var client = settings.client();         // OpenAiCompatibleClient instance
}
```

The `LlmSettings` record returned by `AiConfig.get()` contains:

| Field | Type | Description |
|-------|------|-------------|
| `client()` | `OpenAiCompatibleClient` | HTTP client for the LLM API |
| `model()` | `String` | Model name |
| `mode()` | `String` | "remote" or "local" |
| `baseUrl()` | `String` | Resolved API endpoint URL |

## AiInterceptor

AI interceptors run around the `@Prompt` method, separate from the transport-level `AtmosphereInterceptor` from [Chapter 8](/docs/tutorial/08-interceptors/):

```java
public interface AiInterceptor {

    default AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        return request;
    }

    default void postProcess(AiRequest request, AtmosphereResource resource) {
    }
}
```

- **`preProcess`** runs FIFO (first declared, first executed). Return a modified `AiRequest` (e.g., with augmented message or different model) or the original request unchanged.
- **`postProcess`** runs LIFO (last declared, first executed), matching the `AtmosphereInterceptor` convention.

Specify interceptors on the annotation:

```java
@AiEndpoint(path = "/ai-chat",
    interceptors = {RagInterceptor.class, LoggingInterceptor.class})
```

### Real Example: CostMeteringInterceptor

The `spring-boot-ai-tools` sample includes a `CostMeteringInterceptor` that estimates input costs in `preProcess` and sends routing metadata to the client in `postProcess`:

```java
public class CostMeteringInterceptor implements AiInterceptor {

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        int totalChars = request.systemPrompt().length() + request.message().length();
        for (ChatMessage msg : request.history()) {
            totalChars += msg.content().length();
        }
        long estimatedStreamingTexts = totalChars / 4;

        // Store for postProcess
        resource.getRequest().setAttribute("cost.estStreamingTexts", estimatedStreamingTexts);
        resource.getRequest().setAttribute("cost.startNanos", System.nanoTime());

        return request;
    }

    @Override
    public void postProcess(AiRequest request, AtmosphereResource resource) {
        var startNanos = (Long) resource.getRequest().getAttribute("cost.startNanos");
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        // Send metadata to the client via the streaming session
        var session = resource.getRequest().getAttribute(
            AiStreamingSession.STREAMING_SESSION_ATTR);
        if (session instanceof StreamingSession s && !s.isClosed()) {
            s.sendMetadata("routing.latency", elapsedMs);
        }
    }
}
```

## Virtual Thread Execution

The `@Prompt` method is invoked on a virtual thread. This is critical because LLM API calls are blocking HTTP requests that can take seconds or even minutes. Running them on virtual threads means:

1. **No thread pool exhaustion** -- Atmosphere's platform thread pool is not consumed by LLM calls
2. **Simple blocking code** -- No need for reactive APIs or CompletableFuture; just call the API and it blocks the virtual thread
3. **Natural control flow** -- Use try/catch, loops, and sequential logic without callback chains

This is why `session.stream(message)` works so simply -- it blocks the virtual thread until streaming completes, but the underlying platform threads are free to handle other connections.

## Conversation Memory

Enable automatic conversation memory with `conversationMemory = true`:

```java
@AiEndpoint(path = "/chat",
    conversationMemory = true,
    maxHistoryMessages = 30)
```

When enabled, the framework:

1. Accumulates user/assistant turns per `AtmosphereResource` (keyed by `resource.uuid()`)
2. Injects the history into every `AiRequest` so all adapters get multi-turn context
3. Clears memory automatically when the client disconnects

The `AiConversationMemory` interface defines the SPI:

```java
public interface AiConversationMemory {
    List<ChatMessage> getHistory(String conversationId);
    void addMessage(String conversationId, ChatMessage message);
    void clear(String conversationId);
    int maxMessages();
}
```

The default implementation is `InMemoryConversationMemory`, which uses a sliding window capped at `maxHistoryMessages`.

## Guardrails and Context Providers

### Guardrails

`AiGuardrail` classes run before and after the LLM call:

```java
@AiEndpoint(path = "/chat",
    guardrails = {ContentSafetyGuardrail.class})
```

Execution order: guardrails -> interceptors -> [LLM] -> interceptors -> guardrails

### Context Providers

`ContextProvider` classes augment the prompt with RAG context:

```java
@AiEndpoint(path = "/chat",
    contextProviders = {DocumentSearchProvider.class})
```

## Client Integration

### Vanilla TypeScript

Use `subscribeStreaming` from `atmosphere.js` to connect to an `@AiEndpoint`:

```typescript
import { subscribeStreaming } from 'atmosphere.js';

const handle = await subscribeStreaming(atmosphere, {
  url: '/ai-chat',
  transport: 'websocket',
}, {
  onStreamingText:    (streamingText) => output.textContent += streamingText,
  onProgress: (msg)   => status.textContent = msg,
  onMetadata: (meta)  => { /* model info, usage */ },
  onComplete: ()      => console.log('Done'),
  onError:    (err)   => console.error(err),
});

handle.send('Explain virtual threads in Java 21');
handle.close(); // disconnect when done
```

The callbacks map directly to the [wire protocol](#wire-protocol) message types: `streaming-text`, `progress`, `metadata`, `complete`, and `error`.

### React -- `useStreaming`

The `useStreaming` hook manages connection lifecycle, streaming text accumulation, and streaming state:

```tsx
import { useStreaming } from 'atmosphere.js/react';

function AiChat() {
  const { fullText, isStreaming, progress, send, reset } = useStreaming({
    request: { url: '/ai-chat', transport: 'websocket' },
  });

  return (
    <div>
      <button onClick={() => send('What is Atmosphere?')} disabled={isStreaming}>Ask</button>
      {isStreaming && <span>{progress ?? 'Generating...'}</span>}
      <p>{fullText}</p>
      <button onClick={reset}>Clear</button>
    </div>
  );
}
```

`fullText` accumulates all `streaming-text` messages into a single string. `isStreaming` is `true` between `send()` and `complete`/`error`. `reset` clears the accumulated text for a new prompt.

### Vue -- `useStreaming`

The Vue composable provides the same API surface as the React hook, with all values returned as Vue `Ref` or `ComputedRef` objects:

```vue
<script setup lang="ts">
import { useStreaming } from 'atmosphere.js/vue';

const { fullText, isStreaming, progress, send, reset } = useStreaming(
  { url: '/ai-chat', transport: 'websocket' },
);
</script>

<template>
  <button @click="send('What is Atmosphere?')" :disabled="isStreaming">Ask</button>
  <span v-if="isStreaming">{{ progress ?? 'Generating...' }}</span>
  <p>{{ fullText }}</p>
  <button @click="reset">Clear</button>
</template>
```

`fullText` is a `computed` ref that joins streaming texts automatically. Cleanup is handled via `onUnmounted`.

### Svelte -- `createStreamingStore`

The Svelte store follows the same store contract as `createAtmosphereStore`. Use `$store` auto-subscription syntax:

```svelte
<script>
  import { createStreamingStore } from 'atmosphere.js/svelte';

  const { store, send, reset } = createStreamingStore(
    { url: '/ai-chat', transport: 'websocket' },
  );
</script>

<button on:click={() => send('What is Atmosphere?')} disabled={$store.isStreaming}>Ask</button>
{#if $store.isStreaming}
  <span>{$store.progress ?? 'Generating...'}</span>
{/if}
<p>{$store.fullText}</p>
<button on:click={reset}>Clear</button>
```

`$store.fullText`, `$store.isStreaming`, `$store.progress`, and `$store.error` update reactively. The store connects when the first subscriber appears and disconnects when all unsubscribe.

## Sample

The `samples/spring-boot-ai-chat/` sample contains the complete `AiChat` endpoint shown above, along with a browser client. Run it with:

```bash
./mvnw spring-boot:run -pl samples/spring-boot-ai-chat
```

## Summary

| Concept | Purpose |
|---------|---------|
| `@AiEndpoint` | Annotation that wires up an AI chat endpoint with streaming, lifecycle, and configuration |
| `@Prompt` | Marks the method that handles user messages (invoked on a virtual thread) |
| `StreamingSession` | SPI for pushing streaming texts to clients: `send()`, `stream()`, `complete()`, `error()` |
| `AiConfig` | Global LLM configuration (model, API key, base URL) |
| `AiInterceptor` | Pre/post processing around the prompt (cost metering, RAG, logging) |
| `AiConversationMemory` | Multi-turn conversation history per client |
| `AiGuardrail` | Safety checks before and after LLM calls |
| `ContextProvider` | RAG context augmentation |

In the [next chapter](/docs/tutorial/10-ai-tools/), you will learn about `@AiTool` -- Atmosphere's framework-agnostic annotation for declaring tools that any LLM can call.
