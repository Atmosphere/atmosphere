---
title: "Chapter 20: gRPC, Kotlin DSL & Beyond"
description: "gRPC transport, Kotlin DSL with coroutines, virtual threads, and the road ahead"
---

# Chapter 20: gRPC, Kotlin DSL & Beyond

This is the final chapter of the tutorial. We will cover three topics that extend Atmosphere beyond the traditional servlet-and-browser model: the gRPC transport for high-performance binary streaming, the Kotlin DSL for expressive server-side code, and virtual threads for scalable concurrency. We close with pointers to samples, community, and what to explore next.

## What You Will Learn

- Building a standalone gRPC server with `AtmosphereGrpcServer`.
- Implementing `GrpcHandler` for lifecycle events.
- gRPC message types (Protobuf).
- Spring Boot gRPC auto-configuration.
- Connecting from Java with wAsync's gRPC transport.
- Writing Atmosphere handlers with the Kotlin DSL.
- Using coroutine extensions: `broadcastSuspend()` and `writeSuspend()`.
- How virtual threads work in Atmosphere and why they are enabled by default.
- Where to go from here.

## Prerequisites

You should have completed the core tutorial chapters (1-7) and be familiar with Atmosphere's `AtmosphereHandler`, `Broadcaster`, and `AtmosphereResource` concepts.

---

## Part 1: gRPC Transport

### Why gRPC?

WebSocket, SSE, and long-polling are browser-friendly transports. But server-to-server communication, mobile clients, and polyglot microservices often benefit from gRPC's strengths:

- **Binary framing** via Protocol Buffers -- smaller payloads, faster serialization.
- **HTTP/2 multiplexing** -- many streams over a single TCP connection.
- **Bidirectional streaming** -- true full-duplex, like WebSocket but with typed schemas.
- **Language-agnostic** -- generated clients in Go, Python, Rust, C++, and more.

Atmosphere's gRPC transport lets gRPC clients participate in the same `Broadcaster` topology as WebSocket and SSE clients. A message broadcast to `/chat` reaches everyone -- regardless of transport.

### Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-grpc</artifactId>
    <version>4.0.10</version>
</dependency>
```

For the gRPC runtime itself, you also need:

```xml
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-netty-shaded</artifactId>
    <version>1.68.0</version>
</dependency>
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-protobuf</artifactId>
    <version>1.68.0</version>
</dependency>
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-stub</artifactId>
    <version>1.68.0</version>
</dependency>
```

### Building a Standalone gRPC Server

The `AtmosphereGrpcServer` builder creates a gRPC server backed by an `AtmosphereFramework`:

```java
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.grpc.AtmosphereGrpcServer;

var framework = new AtmosphereFramework();

try (var server = AtmosphereGrpcServer.builder()
        .framework(framework)
        .port(9090)
        .handler(new ChatGrpcHandler())
        .enableReflection(true)
        .build()) {

    server.start();
    System.out.println("gRPC server listening on port 9090");
    server.awaitTermination();
}
```

The `enableReflection(true)` call registers the gRPC Server Reflection service, which allows tools like `grpcurl` and Postman to discover your service schema at runtime.

### Builder Options Reference

```java
AtmosphereGrpcServer.builder()
    .framework(framework)            // Required: the AtmosphereFramework instance
    .port(9090)                      // Default: 9090
    .handler(handler)                // Default: no-op GrpcHandlerAdapter
    .enableReflection(true)          // Default: true
    .interceptor(myServerInterceptor) // Optional: gRPC ServerInterceptor
    .build();
```

### Implementing GrpcHandler

The `GrpcHandler` interface mirrors the familiar `AtmosphereHandler` lifecycle, adapted for gRPC channels:

```java
import org.atmosphere.grpc.GrpcChannel;
import org.atmosphere.grpc.GrpcHandler;

public class ChatGrpcHandler implements GrpcHandler {

    @Override
    public void onOpen(GrpcChannel channel) {
        System.out.println("Client connected: " + channel.uuid());

        // Subscribe this gRPC channel to the /chat broadcaster
        // so it receives messages from WebSocket clients too
        channel.resource().getBroadcaster().addAtmosphereResource(
            channel.resource());
    }

    @Override
    public void onMessage(GrpcChannel channel, String message) {
        System.out.println("Text from " + channel.uuid() + ": " + message);

        // Broadcast to all subscribers (WebSocket, SSE, gRPC, etc.)
        channel.resource().getBroadcaster().broadcast(message);
    }

