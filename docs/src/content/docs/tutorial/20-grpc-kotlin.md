---
title: "Chapter 20: gRPC, Kotlin DSL & Virtual Threads"
description: "gRPC transport with AtmosphereGrpcServer, Kotlin DSL with coroutine extensions, and virtual thread support for scalable concurrency."
sidebar:
  order: 20
---

This final chapter covers three features that extend Atmosphere beyond the traditional servlet-and-browser model: the **gRPC transport** for high-performance binary streaming, the **Kotlin DSL** for expressive server-side code with coroutine support, and **virtual threads** for scalable concurrency on JDK 21+.

## gRPC Transport

The `atmosphere-grpc` module provides a standalone gRPC server integrated with `AtmosphereFramework`. It exposes the Atmosphere programming model over gRPC bidirectional streaming, using Protocol Buffers for message framing.

### Dependency

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-grpc</artifactId>
    <version>4.0.11-SNAPSHOT</version>
</dependency>
```

### AtmosphereGrpcServer

`AtmosphereGrpcServer` uses a builder pattern and implements `AutoCloseable`:

```java
AtmosphereGrpcServer server = AtmosphereGrpcServer.builder()
    .framework(framework)       // required: AtmosphereFramework instance
    .port(9090)                 // default: 9090
    .handler(new ChatHandler()) // default: no-op GrpcHandlerAdapter
    .enableReflection(true)     // default: true (gRPC reflection service)
    .interceptor(myInterceptor) // optional: gRPC ServerInterceptor
    .build();

server.start();
server.awaitTermination();
server.close(); // or use try-with-resources
```

The builder initializes the `AtmosphereFramework` if it has not been initialized yet, creates a `GrpcProcessor` that bridges gRPC streams to the Atmosphere request/response model, and registers the `AtmosphereGrpcService` with the gRPC server.

Key methods:

| Method | Description |
|--------|-------------|
| `start()` | Start the gRPC server |
| `awaitTermination()` | Block until the server shuts down |
| `port()` | Return the listening port |
| `close()` | Shut down gracefully (5-second timeout) |

### GrpcHandler Interface

The `GrpcHandler` interface defines lifecycle callbacks, patterned after WebSocketHandler:

```java
public interface GrpcHandler {
    void onOpen(GrpcChannel channel);
    void onMessage(GrpcChannel channel, String message);
    void onBinaryMessage(GrpcChannel channel, byte[] data);
    void onClose(GrpcChannel channel);
    void onError(GrpcChannel channel, Throwable t);
}
```

Extend `GrpcHandlerAdapter` and override only the methods you need:

```java
public class GrpcHandlerAdapter implements GrpcHandler {
    public void onOpen(GrpcChannel channel) { }
    public void onMessage(GrpcChannel channel, String message) { }
    public void onBinaryMessage(GrpcChannel channel, byte[] data) { }
    public void onClose(GrpcChannel channel) { }
    public void onError(GrpcChannel channel, Throwable t) { }
}
```

### GrpcChannel

`GrpcChannel` wraps a gRPC `StreamObserver` for outbound messages. It is analogous to a WebSocket session:

| Method | Description |
|--------|-------------|
| `uuid()` | Unique channel identifier |
| `write(String)` | Send a text message |
| `write(byte[])` | Send a binary message |
| `write(String topic, String data)` | Send a text message to a specific topic |
| `write(String topic, byte[] data)` | Send a binary message to a specific topic |
| `isOpen()` | Check if the channel is still open |
| `close()` | Close the channel |
| `resource()` | Access the underlying `AtmosphereResource` |

All `write()` methods throw `IOException` if the channel is closed.

### Complete Example: gRPC Chat Server

From `samples/grpc-chat/`:

**GrpcChatServer.java:**

```java
public class GrpcChatServer {

    private static final Logger logger = LoggerFactory.getLogger(GrpcChatServer.class);

    public static void main(String[] args) throws Exception {
        var framework = new AtmosphereFramework();
        framework.setBroadcasterCacheClassName(
                "org.atmosphere.cache.UUIDBroadcasterCache");

        try (var server = AtmosphereGrpcServer.builder()
                .framework(framework)
                .port(9090)
                .handler(new ChatHandler())
                .build()) {

            server.start();
            logger.info("gRPC Chat server listening on port {}", server.port());
            logger.info("Use grpcurl or a gRPC client to connect.");

            server.awaitTermination();
        }
    }
}
```

**ChatHandler.java:**

```java
public class ChatHandler extends GrpcHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);

    @Override
    public void onOpen(GrpcChannel channel) {
        logger.info("Client connected: {}", channel.uuid());
        try {
            channel.write("Welcome to Atmosphere gRPC Chat! Your ID: " + channel.uuid());
        } catch (java.io.IOException e) {
            logger.warn("Failed to send welcome message to {}", channel.uuid(), e);
        }
    }

    @Override
    public void onMessage(GrpcChannel channel, String message) {
        logger.info("Message from {}: {}", channel.uuid(), message);
        // The message will be broadcast to all subscribers by GrpcProcessor
    }

    @Override
    public void onClose(GrpcChannel channel) {
        logger.info("Client disconnected: {}", channel.uuid());
    }
}
```

### Testing with grpcurl

The server enables gRPC reflection by default, so grpcurl can discover services automatically:

```bash
# Subscribe to a topic
grpcurl -plaintext -d '{"type":"SUBSCRIBE","topic":"/chat"}' \
  localhost:9090 atmosphere.AtmosphereService/Stream
