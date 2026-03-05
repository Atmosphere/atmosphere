---
title: "Chapter 1: Introduction & Architecture"
description: "What Atmosphere is, why it exists, the two core abstractions, transport-agnostic design, and how the module map fits together."
---

# Introduction & Architecture

Atmosphere is a transport-agnostic real-time framework for the JVM. You write your application logic once -- broadcasting messages through named channels -- and the framework delivers them over WebSocket, SSE, Long-Polling, HTTP Streaming, gRPC, or MCP, depending on what each client supports. If a WebSocket handshake fails, the client transparently falls back to SSE, then Long-Polling, with no changes to your server code.

The project has been in continuous development since 2008 -- over 18 years of battle-tested WebSocket and Comet experience across every major Java web container: Jetty, Tomcat, Undertow, GlassFish, JBoss, and WebLogic. Version 4.0 adds an AI/LLM streaming platform, MCP server support, rooms with presence, gRPC transport, clustering, and durable sessions, while preserving the same annotation-driven programming model that has been at the core of the framework since the beginning.

## Why Atmosphere Exists

The problem Atmosphere solves is simple to state: **every real-time application needs a persistent connection between client and server, but the transport that creates that connection varies**.

A browser on a modern desktop will negotiate a WebSocket. A browser behind a corporate proxy that strips `Upgrade` headers will fall back to SSE. A mobile client on a flaky 3G connection might need long-polling with aggressive heartbeats. A backend microservice might prefer gRPC. An AI agent might communicate over MCP.

Without Atmosphere, you would need to:

1. Implement each transport separately on the server.
2. Implement each transport separately on the client.
3. Write fallback logic and transport negotiation by hand.
4. Manage the pub/sub plumbing (who is subscribed to what) yourself.
5. Handle heartbeats, reconnection, message caching for missed messages, and session lifecycle across all transports.

Atmosphere eliminates all of this. You write to a **Broadcaster**, and the framework delivers to every subscriber, regardless of their transport.

## The Two Core Abstractions

Everything in Atmosphere is built on two concepts: the **Broadcaster** and the **AtmosphereResource**.

### Broadcaster

A `Broadcaster` is a named pub/sub channel. Think of it as a topic in a message broker, but in-process and integrated with the web layer. When you call `broadcaster.broadcast(message)`, the framework delivers that message to every subscribed connection.

```java
// Retrieve or create a Broadcaster by path
Broadcaster chatRoom = factory.lookup("/chat/room1", true);

// Broadcast a message to all subscribers
chatRoom.broadcast("Hello, everyone!");

// Broadcast to a specific subscriber
chatRoom.broadcast("Private hello", specificResource);
```

Key properties of a Broadcaster:

| Property | Description |
|----------|-------------|
| **ID** | A string identifier (typically the URL path). `broadcaster.getID()` returns it. |
| **Scope** | `REQUEST`, `APPLICATION`, or `VM`. Controls which resources receive broadcasts. |
| **Cache** | A `BroadcasterCache` stores messages for clients that temporarily disconnect. Default is `UUIDBroadcasterCache`. |
| **Filters** | `BroadcastFilter` implementations can transform or suppress messages before delivery. |
| **Asynchronous** | `broadcast()` returns a `Future<Object>`. Delivery happens on a separate thread (virtual thread by default on JDK 21+). |

Broadcasters are created lazily by the `BroadcasterFactory` and are identified by their path. When you annotate a class with `@ManagedService(path = "/chat")`, the framework creates a Broadcaster named `/chat` and automatically subscribes every connecting client to it.

### AtmosphereResource

An `AtmosphereResource` represents a single connection -- one browser tab, one mobile client, one gRPC stream. It wraps the underlying transport mechanism (a WebSocket session, an SSE event stream, an HTTP response held open for long-polling) behind a uniform API.

```java
AtmosphereResource resource = ...;

// What transport is this client using?
AtmosphereResource.TRANSPORT transport = resource.transport();
// Returns WEBSOCKET, SSE, LONG_POLLING, STREAMING, GRPC, etc.

// Suspend the connection (keep it open for future writes)
resource.suspend();

// Resume the connection (close it after writing)
resource.resume();

// Get the unique identifier
String uuid = resource.uuid();

// Access the underlying request/response
AtmosphereRequest request = resource.getRequest();
AtmosphereResponse response = resource.getResponse();

// Get the Broadcaster this resource is subscribed to
Broadcaster broadcaster = resource.getBroadcaster();
```

