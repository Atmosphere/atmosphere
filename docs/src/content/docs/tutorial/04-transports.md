---
title: "Transport-Agnostic Design"
description: "How Atmosphere negotiates WebSocket, SSE, long-polling, streaming, and gRPC transparently"
---

Atmosphere's defining feature is transport agnosticism. You write business logic once — the framework handles delivery over WebSocket, SSE, long-polling, HTTP streaming, or gRPC. This chapter explains how.

## The Five Transports

| Transport | Direction | Connection | Use Case |
|-----------|-----------|-----------|----------|
| **WebSocket** | Bidirectional | Persistent | Chat, gaming, collaboration |
| **SSE** | Server → Client | Persistent | Notifications, dashboards, AI streaming |
| **Long-Polling** | Simulated bidirectional | Short-lived | Firewall-restricted environments |
| **HTTP Streaming** | Server → Client | Persistent | Legacy browsers, chunked transfer |
| **gRPC** | Bidirectional | Persistent | Service-to-service, polyglot |

## Automatic Negotiation

When a client connects, Atmosphere negotiates the best available transport:

```
Client request → WebSocket upgrade?
                    ├─ Yes → WebSocket
                    └─ No → SSE available?
                              ├─ Yes → SSE
                              └─ No → Long-Polling
```

The server-side code is identical regardless of transport:

```java
@ManagedService(path = "/notifications")
public class Notifications {

    @Message
    public String onMessage(String message) {
        return message; // delivered via whatever transport the client negotiated
    }
}
```

### Client-Side Transport Selection

The client explicitly requests a transport with an automatic fallback:

```typescript
const subscription = await atmosphere.subscribe({
  url: '/notifications',
  transport: 'websocket',           // preferred
  fallbackTransport: 'long-polling', // if WebSocket fails
});
```

If the WebSocket upgrade fails (proxy blocks it, firewall restriction), the client automatically falls back to `long-polling` without any server-side changes.

## WebSocket

The default and preferred transport. Full-duplex communication over a single TCP connection.

**How it works:**
1. Client sends an HTTP upgrade request
2. Server upgrades to WebSocket
3. Both sides can send messages at any time
4. Connection stays open until either side closes it

```java
@Ready
public void onReady(AtmosphereResource r) {
    // r.transport() == AtmosphereResource.TRANSPORT.WEBSOCKET
    log.info("WebSocket client connected: {}", r.uuid());
}
```

**Pros:** Lowest latency, bidirectional, efficient  
**Cons:** May be blocked by corporate proxies/firewalls

## Server-Sent Events (SSE)

A unidirectional, server-to-client streaming protocol built on HTTP.

**How it works:**
1. Client sends a standard HTTP GET request
2. Server responds with `Content-Type: text/event-stream`
3. Server pushes events as they occur
4. Client sends data via separate HTTP POST requests

```typescript
const subscription = await atmosphere.subscribe({
  url: '/notifications',
  transport: 'sse',
}, {
  message: (response) => console.log('Event:', response.responseBody),
});
```

SSE is ideal for AI/LLM token streaming where the data flow is primarily server-to-client.

**Pros:** Works through HTTP proxies, auto-reconnection built into the protocol  
**Cons:** Unidirectional (client → server requires separate requests)

## Long-Polling

Simulates real-time by holding HTTP requests open until data is available.

**How it works:**
1. Client sends an HTTP GET request
2. Server holds the request open (suspends it)
3. When data is available, server responds and closes the request
4. Client immediately sends a new request
5. Repeat

**Pros:** Works everywhere (any HTTP client, any proxy)  
**Cons:** Higher latency, more HTTP overhead

## gRPC

Bidirectional streaming over HTTP/2 using Protocol Buffers.

```java
var server = AtmosphereGrpcServer.builder()
        .framework(framework)
        .port(9090)
        .handler(new GrpcHandlerAdapter() {
            @Override
            public void onMessage(GrpcChannel channel, String message) {
                channel.write("Echo: " + message);
            }
        })
        .build();
server.start();
```

gRPC clients and WebSocket clients can share the same Broadcaster — messages broadcast on one transport are delivered on all.

See [Chapter 20](/docs/tutorial/20-grpc-kotlin/) for a complete gRPC deep dive.

## The Suspend/Resume Model

Atmosphere's core model for non-WebSocket transports:

```java
// Suspend: hold the connection open
resource.suspend();

// Resume: send data and close (long-polling) or keep sending (streaming)
resource.resume();

// Or let the Broadcaster handle it
broadcaster.broadcast("data"); // automatically resumes suspended resources
```

For **long-polling**, `broadcast()` resumes the connection (sends the response), and the client reconnects. For **SSE and streaming**, `broadcast()` writes to the open connection without closing it.

## Server-Side Transport Detection

```java
@Ready
public void onReady(AtmosphereResource r) {
    switch (r.transport()) {
        case WEBSOCKET -> log.info("WebSocket client");
        case SSE -> log.info("SSE client");
        case LONG_POLLING -> log.info("Long-polling client");
        case STREAMING -> log.info("HTTP streaming client");
        default -> log.info("Unknown transport: {}", r.transport());
    }
}
```

All transport types receive the same broadcast messages — the framework adapts the wire format automatically.

## Virtual Threads

Atmosphere uses JDK 21 virtual threads by default:

- `ExecutorsFactory` creates `Executors.newVirtualThreadPerTaskExecutor()`
- `DefaultBroadcaster` uses `ReentrantLock` (not `synchronized`) to avoid virtual thread pinning
- `@Prompt` methods in `@AiEndpoint` run on virtual threads
- Only `ScheduledExecutorService` (timed tasks like heartbeats) remains on platform threads

Opt out if needed:

```properties
org.atmosphere.cpr.useVirtualThreads=false
```

## Mixed Transport Scenario

A single Broadcaster can serve clients on different transports simultaneously:

```java
@ManagedService(path = "/dashboard")
public class Dashboard {

    @Message
    public String onMessage(String message) {
        // This message reaches ALL subscribers:
        // - WebSocket browsers
        // - SSE dashboards  
        // - Long-polling mobile apps
        // - gRPC microservices
        return message;
    }
}
```

The transport is a client concern. The server never needs to know or care how each client is connected.

## Next Steps

- [Chapter 5: Broadcaster & Pub/Sub](/docs/tutorial/05-broadcaster/) — deep dive into the message delivery system
- [Chapter 7: WebSocket Deep Dive](/docs/tutorial/07-websocket/) — WebSocket-specific features
- [Chapter 20: gRPC & Kotlin](/docs/tutorial/20-grpc-kotlin/) — gRPC transport details
