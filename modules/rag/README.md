# Atmosphere RAG

RAG (Retrieval-Augmented Generation) module for Atmosphere. Provides the
`ContextProvider` SPI with six built-in implementations and a reachability
matrix that extends to every vector store Spring AI or LangChain4j supports.

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

This module provides six `ContextProvider` implementations:

| Implementation | Backend | Extra dependency |
|----------------|---------|------------------|
| `InMemoryContextProvider` | Word-overlap scoring (no external deps) | None |
| `SpringAiVectorStoreContextProvider` | Spring AI `VectorStore` (Pinecone, pgvector, Weaviate, Milvus, Chroma, …) | `spring-ai-vector-store` |
| `LangChain4jEmbeddingStoreContextProvider` | LangChain4j `EmbeddingStore` (Chroma, Elasticsearch, Pinecone, Weaviate, …) | `langchain4j-core` |
| `PgVectorContextProvider` | Postgres + `pgvector` extension via JDBC (zero vendor SDK) | None (a `DataSource` + `EmbeddingRuntime`) |
| `QdrantContextProvider` | Qdrant REST API (zero vendor SDK) | None (uses `java.net.http.HttpClient`) |
| `PineconeContextProvider` | Pinecone REST API (zero vendor SDK) | None (uses `java.net.http.HttpClient`) |

### Vector-store reachability

The Spring AI and LangChain4j bridges multiply: every backend either of those
ecosystems supports is reachable through Atmosphere with one config bean. The
table below shows the practical reachability matrix today — the three direct
connectors give zero-dep paths for the three most-asked stores; the bridges
cover the long tail without adding a second framework to the classpath.

| Vector store | Direct connector | Spring AI bridge | LangChain4j bridge |
|--------------|:---------------:|:----------------:|:------------------:|
| Postgres / pgvector | ✅ `PgVectorContextProvider` | ✅ | ✅ |
| Qdrant | ✅ `QdrantContextProvider` | ✅ | ✅ |
| Pinecone | ✅ `PineconeContextProvider` | ✅ | ✅ |
| Weaviate | — | ✅ | ✅ |
| Milvus | — | ✅ | ✅ |
| Chroma | — | ✅ | ✅ |
| Elasticsearch | — | ✅ | ✅ |
| Redis Stack | — | ✅ | ✅ |
| MongoDB Atlas Vector Search | — | ✅ | — |
| OpenSearch | — | ✅ | ✅ |
| Cassandra | — | ✅ | ✅ |

It also ships `RagChunker`, a zero-dependency ingestion helper that splits large
documents into bounded, overlapping chunks before they enter a vector store or
the in-memory provider. Chunk metadata preserves the original source document,
chunk index, total chunk count, and character offsets for attribution.

## Direct connectors

The three direct connectors (`PgVectorContextProvider`,
`QdrantContextProvider`, `PineconeContextProvider`) all share the same shape:
embed the user query through an injected `EmbeddingRuntime`, then talk to the
backend over its native protocol (JDBC for pgvector; REST for Qdrant and
Pinecone). None of them depend on Spring AI or LangChain4j, so they are the
right choice for lean apps that want a single vector store and no
framework-coupling.

```java
// pgvector — Postgres with the pgvector extension
var pg = PgVectorContextProvider.builder(dataSource, embeddingRuntime)
    .table("documents")
    .embeddingColumn("embedding")
    .contentColumn("content")
    .sourceColumn("source")
    .build();

// Qdrant — REST API
var qdrant = QdrantContextProvider.builder(
        "https://qdrant.example.com:6333", "atmosphere-docs", embeddingRuntime)
    .apiKey(System.getenv("QDRANT_API_KEY"))
    .build();

// Pinecone — REST API
var pinecone = PineconeContextProvider.builder(
        "atm-docs-abc.svc.us-east-1.pinecone.io",
        System.getenv("PINECONE_API_KEY"),
        embeddingRuntime)
    .namespace("docs")
    .build();
```

Each connector validates caller-controlled identifiers at construction time
(table / column names for pgvector, collection name for Qdrant, host name for
Pinecone) per Correctness Invariant #4 (Boundary Safety) so a misconfigured
runtime fails fast at startup instead of leaking a path-injection vector at
query time.

## InMemoryContextProvider

Zero-dependency provider for development, testing, and small knowledge bases. Uses word-overlap scoring — no embedding model needed.

```java
// Load from classpath resources
var provider = InMemoryContextProvider.fromClasspath(
        "docs/websocket.md", "docs/sse.md", "docs/grpc.md");

// Or chunk larger resources before retrieval
var chunked = InMemoryContextProvider.fromClasspathChunked(
        1_200, 150, "docs/manual.md", "docs/architecture.md");

// Or from explicit documents
var provider = new InMemoryContextProvider(List.of(
    new ContextProvider.Document("Atmosphere supports WebSocket...", "websocket.md", 1.0),
    new ContextProvider.Document("SSE transport enables...", "sse.md", 1.0)
));

List<ContextProvider.Document> results = provider.retrieve("How does WebSocket work?", 3);
```

## Chunking for Production RAG

Vector stores retrieve chunks, not books. Use `RagChunker` during ingestion so a
single long Markdown file does not dominate retrieval or bury the exact passage
the model needs:

```java
var sourceDocs = List.of(
    new ContextProvider.Document(markdown, "docs/operations.md", 1.0));

var chunks = RagChunker.chunkAll(sourceDocs); // 1,200 chars, 150-char overlap
vectorStore.add(chunks.stream()
    .map(d -> new Document(d.content(), Map.of(
        "source", d.source(),
        "source_document", d.metadata().get("source_document"),
        "chunk_index", d.metadata().get("chunk_index"))))
    .toList());
```

The Spring AI and LangChain4j bridges now return an empty result for blank
queries or non-positive result limits before invoking the backend. That keeps
boundary behavior deterministic across the in-memory and vector-store paths.

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
    <version>4.0.47</version>
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
    <version>4.0.47</version>
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