The TRANSPORT enum defines all supported transport types:

```java
enum TRANSPORT {
    POLLING,        // Traditional HTTP polling
    LONG_POLLING,   // HTTP long-polling (Comet)
    STREAMING,      // HTTP streaming
    WEBSOCKET,      // WebSocket (RFC 6455)
    SSE,            // Server-Sent Events
    GRPC,           // gRPC bidirectional streaming
    AJAX,           // XMLHttpRequest streaming
    HTMLFILE,       // Hidden iframe streaming (legacy IE)
    UNDEFINED,      // Not yet determined
    CLOSE           // Connection closing
}
```

### How They Work Together

The relationship between Broadcaster and AtmosphereResource is a classic observer pattern:

```
                    +-----------+
                    | Broadcaster|  ("/chat")
                    +-----+-----+
                          |
            +-------------+-------------+
            |             |             |
     +------+------+ +---+---+ +------+------+
     |AtmosphereRes| |AtmRes  | |AtmosphereRes|
     | (WebSocket) | | (SSE)  | | (Long-Poll) |
     +-------------+ +--------+ +-------------+
       Browser A     Browser B    Browser C
```

When your `@Message` method returns a value, or when you call `broadcaster.broadcast(msg)`, the framework:

1. Passes the message through any registered `BroadcastFilter`s.
2. Iterates over all subscribed `AtmosphereResource`s.
3. For each resource, writes the message using the transport-appropriate mechanism:
   - **WebSocket**: sends a WebSocket frame.
   - **SSE**: writes a `data:` event to the event stream.
   - **Long-Polling**: writes to the HTTP response and closes it (the client immediately reconnects).
   - **HTTP Streaming**: writes to the open HTTP response.
   - **gRPC**: sends a protobuf message on the bidirectional stream.

Your code never needs to know which transport is in use.

## Transport-Agnostic Philosophy

The central design principle of Atmosphere is: **your application code should not contain transport-specific logic**.

Consider this `@ManagedService`:

```java
@ManagedService(path = "/notifications")
public class NotificationService {

    @Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Notification onMessage(Notification notification) {
        return notification; // broadcast to all subscribers
    }
}
```

This single class handles:

- A desktop browser connecting via WebSocket
- A mobile browser falling back to SSE because WebSocket is blocked
- A legacy system using long-polling
- A gRPC microservice consuming the same feed
- An AI agent subscribing via MCP

The framework negotiates the transport per-connection. The `NotificationService` class never changes.

## Module Map

Atmosphere 4.0 is organized into focused modules. You only pull in what you need.

### Core

| Module | Artifact | Description |
|--------|----------|-------------|
| **Runtime** | `atmosphere-runtime` | The core framework: Broadcaster, AtmosphereResource, `@ManagedService`, transport negotiation, interceptors, broadcaster cache, virtual thread support. This is the only required dependency. |

### AI & Protocols

| Module | Artifact | Description |
|--------|----------|-------------|
| **AI** | `atmosphere-ai` | `@AiEndpoint`, `@AiTool`, `@Prompt`, `StreamingSession`, the `AiSupport` SPI, conversation memory, token budgets, cost metering. |
| **MCP** | `atmosphere-mcp` | Model Context Protocol server: `@McpServer`, `@McpTool`, `@McpResource`, `@McpPrompt` over Streamable HTTP, WebSocket, or SSE. |
| **gRPC** | `atmosphere-grpc` | Bidirectional streaming transport via grpc-java. Same Broadcaster, different wire protocol. |

### Language & Clustering

| Module | Artifact | Description |
|--------|----------|-------------|
| **Kotlin** | `atmosphere-kotlin` | Kotlin DSL builder and coroutine extensions for idiomatic Kotlin usage. |
| **Redis** | `atmosphere-redis` | Redis-backed Broadcaster for multi-node clustering. |
| **Kafka** | `atmosphere-kafka` | Kafka-backed Broadcaster for multi-node clustering. |
| **Durable Sessions** | `atmosphere-durable-sessions` | Survive server restarts with InMemory, SQLite, or Redis-backed session stores. |

### Framework Integrations

