# Spring Boot AI Chat Sample

A real-time AI chat application that streams LLM responses text-by-text to the browser using Atmosphere's built-in `OpenAiCompatibleClient`. Works with **Gemini**, **OpenAI**, **Ollama**, and any OpenAI-compatible endpoint.

## Key Features

- **`@AiEndpoint`** — declarative AI endpoint with system prompt, capability validation, and conversation memory
- **Capability requirements** — `requires = {TEXT_STREAMING, SYSTEM_PROMPT}` fails fast if the backend can't deliver
- **Conversation memory** — multi-turn context preserved automatically per client
- **Structured events** — `AiEvent` wire protocol for tool calls, agent steps, and structured output
- **Demo mode** — works out-of-the-box without an API key (simulated streaming)
- **Retry policy demo** — `RetryDemoChat` at `/atmosphere/ai-chat-with-retry` echoes the declared `@AiEndpoint(retry = @Retry(...))` attributes and exposes a deterministic `fail-once:<id>` fault-injection path that recovers on a second request
- **Multi-modal demo** — `MultiModalChat` at `/atmosphere/ai-chat-multimodal` accepts `image:<base64>` prompts, wraps them in a `Content.Image`, and streams a binary content frame next to a text acknowledgement. A minimal picker page is served at `/multimodal.html`

## How It Works

### Server — `AiChat.java`

An `@AiEndpoint` at `/atmosphere/ai-chat`:

1. Client connects via WebSocket and sends a prompt
2. The `@Prompt` handler calls `session.stream(message)` which routes through the `AgentRuntime` SPI
3. The framework handles conversation memory, interceptors, guardrails, and streaming automatically
4. Each streaming text is pushed to the client as a JSON frame

```java
@AiEndpoint(path = "/atmosphere/ai-chat",
        systemPromptResource = "prompts/system-prompt.md",
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

Uses the `useStreaming` hook from `atmosphere.js/react`:

- Connects to `/atmosphere/ai-chat` over WebSocket
- Parses streaming JSON messages and `AiEvent` frames
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

Open http://localhost:8080 in your browser.

## Project Structure

```
spring-boot-ai-chat/
├── pom.xml
├── frontend/                        # React + Vite frontend
│   └── src/
│       ├── App.tsx                  # Chat UI with useStreaming hook
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
        ├── prompts/system-prompt.md # System prompt loaded at startup
        └── static/                  # Built frontend assets
```

## See Also

- [AI Tools sample](../spring-boot-ai-tools/) — framework-agnostic tool calling with real-time tool events
- [Dentist agent](../spring-boot-dentist-agent/) — full `@Agent` with commands, tools, and multi-channel
- [Multi-agent startup team](../spring-boot-multi-agent-startup-team/) — 5 agents collaborating via A2A
