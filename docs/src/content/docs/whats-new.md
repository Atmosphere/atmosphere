---
title: "What's New in 4.0"
description: "New features in Atmosphere 4.0"
---

# What's New in 4.0

## AI / LLM Platform

- **`@AiEndpoint` + pluggable `AiSupport` SPI** — auto-detects Spring AI, LangChain4j, Google ADK, or Embabel from the classpath. Conversation memory, RAG interceptors, cost/latency routing, and fan-out streaming are built in.
- **`@AiTool` framework-agnostic tool calling** — declare tools once with `@AiTool`/`@Param`, portable across all AI backends via automatic bridge adapters (`SpringAiToolBridge`, `LangChain4jToolBridge`, `AdkToolBridge`).
- **Per-endpoint model override** — `@AiEndpoint(model = "gpt-4o")` selects a model per endpoint without changing global config.
- **Multi-backend routing** — `@AiEndpoint(fallbackStrategy = FAILOVER)` wires `DefaultModelRouter` for failover, round-robin, or content-based routing across multiple AI backends.
- **AiMetrics SPI** — `MetricsCapturingSession` records first-streaming-text latency, streaming text usage, and errors via a pluggable `AiMetrics` interface (ServiceLoader-discovered).
- **Auto-detect ConversationPersistence** — `conversationMemory = true` discovers Redis or SQLite backends via ServiceLoader; falls back to in-memory.
- **Broadcast filter auto-registration** — `@AiEndpoint(filters = {CostMeteringFilter.class})` wires filters without manual Broadcaster config.
- **Cache replay coalescing** — reconnecting clients receive coalesced missed streaming texts in a single batch.
- **Streaming text budget management** — `StreamingTextBudgetManager` enforces per-session and per-endpoint streaming text limits.
- **Cost metering UI** — per-message cost badges in the chat frontend via `StreamingSession` request attribute.

## MCP (Model Context Protocol)

- **MCP server** — expose tools, resources, and prompt templates to AI agents with `@McpServer` / `@McpTool` annotations over Streamable HTTP, WebSocket, or SSE.
- **OpenTelemetry tracing** — `McpTracing` auto-instruments tool/resource/prompt calls with span propagation.

## Core Transport

- **gRPC transport** — bidirectional streaming alongside WebSocket/SSE/Long-Polling on the same Broadcaster.
- **Virtual threads** — enabled by default on JDK 21+. `ReentrantLock` replaces `synchronized` to avoid pinning.
- **Rooms & presence** — `RoomManager` with join/leave, presence events, message history, and AI virtual members.

## Cloud & Persistence

- **Clustering** — Redis and Kafka broadcasters for multi-node deployments.
- **Durable sessions** — survive restarts with InMemory, SQLite, or Redis-backed session stores.

## Integrations

- **Spring Boot 4.0 / Quarkus 3.21** — first-class starters with auto-configuration, native image support, and observability (Micrometer + OpenTelemetry).
- **Kotlin DSL** — builder API and coroutine extensions for idiomatic Kotlin usage.

## Client Libraries

- **atmosphere.js 5.0** — TypeScript client with React, Vue, and Svelte hooks for chat, rooms, presence, and AI streaming.
- **[React Native / Expo](react-native.md)** — `useAtmosphere` React Native hook with EventSource polyfill, NetInfo injection, and markdown rendering for mobile AI chat. See the [expo-client](../samples/spring-boot-ai-classroom/expo-client) sample.
- **wAsync 4.0** — Java client rewritten on `java.net.http` with gRPC support.

## Samples — Forked & Augmented

Four official AI framework samples have been forked and augmented with Atmosphere's real-time streaming, tool calling, cost metering, and content safety:

| Sample | Forked from | Atmosphere features added |
|--------|-------------|---------------------------|
| [spring-boot-langchain4j-tools](../samples/spring-boot-langchain4j-tools) | [langchain4j-examples/spring-boot-example](https://github.com/langchain4j/langchain4j-examples/tree/main/spring-boot-example) | Tool calling, PII redaction, cost metering |
| [spring-boot-spring-ai-routing](../samples/spring-boot-spring-ai-routing) | [spring-ai-examples/routing-workflow](https://github.com/spring-projects/spring-ai-examples/tree/main/agentic-patterns/routing-workflow) | Prompt routing, content safety, cost metering |
| [spring-boot-embabel-horoscope](../samples/spring-boot-embabel-horoscope) | [embabel-agent-examples/horoscope](https://github.com/embabel/embabel-agent-examples/tree/main/examples-java/horoscope) | Step progress streaming, content safety |
| [spring-boot-adk-tools](../samples/spring-boot-adk-tools) | [adk-java/city-time-weather](https://github.com/google/adk-java/tree/main/tutorials/city-time-weather) | Tool calling, streaming text budgets, response caching |

## Developer Experience

- **Architectural validation** — CI gate detects NOOP/dead code, placeholder stubs, DI bypass, and fluent builder misuse via TOML-configured patterns.
- **`atmosphere-generator`** — CLI scaffolding with `--tools` flag to generate `@AiTool` methods.

## See Also

- [Full Documentation](README.md)
