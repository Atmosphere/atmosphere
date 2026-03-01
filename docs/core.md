# Core Runtime

The core framework for building real-time web applications in Java. Provides a portable, annotation-driven programming model that runs on any Servlet 6.0+ container with automatic transport negotiation.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-runtime</artifactId>
    <version>4.0.8-SNAPSHOT</version>
</dependency>
```

## Key Concepts

**Broadcaster** -- a named pub/sub channel. Call `broadcaster.broadcast(message)` and every subscribed resource receives it. Broadcasters support caching, filtering, and clustering (Redis, Kafka) out of the box.

**AtmosphereResource** -- a single connection. It wraps the underlying transport (WebSocket frame, SSE event stream, HTTP response, gRPC stream) behind a uniform API. Resources subscribe to Broadcasters.

**Transport** -- the wire protocol. Atmosphere ships with WebSocket, SSE, Long-Polling, gRPC, and MCP transports. The transport is selected per-connection and can fall back automatically (WebSocket -> SSE -> Long-Polling).

## Quick Start

```java
@ManagedService(path = "/chat")
public class Chat {

    @Inject
    private BroadcasterFactory factory;

    @Inject
    private AtmosphereResource r;

    @Ready
    public void onReady() {
        // client connected
    }

    @Disconnect
    public void onDisconnect() {
        // client left
    }

    @Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) {
        return message; // broadcasts to all subscribers
    }
}
```

## Annotations

| Annotation | Target | Description |
|-----------|--------|-------------|
| `@ManagedService` | Class | Marks a class as a managed endpoint with a path |
| `@Ready` | Method | Called when a client connects |
| `@Disconnect` | Method | Called when a client disconnects |
| `@Message` | Method | Called when a message is received; return value is broadcast |
| `@Heartbeat` | Method | Called on heartbeat events |
| `@Resume` | Method | Called when a suspended connection resumes |

## Servlet Configuration

Register `AtmosphereServlet` in `web.xml` or programmatically:

```xml
<servlet>
    <servlet-class>org.atmosphere.cpr.AtmosphereServlet</servlet-class>
    <init-param>
        <param-name>org.atmosphere.cpr.packages</param-name>
        <param-value>com.example.chat</param-value>
    </init-param>
    <load-on-startup>0</load-on-startup>
    <async-supported>true</async-supported>
</servlet>
```

## Virtual Threads

JDK 21 virtual threads are used by default. The `ExecutorsFactory` creates a `newVirtualThreadPerTaskExecutor()` and `DefaultBroadcaster` uses `ReentrantLock` (not `synchronized`) to avoid pinning.

Opt out with:

```
org.atmosphere.cpr.useVirtualThreads=false
```

## GraalVM Native Image

The runtime includes AOT hints for native image compilation. See the [Spring Boot](spring-boot.md) and [Quarkus](quarkus.md) docs for framework-specific native image instructions.

## Samples

- [WAR Chat](../samples/chat/) -- standard WAR deployment with `@ManagedService`
- [Embedded Jetty WebSocket Chat](../samples/embedded-jetty-websocket-chat/) -- programmatic Jetty with `@WebSocketHandlerService`

## See Also

- [Spring Boot Integration](spring-boot.md)
- [Quarkus Integration](quarkus.md)
- [Rooms & Presence](rooms.md)
- [Observability](observability.md)
- [Module README](../modules/cpr/README.md)