    @Override
    public void onBinaryMessage(GrpcChannel channel, byte[] data) {
        System.out.println("Binary from " + channel.uuid()
            + ": " + data.length + " bytes");

        // Forward binary data to all subscribers
        channel.resource().getBroadcaster().broadcast(data);
    }

    @Override
    public void onClose(GrpcChannel channel) {
        System.out.println("Client disconnected: " + channel.uuid());
    }

    @Override
    public void onError(GrpcChannel channel, Throwable t) {
        System.err.println("Error on " + channel.uuid() + ": " + t.getMessage());
    }
}
```

If you do not need all callbacks, extend `GrpcHandlerAdapter` instead -- it provides no-op defaults:

```java
import org.atmosphere.grpc.GrpcHandlerAdapter;

public class MinimalHandler extends GrpcHandlerAdapter {

    @Override
    public void onMessage(GrpcChannel channel, String message) {
        channel.resource().getBroadcaster().broadcast(message);
    }
}
```

### GrpcChannel API

The `GrpcChannel` is your handle to a connected gRPC client:

| Method | Return | Description |
|---|---|---|
| `uuid()` | `String` | Unique channel identifier. |
| `write(String msg)` | `void` | Send a text message to the client. |
| `write(byte[] data)` | `void` | Send binary data to the client. |
| `write(String topic, String msg)` | `void` | Send a message to a specific topic/broadcaster. |
| `isOpen()` | `boolean` | Check if the channel is still open. |
| `close()` | `void` | Close the channel. |
| `resource()` | `AtmosphereResource` | Access the underlying `AtmosphereResource` for broadcaster operations. |

### Protobuf Message Types

Atmosphere defines a `AtmosphereMessage` protobuf type with a `type` field that determines the message semantics:

| Type | Direction | Description |
|---|---|---|
| `SUBSCRIBE` | Client to Server | Subscribe to a topic/broadcaster. The `topic` field specifies the broadcaster path. |
| `UNSUBSCRIBE` | Client to Server | Unsubscribe from a topic. |
| `MESSAGE` | Bidirectional | Carry a text or binary payload. The `payload` field holds the data. |
| `HEARTBEAT` | Bidirectional | Keepalive ping. Prevents idle connection timeouts. |
| `ACK` | Server to Client | Acknowledgment of a client message. Contains the `seq` of the acknowledged message. |

### Testing with grpcurl

With reflection enabled, you can test your gRPC server from the command line:

```bash
# List available services
grpcurl -plaintext localhost:9090 list

# Describe the service
grpcurl -plaintext localhost:9090 describe atmosphere.AtmosphereService

# Open a bidirectional stream
grpcurl -plaintext -d '{"type":"SUBSCRIBE","topic":"/chat"}' \
  localhost:9090 atmosphere.AtmosphereService/Stream

# Send a message
grpcurl -plaintext -d '{"type":"MESSAGE","payload":"Hello from grpcurl!"}' \
  localhost:9090 atmosphere.AtmosphereService/Stream
```

### Spring Boot gRPC Auto-Configuration

When `atmosphere-grpc` is on the classpath and the Spring Boot starter is active, you can enable gRPC with configuration alone:

```yaml
atmosphere:
  grpc:
    enabled: true
    port: 9090
    enable-reflection: true
```

Define a `GrpcHandler` bean and the starter wires everything together:

```java
import org.atmosphere.grpc.GrpcChannel;
import org.atmosphere.grpc.GrpcHandlerAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcConfig {

    @Bean
    public GrpcHandler grpcHandler() {
        return new GrpcHandlerAdapter() {
            @Override
            public void onOpen(GrpcChannel channel) {
                System.out.println("gRPC client connected: " + channel.uuid());
            }

            @Override
            public void onMessage(GrpcChannel channel, String message) {
                channel.resource().getBroadcaster().broadcast(message);
            }
        };
    }
}
```

Your Spring Boot application now serves both HTTP (WebSocket/SSE/long-polling on port 8080) and gRPC (on port 9090) simultaneously, sharing the same `Broadcaster` topology.

### Java Client with wAsync

The wAsync client supports gRPC as a first-class transport. Use the `grpc://` URI scheme:

```java
import org.atmosphere.wasync.*;
import org.atmosphere.wasync.impl.AtmosphereClient;
import org.atmosphere.wasync.impl.AtmosphereRequestBuilder;

var client = AtmosphereClient.newClient();

var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
        .uri("grpc://localhost:9090/chat")
        .transport(Request.TRANSPORT.GRPC)
        .build();

var socket = client.create()
        .on(Event.OPEN, o -> System.out.println("Connected via gRPC"))
        .on(Event.MESSAGE, m -> System.out.println("Received: " + m))
        .on(Event.CLOSE, c -> System.out.println("Disconnected"))
        .on(Event.ERROR, e -> System.err.println("Error: " + e))
        .open(request);

// Send messages
socket.fire("Hello from Java via gRPC!");
socket.fire("Another message");

// Clean up
socket.close();
```

