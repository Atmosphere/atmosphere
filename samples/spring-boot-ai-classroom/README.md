# AI Classroom

**Real-time collaborative AI streaming** — multiple clients in the same room see the AI response stream token-by-token, simultaneously.

This sample demonstrates what makes Atmosphere unique: **broadcasting streamed LLM tokens to multiple connected clients** using a single `@AiEndpoint` annotation.

## Architecture

```
┌──────────┐   ┌──────────┐   ┌──────────┐
│ Student A│   │ Student B│   │ Student C│
└────┬─────┘   └────┬─────┘   └────┬─────┘
     │              │              │
     │  WebSocket   │  WebSocket   │  WebSocket
     │              │              │
     └──────────────┼──────────────┘
                    │
           ┌────────▼────────┐
           │   Atmosphere    │
           │   Broadcaster   │  ← All connected clients share this
           └────────┬────────┘
                    │
           ┌────────▼────────┐
           │  AiClassroom    │  @AiEndpoint + @Prompt
           │  + Interceptor  │  RoomContextInterceptor sets persona
           └────────┬────────┘
                    │
           ┌────────▼────────┐
           │    AiSupport    │  Pluggable backend (built-in, Spring AI,
           │   (auto-detect) │  LangChain4j, ADK — zero code change)
           └─────────────────┘
```

## Key Code

**The endpoint (6 lines of meaningful code):**

```java
@AiEndpoint(path = "/atmosphere/classroom",
        systemPromptResource = "prompts/classroom-prompt.md",
        interceptors = { RoomContextInterceptor.class })
public class AiClassroom {

    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        session.stream(message);  // Works with ANY AiSupport backend
    }
}
```

**The interceptor (sets persona per room):**

```java
public class RoomContextInterceptor implements AiInterceptor {
    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        var room = resource.getRequest().getParameter("room");
        var systemPrompt = ROOM_PROMPTS.getOrDefault(room, DEFAULT_PROMPT);
        return request.withSystemPrompt(systemPrompt);
    }
}
```

## Running

The easiest way to run with a real AI model is via [Embacle](https://github.com/dravr-ai/dravr-embacle), which turns your existing Claude Code, Copilot, Cursor, or Gemini CLI license into an OpenAI-compatible LLM provider — no separate API key required.

### With Embacle (recommended)

```bash
# 1. Start Embacle (see https://github.com/dravr-ai/dravr-embacle)
#    It runs on http://localhost:3000/v1

# 2. Start the classroom with Embacle as the backend
LLM_BASE_URL=http://localhost:3000/v1 LLM_API_KEY=embacle LLM_MODEL=copilot:claude-sonnet-4.6 \
  ./mvnw spring-boot:run -pl samples/spring-boot-ai-classroom

# Open http://localhost:8080 in MULTIPLE browser tabs
# Join the same room, send a question — all tabs stream simultaneously
```

### With other providers

```bash
# Gemini
export LLM_API_KEY=AIza...
export LLM_MODEL=gemini-2.5-flash

# OpenAI
export LLM_API_KEY=sk-...
export LLM_MODEL=gpt-4o-mini
export LLM_BASE_URL=https://api.openai.com/v1

# Local Ollama
export LLM_MODE=local
export LLM_MODEL=llama3.2
```

Without any API key or Embacle, the sample runs in **demo mode** with simulated streaming responses.

## Rooms

| Room | Persona | Query Parameter |
|------|---------|-----------------|
| Math | Mathematics tutor | `?room=math` |
| Code | Programming mentor | `?room=code` |
| Science | Science educator | `?room=science` |
| (default) | General assistant | `?room=` or omitted |

## Portability

The `session.stream(message)` call is **framework-agnostic**. To switch AI backends:

| Backend | What to do |
|---------|-----------|
| Built-in (OpenAI-compatible) | Default — just set `LLM_API_KEY` |
| Spring AI | Add `atmosphere-spring-ai` dependency |
| LangChain4j | Add `atmosphere-langchain4j` dependency |
| Google ADK | Add `atmosphere-adk` dependency |

**Zero code changes.** The `AiSupport` SPI auto-detects the best available backend via `ServiceLoader`.
