# Atmosphere AI Module

The `atmosphere-ai` module provides built-in support for streaming LLM (Large Language Model) responses through Atmosphere's real-time infrastructure.

## @AiEndpoint

The `@AiEndpoint` annotation marks a class as an AI-enabled endpoint. It combines the functionality of `@ManagedService` with AI-specific features:

```java
@AiEndpoint(path = "/atmosphere/chat",
        systemPromptResource = "prompts/system-prompt.md")
public class Chat {
    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

## StreamingSession

`StreamingSession` is the interface for sending streaming text to the client:
- `stream(message)` — sends the message through the full AI pipeline (RAG, guardrails, interceptors)
- `send(text)` — sends a single streaming text chunk to the client
- `complete()` — signals that streaming is finished
- `error(throwable)` — signals an error
- `progress(message)` — sends a progress/status message

## AI Pipeline

When `session.stream(message)` is called, the request passes through:
1. **Guardrails** — inspect and optionally block or modify the request
2. **Context Providers (RAG)** — retrieve relevant documents and augment the prompt
3. **Interceptors** — pre-process the request
4. **LLM Call** — the actual model invocation
5. **Post-processing** — interceptors and guardrails inspect the response

## Context Providers (RAG)

The `ContextProvider` interface enables RAG (Retrieval-Augmented Generation):

```java
public interface ContextProvider {
    List<Document> retrieve(String query, int maxResults);
}
```

Implementations include:
- `InMemoryContextProvider` — simple word-overlap scoring, no external dependencies
- `SpringAiVectorStoreContextProvider` — bridges Spring AI VectorStore
- `LangChain4jEmbeddingStoreContextProvider` — bridges LangChain4j ContentRetriever

## Supported AI Providers

Through `AgentRuntime` implementations (the unified AI SPI — six runtimes share a common baseline: tool calling, structured output, progress events, usage metadata):
- **Spring AI** (`atmosphere-spring-ai`) — integrates with Spring AI ChatClient
- **LangChain4j** (`atmosphere-langchain4j`) — integrates with LangChain4j streaming models
- **Google ADK** (`atmosphere-adk`) — Google Agent Development Kit
- **Embabel** (`atmosphere-embabel`) — Embabel Agent Framework
- **JetBrains Koog** (`atmosphere-koog`) — Koog multi-provider runtime
- **Built-in** — OpenAI-compatible client that works with Gemini, OpenAI, Ollama, etc. (full tool calling)