The wAsync gRPC transport requires `grpc-netty-shaded`, `grpc-protobuf`, and `grpc-stub` on the classpath.

### Transport Fallback with gRPC

You can chain gRPC with other transports for fallback:

```java
var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
        .uri("grpc://localhost:9090/chat")
        .transport(Request.TRANSPORT.GRPC)          // try gRPC first
        .transport(Request.TRANSPORT.WEBSOCKET)      // fallback to WebSocket
        .transport(Request.TRANSPORT.LONG_POLLING)   // last resort
        .build();
```

This is useful for clients that may be deployed in environments where gRPC (HTTP/2) is not available.

---

## Part 2: Kotlin DSL

### Why Kotlin?

Kotlin's type-safe builders and coroutine support make it a natural fit for real-time server code. The `atmosphere-kotlin` module provides:

1. A **DSL builder** for creating `AtmosphereHandler` instances without implementing the interface directly.
2. **Coroutine extensions** that turn blocking Atmosphere operations into suspending functions.

### Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-kotlin</artifactId>
    <version>4.0.10</version>
</dependency>
```

You also need the Kotlin standard library and `kotlinx-coroutines-core`:

```xml
<dependency>
    <groupId>org.jetbrains.kotlin</groupId>
    <artifactId>kotlin-stdlib</artifactId>
</dependency>
<dependency>
    <groupId>org.jetbrains.kotlinx</groupId>
    <artifactId>kotlinx-coroutines-core</artifactId>
</dependency>
```

### The atmosphere { } Builder

The DSL lets you define an `AtmosphereHandler` as a series of callback blocks:

```kotlin
import org.atmosphere.kotlin.atmosphere

val handler = atmosphere {
    onConnect { resource ->
        println("${resource.uuid()} connected via ${resource.transport()}")
    }

    onMessage { resource, message ->
        println("Message from ${resource.uuid()}: $message")
        resource.broadcaster.broadcast(message)
    }

    onDisconnect { resource ->
        println("${resource.uuid()} disconnected")
    }

    onTimeout { resource ->
        println("${resource.uuid()} timed out")
    }

    onResume { resource ->
        println("${resource.uuid()} resumed")
    }
}

framework.addAtmosphereHandler("/chat", handler)
```

Every callback is optional. If you omit one, it defaults to a no-op. This is equivalent to implementing `AtmosphereHandler` in Java, but more concise.

### DSL Callback Reference

| Callback | Parameters | When It Fires |
|---|---|---|
| `onConnect` | `(AtmosphereResource)` | A new client connects. |
| `onMessage` | `(AtmosphereResource, String)` | A message arrives from a client. |
| `onDisconnect` | `(AtmosphereResource)` | A client disconnects (clean or unclean). |
| `onTimeout` | `(AtmosphereResource)` | A suspended connection times out. |
| `onResume` | `(AtmosphereResource)` | A suspended connection is resumed. |

### A Chat Room in Kotlin

Here is a complete chat room handler using the DSL:

```kotlin
import org.atmosphere.kotlin.atmosphere
import org.atmosphere.cpr.AtmosphereFramework
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

data class ChatMessage(val user: String, val text: String)

val mapper = jacksonObjectMapper()

val chatHandler = atmosphere {
    onConnect { resource ->
        val join = mapper.writeValueAsString(
            ChatMessage("system", "${resource.uuid()} joined"))
        resource.broadcaster.broadcast(join)
    }

    onMessage { resource, raw ->
        val msg = mapper.readValue<ChatMessage>(raw)
        val enriched = msg.copy(user = msg.user.ifBlank { resource.uuid() })
        resource.broadcaster.broadcast(mapper.writeValueAsString(enriched))
    }

    onDisconnect { resource ->
        val leave = mapper.writeValueAsString(
            ChatMessage("system", "${resource.uuid()} left"))
        resource.broadcaster.broadcast(leave)
    }
}

fun main() {
    val framework = AtmosphereFramework()
    framework.addAtmosphereHandler("/chat", chatHandler)
    // ... start your server
}
```

### Coroutine Extensions

The `atmosphere-kotlin` module adds suspending extension functions to `Broadcaster` and `AtmosphereResource`. These bridge Atmosphere's `Future`-based API into Kotlin's structured concurrency model.

#### broadcastSuspend()

```kotlin
import org.atmosphere.kotlin.broadcastSuspend
import kotlinx.coroutines.runBlocking

