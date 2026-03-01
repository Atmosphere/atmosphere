<p align="center">
  <img src="logo.png" alt="Atmosphere" width="120"/>
</p>

<h1 align="center">Atmosphere</h1>

<p align="center">
  <strong>The transport-agnostic real-time framework for the JVM.</strong><br/>
  WebSocket, SSE, Long-Polling, gRPC, MCP — one API, any transport.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/org.atmosphere/atmosphere-runtime"><img src="https://img.shields.io/maven-central/v/org.atmosphere/atmosphere-runtime?label=Maven%20Central&color=blue" alt="Maven Central"/></a>
  <a href="https://www.npmjs.com/package/atmosphere.js"><img src="https://img.shields.io/npm/v/atmosphere.js?label=atmosphere.js&color=blue" alt="npm"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml/badge.svg?branch=main" alt="Atmosphere CI"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-js-ci.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-js-ci.yml/badge.svg?branch=main" alt="Atmosphere.js CI"/></a>
</p>

---

Atmosphere was built on one idea: **your application code shouldn't care how the client is connected.** Write to a Broadcaster, and the framework delivers to every subscriber — whether they're on a WebSocket, an SSE stream, a long-polling loop, a gRPC channel, or an MCP session. The transport is pluggable and transparent.

The two core abstractions are **Broadcaster** (a named pub/sub channel) and **AtmosphereResource** (a single connection). Additional modules — rooms, AI/LLM streaming, clustering, observability — build on top of these.

## Quick Start

```java
@ManagedService(path = "/chat")
public class Chat {

    @Ready
    public void onReady(AtmosphereResource r) {
        // r could be WebSocket, SSE, Long-Polling, gRPC, or MCP — doesn't matter
        log.info("{} connected via {}", r.uuid(), r.transport());
    }

    @Message(encoders = JacksonEncoder.class, decoders = JacksonDecoder.class)
    public ChatMessage onMessage(ChatMessage message) {
        // Return value is broadcast to all subscribers
        return message;
    }
}
```

## What's New in 4.0

- **AI / LLM streaming** — `@AiEndpoint` + pluggable `AiSupport` SPI auto-detects Spring AI, LangChain4j, Google ADK, or Embabel from the classpath. Conversation memory, RAG interceptors, cost/latency routing, and fan-out streaming are built in.
- **MCP server** — expose tools, resources, and prompt templates to AI agents with `@McpServer` / `@McpTool` annotations over Streamable HTTP, WebSocket, or SSE.
- **gRPC transport** — bidirectional streaming alongside WebSocket/SSE/Long-Polling on the same Broadcaster.
- **Virtual threads** — enabled by default on JDK 21+. `ReentrantLock` replaces `synchronized` to avoid pinning.
- **Rooms & presence** — `RoomManager` with join/leave, presence events, message history, and AI virtual members.
- **Clustering** — Redis and Kafka broadcasters for multi-node deployments.
- **Durable sessions** — survive restarts with InMemory, SQLite, or Redis-backed session stores.
- **Spring Boot 4.0 / Quarkus 3.21** — first-class starters with auto-configuration, native image support, and observability (Micrometer + OpenTelemetry).
- **atmosphere.js 5.0** — TypeScript client with React, Vue, and Svelte hooks for chat, rooms, presence, and AI streaming.
- **wAsync 4.0** — Java client rewritten on `java.net.http` with gRPC support.

### AI / LLM — two annotations, zero boilerplate

```java
@AiEndpoint(path = "/ai/chat",
            systemPrompt = "You are a helpful assistant",
            conversationMemory = true)
public class AiChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // auto-detects the AI framework from the classpath
    }
}
```

### MCP — expose tools to AI agents

```java
@McpServer(name = "my-tools", path = "/atmosphere/mcp")
public class MyTools {

    @McpTool(name = "ask_ai", description = "Ask AI and stream the answer to browsers")
    public String askAi(
            @McpParam(name = "question") String question,
            @McpParam(name = "topic") String topic,
            StreamingSession session) {
        session.stream(question);  // tokens broadcast to all clients on the topic
        return "streaming to " + topic;
    }
}
```

## Modules

| Module | Artifact | What it does |
|--------|----------|--------------|
| [**Core**](docs/core.md) | `atmosphere-runtime` | WebSocket, SSE, Long-Polling (Servlet 6.0+) |
| [**gRPC**](docs/grpc.md) | `atmosphere-grpc` | Bidirectional streaming transport (grpc-java 1.71) |
| [**Spring Boot**](docs/spring-boot.md) | `atmosphere-spring-boot-starter` | Auto-configuration for Spring Boot 4.0+ |
| [**Quarkus**](docs/quarkus.md) | `atmosphere-quarkus-extension` | Build-time processing for Quarkus 3.21+ |
| [**AI core**](docs/ai.md) | `atmosphere-ai` | `AiSupport` SPI, `@AiEndpoint`, filters, routing, conversation memory |
| [**Spring AI adapter**](docs/spring-ai.md) | `atmosphere-spring-ai` | `AiSupport` backed by Spring AI `ChatClient` |
| [**LangChain4j adapter**](docs/langchain4j.md) | `atmosphere-langchain4j` | `AiSupport` backed by LangChain4j `StreamingChatLanguageModel` |
| [**Google ADK adapter**](docs/adk.md) | `atmosphere-adk` | `AiSupport` backed by Google ADK `Runner` |
| [**Embabel adapter**](docs/embabel.md) | `atmosphere-embabel` | `AiSupport` backed by Embabel `AgentPlatform` |
| [**MCP server**](docs/mcp.md) | `atmosphere-mcp` | Model Context Protocol server over WebSocket |
| [**Rooms**](docs/rooms.md) | built into core | Room management with join/leave and presence |
| [**Redis clustering**](docs/redis.md) | `atmosphere-redis` | Cross-node broadcasting via Redis pub/sub |
| [**Kafka clustering**](docs/kafka.md) | `atmosphere-kafka` | Cross-node broadcasting via Kafka |
| [**Durable sessions**](docs/durable-sessions.md) | `atmosphere-durable-sessions` | Session persistence across restarts (SQLite / Redis) |
| [**Kotlin DSL**](docs/kotlin.md) | `atmosphere-kotlin` | Builder API and coroutine extensions |
| [**TypeScript client**](docs/client-javascript.md) | `atmosphere.js` (npm) | Browser client with React, Vue, and Svelte hooks |
| [**Java client**](docs/client-java.md) | `atmosphere-wasync` | Async Java client — WebSocket, SSE, streaming, long-polling, gRPC (JDK 21+) |

## Requirements

| Java | Spring Boot | Quarkus |
|------|-------------|---------|
| 21+  | 4.0.2+      | 3.21+   |

JDK 21 virtual threads are used by default.

## Documentation

- [**Full documentation**](docs/README.md) — architecture, configuration, and API reference for every module
- [**Samples**](samples/) — runnable apps covering every transport and integration
- [**Javadoc**](http://atmosphere.github.io/atmosphere/apidocs/)

## Commercial Support

Available via [Async-IO.org](https://async-io.org)

## License

Apache 2.0 — @Copyright 2008-2026 [Async-IO.org](https://async-io.org)