```

### Spring Boot Auto-Configuration

The `atmosphere-spring-boot-starter` includes `AtmosphereGrpcAutoConfiguration` that automatically creates an `AtmosphereGrpcServer` bean when the gRPC classes are on the classpath.

### Mixed Transport

With both the servlet container and gRPC server running, clients can connect over any transport -- WebSocket, SSE, long-polling via HTTP, or gRPC via HTTP/2 -- and they all share the same Broadcasters:

```java
@ManagedService(path = "/chat")
public class Chat {
    @Message
    public String onMessage(String message) {
        return message; // Broadcast reaches ALL clients — WebSocket, SSE, AND gRPC
    }
}
```

This means a WebSocket browser client, an SSE mobile client, and a gRPC microservice can all participate in the same conversation through the same `@ManagedService` endpoint. The Broadcaster abstraction handles delivery to each transport transparently.

### Java Client (wAsync) gRPC

The [Java client (wAsync)](/docs/tutorial/19-client/#java-client-wasync) supports gRPC as a transport. Use the `grpc://` URI scheme:

```java
Client client = Client.newClient();
Request request = client.newRequestBuilder()
    .uri("grpc://localhost:9090/chat")
    .transport(Request.TRANSPORT.GRPC)
    .build();

Socket socket = client.create();
socket.on(Event.MESSAGE, msg -> System.out.println("Received: " + msg))
      .open(request);

socket.fire("Hello via gRPC!");
```

## Kotlin DSL

The `atmosphere-kotlin` module provides a type-safe DSL for building `AtmosphereHandler` instances and coroutine extensions for Atmosphere's core types.

### Dependency

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-kotlin</artifactId>
    <version>4.0.11-SNAPSHOT</version>
</dependency>
```

### The atmosphere { } DSL

The `atmosphere` function creates an `AtmosphereHandler` using lambda callbacks:

```kotlin
val handler = atmosphere {
    onConnect { resource ->
        resource.broadcaster.broadcast("${resource.uuid()} joined")
    }
    onMessage { resource, message ->
        resource.broadcaster.broadcast(message)
    }
    onDisconnect { resource ->
        resource.broadcaster.broadcast("${resource.uuid()} left")
    }
    onTimeout { resource ->
        println("Resource ${resource.uuid()} timed out")
    }
    onResume { resource ->
        println("Resource ${resource.uuid()} resumed")
    }
}

framework.addAtmosphereHandler("/chat", handler)
```

The DSL builder creates a `DslAtmosphereHandler` that dispatches to callbacks based on the request type and lifecycle events.

**Available callbacks:**

| Callback | When it fires |
|----------|---------------|
| `onConnect` | Client connects (GET request, resource is suspended) |
| `onMessage` | Message received (POST request, body parsed as String) |
| `onDisconnect` | Client disconnects or connection closed |
| `onTimeout` | Resource times out |
| `onResume` | Resource is resumed |

All callbacks are optional -- override only what you need.

### Coroutine Extensions

The `CoroutineExtensions.kt` file provides suspending versions of blocking Atmosphere operations:

**Broadcaster.broadcastSuspend()** -- suspending broadcast that waits for delivery:

```kotlin
suspend fun handleMessage(broadcaster: Broadcaster, msg: String) {
    broadcaster.broadcastSuspend(msg)
    println("Delivered to all clients")
}
```

**Broadcaster.broadcastSuspend(message, resource)** -- suspending broadcast to a specific resource:

```kotlin
suspend fun sendTo(broadcaster: Broadcaster, msg: String, target: AtmosphereResource) {
    broadcaster.broadcastSuspend(msg, target)
}
```

**AtmosphereResource.writeSuspend()** -- suspending write (text or binary):

```kotlin
suspend fun respond(resource: AtmosphereResource) {
    resource.writeSuspend("Hello from coroutine")
}
```

All coroutine extensions use `Dispatchers.IO` to avoid blocking the caller's coroutine context.

### Full Kotlin Chat Server

Combining the DSL with gRPC:

```kotlin
fun main() {
    val framework = AtmosphereFramework()
    framework.setBroadcasterCacheClassName(
        "org.atmosphere.cache.UUIDBroadcasterCache")

    val handler = atmosphere {
        onConnect { resource ->
            resource.broadcaster.broadcast("${resource.uuid()} joined")
        }
        onMessage { resource, message ->
            resource.broadcaster.broadcast(message)
        }
        onDisconnect { resource ->
            resource.broadcaster.broadcast("${resource.uuid()} left")
        }
    }

    framework.addAtmosphereHandler("/chat", handler)
}
```

### Java Annotations in Kotlin

All Atmosphere annotations (`@ManagedService`, `@Ready`, `@Message`, `@Disconnect`) work in Kotlin classes as-is:

```kotlin
@ManagedService(path = "/atmosphere/chat")
class Chat {