| Module | Artifact | Description |
|--------|----------|-------------|
| **Spring Boot** | `atmosphere-spring-boot-starter` | Auto-configuration for Spring Boot 4.0+. Registers the servlet, wires Spring DI, exposes beans. |
| **Quarkus** | `atmosphere-quarkus-extension` | Quarkus 3.21+ extension with build-time processing, CDI integration, and native image support. |

### Client Libraries

| Library | Package | Description |
|---------|---------|-------------|
| **atmosphere.js** | `atmosphere.js` (npm) | TypeScript client with React, Vue, and Svelte hooks. Handles transport negotiation, reconnection, and heartbeats. |
| **React Native** | `atmosphere.js/react-native` | React Native hooks with EventSource polyfill for mobile apps. |
| **wAsync** | `atmosphere-wasync` | Java client built on `java.net.http` with gRPC support. |

### Dependency Graph

For a typical Spring Boot chat application, you need exactly two Atmosphere dependencies:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-boot-starter</artifactId>
    <version>4.0.11-SNAPSHOT</version>
</dependency>
```

The starter transitively includes `atmosphere-runtime`. If you add AI features later:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai</artifactId>
    <version>4.0.11-SNAPSHOT</version>
</dependency>
```

Each module is independently versioned with the same version number. Pull in only what you need.

## How Transports Work

### WebSocket

The preferred transport. Full-duplex, low-latency, minimal overhead. Atmosphere uses JSR 356 (Jakarta WebSocket) under the hood, with container-specific optimizations for Jetty, Tomcat, and Undertow.

When a client connects with `transport: 'websocket'`, the framework upgrades the HTTP connection to a WebSocket, creates an `AtmosphereResource` with `transport() == WEBSOCKET`, and subscribes it to the Broadcaster at the requested path.

### Server-Sent Events (SSE)

Unidirectional: server pushes events to the client. The client sends messages back over regular HTTP POST requests. SSE is natively supported by all modern browsers and has excellent proxy compatibility.

Atmosphere's SSE implementation sets `Content-Type: text/event-stream`, manages the event ID for reconnection, and uses the Broadcaster cache to replay missed messages.

### Long-Polling

The universal fallback. The client sends an HTTP request, the server holds it open until there is a message to deliver (or a timeout occurs), then responds and closes the connection. The client immediately sends a new request.

Atmosphere manages this suspend/resume cycle automatically via the `AtmosphereResourceLifecycleInterceptor`. From your application code, it looks identical to WebSocket -- you broadcast to a Broadcaster, and the framework writes to the held-open response and triggers the client to reconnect.

### HTTP Streaming

Similar to long-polling, but the connection stays open and multiple messages are written to the same response. This is more efficient than long-polling but has limited browser support and can be disrupted by buffering proxies.

### gRPC

Bidirectional streaming for service-to-service communication. The `atmosphere-grpc` module bridges gRPC streams into the same Broadcaster infrastructure. A gRPC client and a WebSocket client subscribed to the same Broadcaster will receive the same messages.

### Automatic Fallback

The client library (`atmosphere.js`) negotiates the best transport:

```
WebSocket  -->  (failed?)  -->  SSE  -->  (failed?)  -->  Long-Polling
```

This fallback is transparent. The server does not need to be configured differently per transport -- it handles all transports simultaneously on the same endpoint.

## The AsyncSupport and AiSupport SPIs

Atmosphere has two parallel SPI (Service Provider Interface) layers that follow the same design pattern:

### AsyncSupport: Adapting Web Containers

The `AsyncSupport` interface is how Atmosphere adapts to different web containers. Each container (Jetty, Tomcat, Undertow) has a different internal API for holding HTTP connections open. The `AsyncSupport` implementation for each container translates between the container's native API and Atmosphere's `AtmosphereResource` abstraction.

```java
public interface AsyncSupport<E extends AtmosphereResource> {
    String getContainerName();
    void init(ServletConfig sc) throws ServletException;
    Action service(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException;
    boolean supportWebSocket();
    AsyncSupport<E> complete(E r);
}
```

When your application starts, the `DefaultAsyncSupportResolver` inspects the classpath and selects the best `AsyncSupport` implementation. If Jetty's classes are present, it picks Jetty's. If Tomcat's are present, it picks Tomcat's. If nothing is found, it falls back to `BlockingIOCometSupport`.

### AiSupport: Adapting AI Frameworks

