# Atmosphere Runtime

The core framework for building real-time web applications in Java. Provides a portable, annotation-driven programming model that runs on any Servlet 6.0+ container with automatic transport negotiation.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-runtime</artifactId>
    <version>4.0.2</version>
</dependency>
```

## Key Features

- **WebSocket** with automatic fallback to SSE and long-polling
- **`@ManagedService`** annotation-driven endpoints with `@Ready`, `@Disconnect`, `@Message`
- **Rooms** -- `RoomManager`, `@RoomService`, presence tracking, message history
- **Virtual threads** enabled by default (JDK 21+)
- **Broadcasting** -- pub/sub via `Broadcaster` and `BroadcasterFactory`
- **Micrometer and OpenTelemetry** observability (optional)
- **GraalVM Native Image** compatible

## Minimal Example

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

## Configuration

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

## Samples

- [WAR Chat](../../samples/chat/) -- standard WAR deployment with `@ManagedService`
- [Embedded Jetty WebSocket Chat](../../samples/embedded-jetty-websocket-chat/) -- programmatic Jetty with `@WebSocketHandlerService`

## Requirements

- Java 21+
- Servlet 6.0+ container (Jetty 12, Tomcat 11, Undertow, etc.)

## Building

```bash
./mvnw install -pl modules/cpr
```
