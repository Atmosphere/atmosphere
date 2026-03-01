# Atmosphere Documentation

Atmosphere is a transport-agnostic real-time framework for the JVM. Write to a Broadcaster, and the framework delivers to every subscriber -- whether they're on a WebSocket, SSE stream, long-polling loop, gRPC channel, or MCP session.

- [**What's New in 4.0**](whats-new-4.0.md)

## Core

- [**Core Runtime**](core.md) -- Broadcaster, AtmosphereResource, `@ManagedService`, transport negotiation
- [**Rooms & Presence**](rooms.md) -- Room management, join/leave, presence tracking, message history, AI virtual members

## Framework Integration

- [**Spring Boot**](spring-boot.md) -- Auto-configuration for Spring Boot 4.0+, gRPC transport, native image
- [**Quarkus**](quarkus.md) -- Build-time processing for Quarkus 3.21+, native image

## AI / LLM

- [**AI Core**](ai.md) -- `AiSupport` SPI, `@AiEndpoint`, filters, routing, conversation memory
- [**Spring AI Adapter**](spring-ai.md) -- `AiSupport` backed by Spring AI `ChatClient`
- [**LangChain4j Adapter**](langchain4j.md) -- `AiSupport` backed by LangChain4j `StreamingChatLanguageModel`
- [**Google ADK Adapter**](adk.md) -- `AiSupport` backed by Google ADK `Runner`
- [**Embabel Adapter**](embabel.md) -- `AiSupport` backed by Embabel `AgentPlatform`

## Protocols

- [**gRPC Transport**](grpc.md) -- Bidirectional streaming via grpc-java
- [**MCP Server**](mcp.md) -- Model Context Protocol server over WebSocket, SSE, or Streamable HTTP

## Client Libraries

- [**atmosphere.js (TypeScript)**](client-javascript.md) -- Browser client with React, Vue, and Svelte hooks
- [**wAsync (Java)**](client-java.md) -- Async Java client for WebSocket, SSE, streaming, long-polling, gRPC

## Infrastructure

- [**Redis Clustering**](redis.md) -- Cross-node broadcasting via Redis pub/sub
- [**Kafka Clustering**](kafka.md) -- Cross-node broadcasting via Kafka
- [**Durable Sessions**](durable-sessions.md) -- Session persistence across restarts (InMemory, SQLite, Redis)

## Extensions

- [**Kotlin DSL**](kotlin.md) -- Builder API and coroutine extensions
- [**Observability**](observability.md) -- Micrometer metrics, OpenTelemetry tracing, backpressure

## Additional Resources

- [Samples](../samples/) -- Runnable apps covering every transport and integration
- [Javadoc](http://atmosphere.github.io/atmosphere/apidocs/)
- [GitHub](https://github.com/Atmosphere/atmosphere)