The `AiSupport` SPI follows the same pattern for AI/LLM frameworks. Instead of adapting web containers, it adapts AI frameworks -- Spring AI, LangChain4j, Google ADK, Embabel:

| Concern | Transport layer | AI layer |
|---------|----------------|----------|
| **SPI interface** | `AsyncSupport` | `AiSupport` |
| **What it adapts** | Web containers (Jetty, Tomcat, Undertow) | AI frameworks (Spring AI, LangChain4j, ADK, Embabel) |
| **Discovery** | Classpath scanning | `ServiceLoader` |
| **Resolution** | Best available container | Highest `priority()` among `isAvailable()` |
| **Initialization** | `init(ServletConfig)` | `configure(LlmSettings)` |
| **Core method** | `service(req, res)` | `stream(AiRequest, StreamingSession)` |
| **Fallback** | `BlockingIOCometSupport` | `BuiltInAiSupport` (OpenAI-compatible) |

This design means you can write `@AiEndpoint` code once, and it works whether your project uses Spring AI, LangChain4j, or the built-in OpenAI-compatible client. The SPI layer auto-detects which framework is on the classpath and wires the appropriate adapter.

## Comparison with Alternatives

### Atmosphere vs. Plain WebSocket (JSR 356)

| Feature | Plain WebSocket | Atmosphere |
|---------|----------------|------------|
| Transport | WebSocket only | WebSocket + SSE + Long-Polling + Streaming + gRPC + MCP |
| Fallback | None -- if WebSocket fails, you are stuck | Automatic negotiation with configurable fallback chain |
| Pub/sub | Roll your own | Built-in Broadcaster with caching, filtering, clustering |
| Message caching | Roll your own | `UUIDBroadcasterCache` replays missed messages on reconnect |
| Heartbeat | Roll your own | Built-in `HeartbeatInterceptor` with configurable intervals |
| DI integration | Manual | `@Inject` works with Spring, CDI, or Atmosphere's own DI |
| Clustering | Not built in | Redis and Kafka Broadcaster implementations |
| AI streaming | Not built in | `@AiEndpoint`, `StreamingSession`, auto-detected AI backends |

### Atmosphere vs. Spring WebFlux / WebSocket

| Feature | Spring WebFlux | Atmosphere |
|---------|---------------|------------|
| Programming model | Reactive (Mono/Flux) | Annotation-driven POJO (`@ManagedService`) |
| Transport fallback | Must implement SSE and WebSocket separately | Automatic negotiation, single endpoint |
| Broadcaster abstraction | None -- use `Sinks` manually | First-class Broadcaster with cache, filters, clustering |
| Client library | None (roll your own) | `atmosphere.js` with React/Vue/Svelte hooks |
| AI streaming | Via Spring AI (separate setup) | Integrated `@AiEndpoint` with the same Broadcaster plumbing |
| Container portability | Netty or Servlet (not both) | Any Servlet 6.0+ container (Jetty, Tomcat, Undertow) |

### Atmosphere vs. Socket.IO

| Feature | Socket.IO (Java server) | Atmosphere |
|---------|------------------------|------------|
| Language | JavaScript-first (Java port) | Java-first, TypeScript client |
| Transports | WebSocket + HTTP long-polling | WebSocket + SSE + Long-Polling + Streaming + gRPC + MCP |
| Framework integration | Standalone server | Spring Boot starter, Quarkus extension, any Servlet container |
| AI/LLM | Not built in | `@AiEndpoint`, MCP server, tool calling |
| Clustering | Redis adapter | Redis and Kafka adapters |
| Message guarantees | Room-level | Broadcaster-level with pluggable cache |
| Virtual threads | Not applicable (Node.js model) | JDK 21 virtual threads by default |

## What is Next

Now that you understand the architecture, the next chapter walks through building your first Atmosphere application: a Spring Boot chat app with the `atmosphere.js` TypeScript client.

- **[Chapter 2: Getting Started](/docs/tutorial/02-getting-started/)** -- Spring Boot quickstart, the minimal `@ManagedService`, and the `atmosphere.js` client.
- **[Chapter 3: @ManagedService Deep Dive](/docs/tutorial/03-managed-service/)** -- Full annotation reference, lifecycle methods, encoders/decoders, path parameters.
- **[Chapter 4: Transport-Agnostic Design](/docs/tutorial/04-transports/)** -- How transport negotiation works, suspend/resume, virtual threads.
