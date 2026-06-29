# Spring Boot AI Chat Sample

A real-time AI chat application that streams LLM responses text-by-text to the browser using Atmosphere's built-in `OpenAiCompatibleClient`. Works with **Gemini**, **OpenAI**, **Ollama**, and any OpenAI-compatible endpoint.

## Key Features

- **`@AiEndpoint`** — declarative AI endpoint with system prompt, capability validation, and conversation memory
- **Capability requirements** — `requires = {TEXT_STREAMING, SYSTEM_PROMPT}` fails fast if the backend can't deliver
- **Conversation memory** — multi-turn context preserved automatically per client
- **Structured events** — `AiEvent` wire protocol for tool calls, agent steps, and structured output
- **Demo mode** — works out-of-the-box without an API key (simulated streaming)
- **Prompt cache demo** — `PromptCacheDemoChat` at `/atmosphere/ai-chat-with-cache` shows how `@AiEndpoint(promptCache = CONSERVATIVE)` threads a `CacheHint` into every request; the sample routes prompts through a real `AiPipeline` + `InMemoryResponseCache` so the framework emits `ai.cache.hit=false` on the first request and `ai.cache.hit=true` on repeated identical prompts (canonical framework-level wire signal, not a sample shim)
- **Retry policy demo** — `RetryDemoChat` at `/atmosphere/ai-chat-with-retry` echoes the declared `@AiEndpoint(retry = @Retry(...))` attributes and exposes a deterministic `fail-once:<id>` fault-injection path that recovers on a second request
- **Multi-modal demo** — `MultiModalChat` at `/atmosphere/ai-chat-multimodal` accepts both vision and audio input:
  - **Vision** — `image:<base64>` prompts are wrapped in a `Content.Image` and streamed back as a binary content frame next to a text acknowledgement.
  - **Audio input** — `audio:<base64>` prompts are wrapped in a `Content.Audio` and forwarded to the resolved AI runtime as a multi-modal **input** part via `session.stream(prompt, parts)`. The runtime encodes it onto the provider wire request (the built-in OpenAI-compatible client emits an `input_audio` content block), so an audio-capable model such as `gpt-4o-audio-preview` receives the clip. With no API key the demo runtime returns a canned reply, but the audio still reaches the runtime context. Override the media type with `audio:audio/<subtype>:<base64>` (default `audio/wav`).
  - The delivery test `MultiModalAudioInputDeliveryTest` proves the audio reaches the runtime by asserting the captured `AgentExecutionContext.parts()` contains the `Content.Audio` with the right media type.
  - A minimal picker page is served at `/multimodal.html`.

## How It Works

### Server — `AiChat.java`

An `@AiEndpoint` at `/atmosphere/ai-chat`:

1. Client connects via WebSocket and sends a prompt
2. The `@Prompt` handler calls `session.stream(message)` which routes through the `AgentRuntime` SPI
3. The framework handles conversation memory, interceptors, guardrails, and streaming automatically
4. Each streaming text is pushed to the client as a JSON frame

```java
@AiEndpoint(path = "/atmosphere/ai-chat",
        systemPromptResource = "skill:ai-assistant",
        requires = {AiCapability.TEXT_STREAMING, AiCapability.SYSTEM_PROMPT},
        conversationMemory = true)
public class AiChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

### Client — React + atmosphere.js

Uses the `useChat` hook from `atmosphere.js/react`:

- Connects to `/atmosphere/ai-chat` over WebSocket
- Parses streaming JSON messages and `AiEvent` frames
- Keeps optimistic user and assistant message state in one hook
- Renders streaming texts as they arrive with markdown support
- Shows model name, cost, and latency badges

## Configuration

Set environment variables before running:

```bash
# Gemini (default)
export LLM_API_KEY=AIza...

# OpenAI
export LLM_MODEL=gpt-4o-mini
export LLM_BASE_URL=https://api.openai.com/v1
export LLM_API_KEY=sk-...

# Ollama (local)
export LLM_MODE=local
export LLM_MODEL=llama3.2
```

## Build & Run

```bash
# From the repository root
./mvnw spring-boot:run -pl samples/spring-boot-ai-chat

# Or via the CLI
atmosphere run spring-boot-ai-chat
```

Open http://localhost:8080 in your browser. The AI Console UI is bundled at
`/atmosphere/console/` (the root path redirects there).

## Authentication

Token-based authentication is **disabled by default** in this sample
(`atmosphere.auth.enabled=false` in `application.properties`) so the bundled
AI Console connects out-of-the-box. The framework default is fail-closed
per Correctness Invariant #6 — the sample-level override is explicit.

To demo the bundled `AuthConfig` token flow, run with auth enabled:

```bash
./mvnw spring-boot:run -pl samples/spring-boot-ai-chat \
    -Dspring-boot.run.arguments="--atmosphere.auth.enabled=true"
```

Then mint a token and use it on the handshake:

```bash
# 1. Mint a demo token
curl -s -X POST http://localhost:8080/api/auth/login \
     -H 'Content-Type: application/json' -d '{"user":"demo"}'
# -> {"token":"demo-token"}

# 2. Use it as a header
curl -i -H 'X-Atmosphere-Auth: demo-token' http://localhost:8080/atmosphere/ai-chat

# Or as a query parameter (works for WebSocket too)
curl -i 'http://localhost:8080/atmosphere/ai-chat?X-Atmosphere-Auth=demo-token'
```

Without `X-Atmosphere-Auth` (and with auth enabled), the handshake returns
`HTTP 401 X-Atmosphere-error: No authentication token provided`.

## Project Structure

```
spring-boot-ai-chat/
├── pom.xml
├── frontend/                        # React + Vite frontend
│   └── src/
│       ├── App.tsx                  # Chat UI with useChat hook
│       └── main.tsx                 # AtmosphereProvider wrapper
└── src/main/
    ├── java/.../aichat/
    │   ├── AiChatApplication.java   # Spring Boot entry point
    │   ├── AiChat.java             # @AiEndpoint with capability validation
    │   ├── AuthConfig.java         # Token-based authentication
    │   ├── DemoResponseProducer.java # Simulated streaming for demo mode
    │   └── LlmConfig.java          # Spring properties → AiConfig bridge
    └── resources/
        ├── application.yml          # LLM config (model, mode, API key)
        └── static/                  # Built frontend assets
```

## See Also

- [AI Tools sample](../spring-boot-ai-tools/) — framework-agnostic tool calling with real-time tool events
- [Dentist agent](../spring-boot-dentist-agent/) — full `@Agent` with commands, tools, and multi-channel
- [Multi-agent startup team](../spring-boot-multi-agent-startup-team/) — 5 agents collaborating via A2A
