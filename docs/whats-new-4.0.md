# What's New in 4.0

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

## See Also

- [Full Documentation](README.md)
