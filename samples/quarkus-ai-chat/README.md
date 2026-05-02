# Quarkus AI Chat

Minimal Quarkus app that exposes an Atmosphere `@AiEndpoint` over WebSocket,
streaming LLM tokens through `atmosphere-quarkus-langchain4j` → Quarkus
LangChain4j → an OpenAI-compatible provider (Gemini's compat endpoint by
default; works with any provider Quarkus LangChain4j supports).

## Run

The sample defaults to Gemini's OpenAI-compatible endpoint, but any
OpenAI-compatible provider works.

```bash
export GEMINI_API_KEY=ya29...   # or LLM_API_KEY
cd samples/quarkus-ai-chat
mvn quarkus:dev
```

Open http://localhost:18810/ — type a message, watch the assistant stream a
reply token-by-token.

To swap providers, override the application properties:

```bash
LLM_API_KEY=sk-...                           \
LLM_BASE_URL=https://api.openai.com/v1/      \
LLM_MODEL=gpt-4o-mini                        \
mvn quarkus:dev
```

## What's in the box

| File | Role |
|------|------|
| `AiChat.java` | `@AiEndpoint` handler — `@Prompt onPrompt(message, session)` calls `session.stream(message)` |
| `GeminiCompatCustomizer.java` | Drops `frequency_penalty` / `presence_penalty` from the OpenAI request — Gemini's compat endpoint rejects unknown fields. Delete for OpenAI proper. |
| `application.properties` | `quarkus.atmosphere.packages` + `quarkus.langchain4j.openai.*` |
| `META-INF/resources/index.html` | Vanilla HTML client using `atmosphere.js@5` from jsDelivr — `WebSocket` transport with `long-polling` fallback |

## How streaming flows end-to-end

```
browser (atmosphere.js)
   │   prompt over WebSocket
   ▼
QuarkusAtmosphereServlet (atmosphere-quarkus-extension)
   │   AiPipeline → @AiEndpoint dispatch
   ▼
AiChat.onPrompt(message, session)
   │   session.stream(message)
   ▼
LangChain4jAgentRuntime
   │   uses model installed by AtmosphereQuarkusLangChain4jBridge at startup
   ▼
Quarkus LangChain4j StreamingChatModel
   │   SSE stream from provider
   ▼
AtmosphereStreamingResponseHandler.onPartialResponse(token)
   │   session.send(token)
   ▼
DefaultBroadcaster → WebSocket frame → atmosphere.js → DOM
```

## Verifying the bridge

The startup log includes:

```
Auto-wired Quarkus LangChain4j StreamingChatModel (...) into LangChain4jAgentRuntime
AI endpoint registered at /atmosphere/ai-chat (... runtime: langchain4j ...)
Installed features: [atmosphere, atmosphere-quarkus-langchain4j, langchain4j, langchain4j-openai, ...]
```

A `Reply with QUARKUS_OK only.` prompt should round-trip and render
`< QUARKUS_OK` in the chat log.
