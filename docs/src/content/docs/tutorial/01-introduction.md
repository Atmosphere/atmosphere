---
title: "Chapter 1: Introduction"
description: "What Atmosphere is, its transport-agnostic design, key concepts, and what version 4.0 brings to the table."
sidebar:
  order: 1
---

# Introduction

Atmosphere is a transport-agnostic real-time framework for the JVM. You write your application logic once, broadcast messages through named channels, and the framework delivers them over WebSocket, SSE, long-polling, streaming, gRPC, or MCP -- depending on what each client supports. If a WebSocket handshake fails, the client transparently falls back to SSE, then long-polling, with no changes to your server code.

The project has been in continuous development since 2008. Version 4.0 requires JDK 21+ and adds rooms with presence, AI endpoint streaming, MCP server support, gRPC transport, and durable sessions, while preserving the same annotation-driven programming model that has been at the core of the framework since the beginning.

## Why Atmosphere Exists

Every real-time application needs a persistent connection between client and server, but the transport that creates that connection varies. A browser on a modern desktop will negotiate a WebSocket. A browser behind a corporate proxy that strips `Upgrade` headers will fall back to SSE. A mobile client on a flaky connection might need long-polling with aggressive heartbeats. A backend microservice might prefer gRPC.

Without Atmosphere, you would need to:

1. Implement each transport separately on the server.
2. Implement each transport separately on the client.
3. Write fallback logic and transport negotiation by hand.
4. Manage the pub/sub plumbing (who is subscribed to what) yourself.
5. Handle heartbeats, reconnection, message caching for missed messages, and connection lifecycle across all transports.

Atmosphere eliminates all of this. You write to a **Broadcaster**, and the framework delivers to every subscriber, regardless of their transport.

## Key Concepts

### AtmosphereResource

An `AtmosphereResource` represents a single suspended connection from a client. It wraps the underlying HTTP request/response pair and provides a transport-independent handle to communicate with that client. Every connected client -- whether over WebSocket, SSE, or long-polling -- is represented as an `AtmosphereResource`.

Each resource has a unique identifier accessible via `resource.uuid()`.

### Broadcaster

A `Broadcaster` is the pub/sub hub at the heart of Atmosphere. It maintains a set of subscribed `AtmosphereResource` instances and delivers messages to all of them when you call `broadcast(message)`. Think of it as a named topic or channel.

When you annotate a class with `@ManagedService(path = "/chat")`, Atmosphere automatically creates a `Broadcaster` for that path and subscribes every connecting client to it. When your `@Message` method returns a value, that value is broadcast to all subscribers.

### @ManagedService

`@ManagedService` is the primary programming model. It turns a plain Java class into a real-time endpoint with automatic lifecycle management, message routing, heartbeats, and message caching. A minimal example from the chat sample:

```java
@ManagedService(path = "/chat")
public class Chat {

    @Ready
    public void onReady() {
        // called when a connection is suspended and ready
    }

    @Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) {
        return message; // returned value is broadcast to all subscribers
    }
}
```

The lifecycle annotations -- `@Ready`, `@Disconnect`, `@Message`, `@Resume`, `@Heartbeat` -- let you hook into every stage of a connection without implementing any framework interface.

### AtmosphereHandler

For cases where you need lower-level control, `AtmosphereHandler` is the interface-based programming model. It has three methods: `onRequest`, `onStateChange`, and `destroy`. Most applications should prefer `@ManagedService`, which uses `AtmosphereHandler` internally but removes the boilerplate.

### BroadcasterFactory

`BroadcasterFactory` is the registry of all active `Broadcaster` instances. You can look up a broadcaster by path, create new ones on the fly, or iterate over all of them:

```java
@Inject
private BroadcasterFactory factory;

// Look up a broadcaster, creating it if it does not exist
Broadcaster b = factory.lookup("/chat", true);

// Get all active broadcasters
Collection<Broadcaster> all = factory.lookupAll();
```

## Transport-Agnostic Design

The central design principle is that your server-side code is identical regardless of transport. The same `@ManagedService` class handles WebSocket clients, SSE clients, and long-polling clients simultaneously. Transport negotiation happens at the framework level -- your `@Message` method never needs to know how the message will be delivered.

This is achieved through the `Broadcaster` abstraction. When you broadcast a message, Atmosphere inspects each subscribed `AtmosphereResource` to determine its transport and writes the message using the appropriate protocol. A WebSocket client receives a WebSocket frame. An SSE client receives a `data:` event. A long-polling client receives an HTTP response body.

## What's New in 4.0

Atmosphere 4.0 is the most significant release since the original 1.0. Key additions:

- **JDK 21 required** -- virtual threads are enabled by default for all async operations
- **Rooms and Presence** (`@RoomService`) -- first-class support for named rooms with join/leave lifecycle and optional message history
- **AI Endpoint Streaming** -- `@AiEndpoint` for streaming LLM responses token-by-token to connected clients
- **MCP Server Support** (`atmosphere-mcp`) -- expose Atmosphere endpoints as Model Context Protocol servers
- **gRPC Transport** (`atmosphere-grpc`) -- bidirectional streaming over gRPC alongside WebSocket and SSE
- **Durable Sessions** (`atmosphere-durable-sessions`) -- persist session state to SQLite or Redis for recovery after reconnection
- **Spring Boot 4.0 Starter** (`atmosphere-spring-boot-starter`) -- auto-configuration for Spring Boot 4.0 with Spring Framework 7.0
- **Quarkus Extension** (`atmosphere-quarkus-extension`) -- build-time configuration for Quarkus 3.21+
- **LLM Integrations** -- `atmosphere-langchain4j`, `atmosphere-spring-ai`, `atmosphere-adk` for AI agent frameworks

## Module Map

| Module | Artifact | Description |
|--------|----------|-------------|
| Core Runtime | `atmosphere-runtime` | The framework itself -- Broadcaster, AtmosphereResource, transports |
| Spring Boot | `atmosphere-spring-boot-starter` | Auto-configuration for Spring Boot 4.0 |
| Quarkus | `atmosphere-quarkus-extension` | Build-time extension for Quarkus 3.21+ |
| AI | `atmosphere-ai` | AI endpoint streaming support |
| MCP | `atmosphere-mcp` | Model Context Protocol server |
| gRPC | `atmosphere-grpc` | gRPC bidirectional streaming transport |
| LangChain4j | `atmosphere-langchain4j` | LangChain4j integration |
| Spring AI | `atmosphere-spring-ai` | Spring AI integration |
| ADK | `atmosphere-adk` | Google ADK integration |
| Durable Sessions | `atmosphere-durable-sessions` | Session persistence (SQLite, Redis) |
| TypeScript Client | `atmosphere.js` | Browser and React Native client library |

## Next Steps

In the next chapter, you will build a working chat application from scratch using `@ManagedService`, starting with the Maven dependency and ending with a running server that accepts WebSocket and SSE connections.