runBlocking {
    // Suspend until the broadcast is delivered
    broadcaster.broadcastSuspend("Hello to everyone!")

    // Broadcast to a specific resource
    broadcaster.broadcastSuspend("Private message", targetResource)
}
```

Under the hood, `broadcastSuspend()` calls `broadcaster.broadcast()` and suspends on the returned `Future` using `kotlinx.coroutines.future.await()`. This means your coroutine does not block a thread while waiting for delivery.

#### writeSuspend()

```kotlin
import org.atmosphere.kotlin.writeSuspend
import kotlinx.coroutines.runBlocking

runBlocking {
    // Write text to a specific client, suspending until delivery
    resource.writeSuspend("Direct message to you")

    // Write binary data
    resource.writeSuspend(byteArrayOf(0x01, 0x02, 0x03))
}
```

### Using Coroutines with the DSL

You can combine the DSL builder with coroutines. Since the DSL callbacks are not suspending by default (they run on Atmosphere's thread pool), you need to launch a coroutine inside them:

```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.atmosphere.kotlin.atmosphere
import org.atmosphere.kotlin.broadcastSuspend

val scope = CoroutineScope(Dispatchers.Default)

val handler = atmosphere {
    onMessage { resource, message ->
        scope.launch {
            // Enrich the message asynchronously
            val enriched = enrichMessage(message)  // suspending function

            // Broadcast using the suspending extension
            resource.broadcaster.broadcastSuspend(enriched)
        }
    }
}

suspend fun enrichMessage(raw: String): String {
    // e.g., call an external API, query a database, etc.
    return "[$raw]"
}
```

### Kotlin with Spring Boot

The Kotlin DSL works seamlessly with the Spring Boot starter. Register your handler as a bean:

```kotlin
import org.atmosphere.kotlin.atmosphere
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AtmosphereConfig {

    @Bean
    fun chatHandler() = atmosphere {
        onConnect { resource ->
            println("${resource.uuid()} connected")
        }
        onMessage { resource, message ->
            resource.broadcaster.broadcast(message)
        }
    }
}
```

---

## Part 3: Virtual Threads

### Enabled by Default

Atmosphere 4.0 runs on JDK 21+ and takes full advantage of virtual threads. The `ExecutorsFactory` uses `Executors.newVirtualThreadPerTaskExecutor()` by default. This means:

- Every Atmosphere operation that would previously block a platform thread (HTTP long-polling waits, blocking I/O in handlers, etc.) now runs on a virtual thread.
- You can have thousands of concurrent long-polling connections without exhausting the platform thread pool.
- No configuration is needed -- it works out of the box.

### Non-Pinning Locks

A key concern with virtual threads is "pinning" -- when a virtual thread holds a `synchronized` block and cannot be unmounted from its carrier thread. Atmosphere avoids this by using `ReentrantLock` throughout its core:

```java
// In DefaultBroadcaster (simplified)
private final ReentrantLock broadcastLock = new ReentrantLock();

public Future<Object> broadcast(Object msg) {
    broadcastLock.lock();
    try {
        // ... broadcast logic
    } finally {
        broadcastLock.unlock();
    }
}
```

`ReentrantLock` is virtual-thread-friendly: when a virtual thread blocks on `lock()`, it unmounts from the carrier thread, freeing it for other virtual threads.

### Opting Out

If you need to disable virtual threads (e.g., for compatibility testing with older libraries), set:

```java
framework.addInitParameter(
    ApplicationConfig.USE_VIRTUAL_THREADS, "false");
```

Or in Spring Boot:

```yaml
atmosphere:
  init-params:
    org.atmosphere.useVirtualThreads: "false"