    @Inject
    lateinit var r: AtmosphereResource

    @Ready
    fun onReady() {
        println("Client ${r.uuid()} connected")
    }

    @Message
    fun onMessage(message: String): String {
        return message
    }
}
```

### Spring Boot Usage

Use the DSL to programmatically register handlers in a Spring Boot application via `AtmosphereFrameworkCustomizer`:

```kotlin
@Configuration
class AtmosphereConfig {

    @Bean
    fun customizeFramework(): AtmosphereFrameworkCustomizer {
        return AtmosphereFrameworkCustomizer { framework ->
            framework.addAtmosphereHandler("/notifications", atmosphere {
                onConnect { resource ->
                    resource.suspend()
                }
                onMessage { resource, message ->
                    resource.broadcaster.broadcast(message)
                }
            })
        }
    }
}
```

For most use cases, the annotation-driven approach with `@ManagedService` or `@RoomService` is simpler. The Kotlin DSL is best for dynamic handler registration, testing, or when you prefer a programmatic style over annotations.

## Virtual Threads

Atmosphere uses virtual threads by default on JDK 21+. This is not a feature you need to enable -- it is the default behavior.

### How It Works

`ExecutorsFactory` creates `Executors.newVirtualThreadPerTaskExecutor()` for the async write pool and broadcaster thread pool. This means:

- Every broadcast delivery runs on a virtual thread
- Every async I/O operation runs on a virtual thread
- Thousands of concurrent connections use a small number of platform threads

Only the `ScheduledExecutorService` (used for timed tasks like heartbeat and session cleanup) remains on platform threads, because `Executors.newVirtualThreadPerTaskExecutor()` does not support scheduled execution.

### Pinning Prevention

`DefaultBroadcaster` uses `ReentrantLock` instead of `synchronized` blocks to avoid virtual thread pinning. When a virtual thread enters a `synchronized` block, it pins its carrier platform thread, negating the scalability benefit. `ReentrantLock` does not have this limitation.

### Opting Out

If you need to disable virtual threads (for debugging or compatibility), set the configuration property:

```properties
org.atmosphere.useVirtualThreads=false
```

This is the `ApplicationConfig.USE_VIRTUAL_THREADS` constant. When set to `false`, `ExecutorsFactory` falls back to traditional platform thread pools.

### Kafka Consumer Thread

The `KafkaBroadcaster` consumer loop runs on a virtual thread created via `Thread.ofVirtual()`:

```java
consumerThread = Thread.ofVirtual()
    .name("atmosphere-kafka-consumer-" + getID())
    .start(this::consumeLoop);
```

This means Kafka message consumption does not consume a platform thread even when idle.

### Session Cleanup Thread

The `DurableSessionInterceptor` cleanup scheduler uses a platform thread (via `Thread.ofPlatform().daemon()`) because it needs `ScheduledExecutorService` for periodic execution:

```java
Thread.ofPlatform().daemon().unstarted(r);
```

This is the correct design: virtual threads for I/O-bound work, platform threads for scheduling.

## Summary

- **`AtmosphereGrpcServer`** provides a builder-based gRPC server integrated with `AtmosphereFramework`, supporting bidirectional streaming via `GrpcHandler` and `GrpcChannel`
- **`GrpcHandlerAdapter`** is the base class for gRPC handlers with `onOpen`, `onMessage`, `onBinaryMessage`, `onClose`, and `onError` callbacks
- **Mixed transport**: WebSocket, SSE, long-polling, and gRPC clients all share the same Broadcasters -- a single `@ManagedService` endpoint serves every transport
- The **Java client (wAsync)** connects to gRPC servers via the `grpc://` URI scheme with `Request.TRANSPORT.GRPC`
- The **Kotlin DSL** (`atmosphere { }`) creates `AtmosphereHandler` instances with lambda callbacks; coroutine extensions (`broadcastSuspend`, `writeSuspend`) bridge blocking Atmosphere operations to suspending functions
- Use `AtmosphereFrameworkCustomizer` to register DSL handlers in **Spring Boot** applications
- **Virtual threads** are enabled by default: `ExecutorsFactory` uses `newVirtualThreadPerTaskExecutor()`, `DefaultBroadcaster` uses `ReentrantLock` to avoid pinning, and `KafkaBroadcaster` runs its consumer on a virtual thread
- Disable virtual threads with `org.atmosphere.useVirtualThreads=false` if needed
