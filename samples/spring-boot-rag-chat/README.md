# Atmosphere RAG Chat Sample

Real-time chat with Retrieval-Augmented Generation (RAG) using Atmosphere Framework, Spring AI, and a SimpleVectorStore.

## What It Does

This sample demonstrates how to build a RAG-powered chatbot that answers questions about the Atmosphere Framework by:

1. Loading documentation files into a Spring AI `SimpleVectorStore` at startup
2. Using `atmosphere-rag` to automatically retrieve relevant documents when a user asks a question
3. Augmenting the LLM prompt with the retrieved context
4. Streaming the response back to the browser in real time via WebSocket/SSE

## Architecture

```
Browser (atmosphere.js)
    |
    v
@AiEndpoint (RagChat.java)
    |
    v
ContextProvider (SpringAiVectorStoreContextProvider)
    |  -- retrieves relevant docs from SimpleVectorStore
    v
LLM (via Spring AI ChatClient)
    |  -- generates response grounded in context
    v
StreamingSession -> Browser
```

## How to Run

### Without API Key (Demo Mode)

```bash
cd samples/spring-boot-rag-chat
../../mvnw spring-boot:run
```

Open http://localhost:8080. The app runs in demo mode with simulated responses.

### With Gemini API Key

```bash
export LLM_API_KEY=your-gemini-api-key
cd samples/spring-boot-rag-chat
../../mvnw spring-boot:run
```

### With OpenAI API Key

```bash
export LLM_MODE=remote
export LLM_MODEL=gpt-4o-mini
export LLM_BASE_URL=https://api.openai.com/v1
export LLM_API_KEY=sk-your-openai-key
cd samples/spring-boot-rag-chat
../../mvnw spring-boot:run
```

## Key Files

| File | Description |
|------|-------------|
| `RagChat.java` | `@AiEndpoint` that handles user prompts |
| `VectorStoreConfig.java` | Creates `SimpleVectorStore` and loads knowledge base docs |
| `LlmConfig.java` | Bridges Spring properties to `AiConfig` |
| `docs/*.md` | Knowledge base documents about Atmosphere |
| `prompts/rag-system-prompt.md` | System prompt instructing the LLM to use provided context |

## Knowledge Base

The sample includes four documentation files in `src/main/resources/docs/`:

- `atmosphere-overview.md` — Framework overview and key features
- `atmosphere-transports.md` — Transport protocols (WebSocket, SSE, long-polling, gRPC)
- `atmosphere-ai-module.md` — AI module documentation (@AiEndpoint, StreamingSession, ContextProvider)
- `atmosphere-getting-started.md` — Getting started guide with Maven dependencies and examples
