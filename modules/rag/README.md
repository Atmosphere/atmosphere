# Atmosphere RAG

RAG (Retrieval-Augmented Generation) module for Atmosphere. Provides the `ContextProvider` SPI with built-in implementations for in-memory, Spring AI, and LangChain4j vector stores.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-rag</artifactId>
    <version>${project.version}</version>
</dependency>
```

## How It Works

The `ContextProvider` SPI (defined in `atmosphere-ai`) retrieves relevant documents and injects them into the LLM prompt automatically during `AiStreamingSession.stream()`:

```
User message → Guardrails → ContextProvider.retrieve() → Interceptors → LLM → Response
```

Each registered provider retrieves up to 5 documents. Results are appended to the user message as `Relevant context:` blocks with source attribution before the LLM call.

This module provides three `ContextProvider` implementations:

| Implementation | Backend | Extra dependency |
|----------------|---------|------------------|
| `InMemoryContextProvider` | Word-overlap scoring (no external deps) | None |
| `SpringAiVectorStoreContextProvider` | Spring AI `VectorStore` | `spring-ai-vector-store` |
| `LangChain4jEmbeddingStoreContextProvider` | LangChain4j `ContentRetriever` | `langchain4j-core` |

## InMemoryContextProvider

Zero-dependency provider for development, testing, and small knowledge bases. Uses word-overlap scoring — no embedding model needed.

```java
// Load from classpath resources
var provider = InMemoryContextProvider.fromClasspath(
        "docs/websocket.md", "docs/sse.md", "docs/grpc.md");

// Or from explicit documents
var provider = new InMemoryContextProvider(List.of(
    new ContextProvider.Document("Atmosphere supports WebSocket...", "websocket.md", 1.0),
    new ContextProvider.Document("SSE transport enables...", "sse.md", 1.0)
));

List<ContextProvider.Document> results = provider.retrieve("How does WebSocket work?", 3);
```

## Spring AI Bridge

Bridges Spring AI's `VectorStore.similaritySearch()` to the `ContextProvider` SPI. Auto-configured when both `atmosphere-rag` and a Spring AI `VectorStore` bean are present.

```java
// Auto-configured via AtmosphereRagAutoConfiguration, or set manually:
SpringAiVectorStoreContextProvider.setVectorStore(vectorStore);
```

Add Spring AI to your dependencies:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-vector-store</artifactId>
    <version>4.0.42</version>
</dependency>
```

## LangChain4j Bridge

Bridges any LangChain4j `ContentRetriever` (including `EmbeddingStoreContentRetriever`) to the `ContextProvider` SPI.

```java
var retriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .maxResults(5)
        .build();
LangChain4jEmbeddingStoreContextProvider.setContentRetriever(retriever);
```

Add LangChain4j to your dependencies:

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-core</artifactId>
    <version>4.0.42</version>
</dependency>
```

## Using with @AiEndpoint

Context providers are auto-discovered when registered as Spring beans (via `AtmosphereRagAutoConfiguration`) or can be declared explicitly on the endpoint:

```java
@AiEndpoint(path = "/ai/rag-chat",
            systemPrompt = "Answer using the provided context.",
            contextProviders = SpringAiVectorStoreContextProvider.class)
public class RagChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // RAG context is injected automatically
    }
}
```

With Spring Boot auto-configuration, you don't even need `contextProviders` — just have a `VectorStore` bean and `atmosphere-rag` on the classpath:

```java
@AiEndpoint(path = "/ai/rag-chat",
            systemPromptResource = "prompts/system-prompt.md")
public class RagChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

## Sample

- [Spring Boot RAG Chat](../../samples/spring-boot-rag-chat/) — full RAG sample with Spring AI `SimpleVectorStore`, knowledge base loading, and demo mode

## Requirements

- Java 21+
- `atmosphere-ai` (transitive)
- Spring AI or LangChain4j (optional, for vector store bridges)