```

When disabled, Atmosphere falls back to `Executors.newCachedThreadPool()` with platform threads.

### What Still Uses Platform Threads

The `ScheduledExecutorService` used for timed tasks (heartbeat checks, cache expiration, etc.) remains on platform threads. This is expected -- `ScheduledThreadPoolExecutor` does not have a virtual-thread equivalent, and timed tasks are few in number and short-lived.

### Virtual Threads and gRPC

The gRPC transport also benefits from virtual threads. Each gRPC stream handler runs on a virtual thread, so even with thousands of concurrent gRPC streams, the server uses minimal platform threads.

---

## Where to Go from Here

You have completed the Atmosphere tutorial. Here is a map of where to explore next.

### Sample Applications

The `samples/` directory contains production-quality examples for every major feature:

| Sample | Description |
|---|---|
| `samples/chat/` | Embedded Jetty WebSocket chat |
| `samples/embedded-jetty-websocket-chat/` | Standalone Jetty with WebSocket |
| `samples/spring-boot-chat/` | Spring Boot chat with actuator metrics |
| `samples/quarkus-chat/` | Quarkus extension chat |
| `samples/spring-boot-otel-chat/` | OpenTelemetry tracing with Jaeger |

### Reference Documentation

The reference section provides compact API documentation for every module:

| Reference | What It Covers |
|---|---|
| [Core Runtime](/docs/reference/core/) | `AtmosphereHandler`, `Broadcaster`, `AtmosphereResource`, interceptors |
| [Rooms & Presence](/docs/reference/rooms/) | `RoomManager`, `@Room`, presence tracking |
| [AI Integration](/docs/reference/ai/) | `@AiEndpoint`, `StreamingSession`, `AiHandler` |
| [MCP Server](/docs/reference/mcp/) | `@McpTool`, `@McpResource`, `@McpPrompt` |
| [gRPC Transport](/docs/reference/grpc/) | `AtmosphereGrpcServer`, `GrpcHandler`, `GrpcChannel` |
| [Kotlin DSL](/docs/reference/kotlin/) | DSL builder, coroutine extensions |
| [Observability](/docs/reference/observability/) | Metrics, tracing, backpressure, cache |
| [Durable Sessions](/docs/reference/durable-sessions/) | Session persistence across restarts |

### Integration Guides

| Integration | What It Covers |
|---|---|
| [Spring Boot](/docs/integrations/spring-boot/) | Auto-configuration, actuator, properties |
| [Quarkus](/docs/integrations/quarkus/) | Extension, build-time processing, config |
| [Spring AI](/docs/integrations/spring-ai/) | ChatClient integration |
| [LangChain4j](/docs/integrations/langchain4j/) | Agent and tool integration |
| [Google ADK](/docs/integrations/adk/) | Agent Development Kit |

### Client Libraries

| Client | What It Covers |
|---|---|
| [atmosphere.js](/docs/clients/javascript/) | TypeScript client, React/Vue/Svelte hooks |
| [wAsync (Java)](/docs/clients/java/) | Java client for WebSocket, SSE, gRPC |
| [React Native](/docs/clients/react-native/) | Mobile client with Expo support |

### Community and Source Code

- **GitHub**: [https://github.com/Atmosphere/atmosphere](https://github.com/Atmosphere/atmosphere) -- source code, issues, and pull requests.
- **Discussions**: Use GitHub Discussions for questions and feature requests.
- **Stack Overflow**: Tag your questions with `atmosphere` for community support.

### Contributing

Atmosphere welcomes contributions. To get started:

1. Fork the repository on GitHub.
2. Set up your development environment (JDK 21+, Maven).
3. Run `git config core.hooksPath .githooks` to enable pre-commit hooks.
4. Build with `./mvnw install`.
5. Create a feature branch, make your changes, and submit a pull request.

See the project's `CLAUDE.md` for detailed coding standards, commit message format, and build verification steps.

---

## Summary

In this final chapter you learned:

- **gRPC transport** lets non-browser clients participate in the same broadcaster topology as WebSocket and SSE clients, using Protocol Buffers for efficient binary communication.
- **AtmosphereGrpcServer** provides a standalone gRPC server, and Spring Boot auto-configures it when `atmosphere.grpc.enabled=true`.
- **wAsync** supports gRPC as a first-class transport via the `grpc://` URI scheme.
- **Kotlin DSL** (`atmosphere { }`) offers a concise, type-safe way to define `AtmosphereHandler` instances with callback blocks.
- **Coroutine extensions** (`broadcastSuspend()`, `writeSuspend()`) bridge Atmosphere's `Future` API into Kotlin's structured concurrency.
- **Virtual threads** are enabled by default in Atmosphere 4.0, using `ReentrantLock` instead of `synchronized` to avoid pinning.

Thank you for working through the entire tutorial. Atmosphere gives you a single API for every transport -- WebSocket, SSE, long-polling, gRPC -- and a single broadcast primitive that reaches every subscriber. Build something great with it.

## See Also

- [gRPC Transport Reference](/docs/reference/grpc/)
- [Kotlin DSL Reference](/docs/reference/kotlin/)
- [atmosphere.js Client](/docs/clients/javascript/)
- [wAsync Java Client](/docs/clients/java/)
