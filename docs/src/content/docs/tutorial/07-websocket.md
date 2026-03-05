---
title: "Chapter 7: WebSocket Deep Dive"
description: "Low-level WebSocket handling with @WebSocketHandlerService, binary messages, and protocol customization"
---

In [Chapter 3](/docs/tutorial/03-managed-service/) you used `@ManagedService` -- a high-level, annotation-driven model that handles connection lifecycle, message routing, and broadcasting for you. For most applications, that is the right choice. But sometimes you need to work directly with the WebSocket transport: handling raw text and binary frames, managing the WebSocket lifecycle yourself, customizing the protocol layer, or integrating with a system that speaks a binary protocol.

This chapter covers `@WebSocketHandlerService` and the `WebSocketHandler` interface -- Atmosphere's lower-level WebSocket API.

## @ManagedService vs. @WebSocketHandlerService

Before diving in, understand when to use which:

| Feature | `@ManagedService` | `@WebSocketHandlerService` |
|---------|-------------------|----------------------------|
| Programming model | Annotation-driven (`@Ready`, `@Message`, `@Disconnect`) | Interface-driven (`onOpen`, `onTextMessage`, `onClose`) |
| Transport | Any (WebSocket, SSE, long-polling) | WebSocket only |
| Message encoding/decoding | Built-in via `@Message(encoders, decoders)` | Manual -- you parse and serialize yourself |
| Binary message support | Limited | Full control (`onByteMessage`) |
| Broadcaster integration | Automatic (return value from `@Message` is broadcast) | Manual (you call `broadcast()` yourself) |
| Default interceptors | Heartbeat, TrackMessageSize, Lifecycle, SuspendTracker | None (you add what you need) |
| Default cache | `UUIDBroadcasterCache` | None (`DefaultBroadcasterCache`, which is a no-op) |
| Use case | Chat, notifications, real-time dashboards | Binary protocols, custom framing, raw WebSocket control |

**Rule of thumb**: start with `@ManagedService`. Switch to `@WebSocketHandlerService` only if you need binary messages, custom protocol handling, or explicit control over the WebSocket lifecycle.

## WebSocketHandler Interface

The core interface has five methods:

```java
public interface WebSocketHandler {

    void onOpen(WebSocket webSocket) throws IOException;

    void onClose(WebSocket webSocket);

    void onTextMessage(WebSocket webSocket, String data) throws IOException;

    void onByteMessage(WebSocket webSocket, byte[] data, int offset, int length)
        throws IOException;

    void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t);
}
```

| Method | When it fires |
|--------|---------------|
| `onOpen` | WebSocket handshake completed, connection is open |
| `onClose` | WebSocket closed (client or server initiated) |
| `onTextMessage` | A text frame was received |
| `onByteMessage` | A binary frame was received |
| `onError` | A WebSocket error occurred (protocol error, I/O failure) |

### WebSocketHandlerAdapter

Rather than implementing all five methods, extend the adapter class which provides empty implementations:

```java
public class WebSocketHandlerAdapter implements WebSocketHandler {
    public void onOpen(WebSocket webSocket) throws IOException {}
    public void onClose(WebSocket webSocket) {}
    public void onTextMessage(WebSocket webSocket, String data) throws IOException {}
    public void onByteMessage(WebSocket webSocket, byte[] data, int offset, int length) {}
    public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) {}
}
```

Override only the methods you care about.

## Your First @WebSocketHandlerService

A simple echo server that sends back whatever the client sends:

```java
@WebSocketHandlerService(path = "/echo")
public class EchoHandler extends WebSocketHandlerAdapter {

    @Override
    public void onTextMessage(WebSocket webSocket, String data) throws IOException {
        webSocket.write(data);
    }
}
```

That is the minimal example. The `@WebSocketHandlerService` annotation registers this handler at the `/echo` path. Atmosphere routes incoming WebSocket connections to this handler and invokes the appropriate method for each frame.

### Chat With Manual Broadcasting

Unlike `@ManagedService`, broadcasting is not automatic. You must do it yourself:

```java
@WebSocketHandlerService(
    path = "/ws-chat",
    broadcasterCache = UUIDBroadcasterCache.class
)
public class WsChatHandler extends WebSocketHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(WsChatHandler.class);

    @Override
    public void onOpen(WebSocket webSocket) {
        AtmosphereResource r = webSocket.resource();
        Broadcaster b = r.getBroadcaster();
        log.info("Client {} connected", r.uuid());
        b.broadcast("User " + r.uuid() + " joined");
    }

    @Override
    public void onClose(WebSocket webSocket) {
        AtmosphereResource r = webSocket.resource();
        log.info("Client {} disconnected", r.uuid());
        r.getBroadcaster().broadcast("User " + r.uuid() + " left");
    }

    @Override
    public void onTextMessage(WebSocket webSocket, String data) {
        AtmosphereResource r = webSocket.resource();
        r.getBroadcaster().broadcast(data);
    }

    @Override
    public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) {
        log.error("WebSocket error for {}", webSocket.resource().uuid(), t);
    }
}
```

Key differences from `@ManagedService`:

1. You get the `Broadcaster` from `webSocket.resource().getBroadcaster()`
2. You call `broadcast()` explicitly
3. There is no auto-suspend -- the resource is automatically suspended for WebSocket connections
4. No default interceptors are installed unless you specify them

## The WebSocket Object

The `WebSocket` object is your handle to the underlying WebSocket connection. It provides methods for writing data back to the client:

```java
// Write a text frame
webSocket.write("Hello");

// Write binary data
byte[] data = ...;
webSocket.write(data, 0, data.length);

// Access the underlying AtmosphereResource
AtmosphereResource resource = webSocket.resource();

// Get the request that initiated the WebSocket handshake
AtmosphereRequest request = resource.getRequest();

// Get the response (for setting headers during handshake)
AtmosphereResponse response = resource.getResponse();

// Access the Broadcaster this resource is subscribed to
Broadcaster broadcaster = resource.getBroadcaster();

// Get the client's UUID
String uuid = resource.uuid();

// Attach custom data to the WebSocket
webSocket.attachment(mySessionData);
Object data = webSocket.attachment();
```

### Accessing Handshake Information

WebSocket connections start as HTTP requests. You can access headers, parameters, and session data from the original handshake:

```java
@Override
public void onOpen(WebSocket webSocket) {
    AtmosphereRequest request = webSocket.resource().getRequest();

    // Query parameters from the WebSocket URL
    String token = request.getParameter("token");

    // HTTP headers from the handshake
    String origin = request.getHeader("Origin");

    // Session data (if sessions are enabled)
    var session = request.getSession(false);
    if (session != null) {
        var user = session.getAttribute("user");
    }
}
```

## Binary Message Handling

One of the primary reasons to use `@WebSocketHandlerService` is binary message support. WebSockets natively support both text and binary frames, and Atmosphere exposes this through `onByteMessage`.

### Example: Image Relay

Broadcast binary images to all connected clients:

```java
@WebSocketHandlerService(path = "/image-share")
public class ImageShareHandler extends WebSocketHandlerAdapter {

    @Override
    public void onByteMessage(WebSocket webSocket, byte[] data, int offset, int length)
            throws IOException {
        // Validate it looks like an image
        if (length < 4) return;

        // Forward to all other connected clients
        AtmosphereResource sender = webSocket.resource();
        Broadcaster b = sender.getBroadcaster();

        // Create a copy of the relevant portion
        byte[] image = new byte[length];
        System.arraycopy(data, offset, image, 0, length);

        // Broadcast to everyone except sender
        b.getAtmosphereResources().stream()
            .filter(r -> !r.uuid().equals(sender.uuid()))
            .forEach(r -> {
                try {
                    r.write(image);
                } catch (IOException e) {
                    // Client may have disconnected
                }
            });
    }
}
```

### Example: Binary Protocol (Protocol Buffers)

If your application uses a binary protocol like Protocol Buffers:

```java
@WebSocketHandlerService(path = "/proto")
public class ProtobufHandler extends WebSocketHandlerAdapter {

    @Override
    public void onByteMessage(WebSocket webSocket, byte[] data, int offset, int length)
            throws IOException {
        // Deserialize the incoming protobuf message
        byte[] payload = Arrays.copyOfRange(data, offset, offset + length);
        var request = GameAction.parseFrom(payload);

        // Process the action
        var response = gameEngine.processAction(request);

        // Serialize and send back
        byte[] responseBytes = response.toByteArray();
        webSocket.write(responseBytes, 0, responseBytes.length);
    }
}
```

### Enabling Binary Write Mode

By default, Atmosphere writes text frames. To make `broadcast()` send binary frames, set the configuration property:

```xml
<init-param>
    <param-name>org.atmosphere.websocket.binaryWrite</param-name>
    <param-value>true</param-value>
</init-param>
```

Or per-request:

```java
request.setAttribute(ApplicationConfig.WEBSOCKET_BINARY_WRITE, "true");
```

## WebSocket Streaming

For large payloads, `WebSocketStreamingHandler` extends `WebSocketHandler` with streaming variants:

```java
public interface WebSocketStreamingHandler extends WebSocketHandler {

    void onBinaryStream(WebSocket webSocket, InputStream inputStream) throws IOException;

    void onTextStream(WebSocket webSocket, Reader reader) throws IOException;
}
```

Use `WebSocketStreamingHandlerAdapter` as a base class:

```java
@WebSocketHandlerService(path = "/upload")
public class FileUploadHandler extends WebSocketStreamingHandlerAdapter {

    @Override
    public void onBinaryStream(WebSocket webSocket, InputStream inputStream)
            throws IOException {
        // Stream directly to disk without buffering the entire payload in memory
        Path destination = Path.of("/uploads", UUID.randomUUID() + ".bin");
        try (var out = Files.newOutputStream(destination)) {
            inputStream.transferTo(out);
        }
        webSocket.write("Upload complete: " + destination.getFileName());
    }
}
```

## WebSocketProtocol: Message Framing

The `WebSocketProtocol` interface sits between the raw WebSocket transport and Atmosphere's request dispatch. It parses incoming WebSocket messages into `AtmosphereRequest` objects that can be routed through the interceptor chain.

### The Default Protocol

Atmosphere ships with `SimpleHttpProtocol`, which treats each WebSocket message as an HTTP-like request body. For most applications, this is sufficient.

### Custom Protocol

If your application uses a custom wire format (e.g., a multiplexed protocol with channel IDs embedded in each message), you can implement `WebSocketProtocol`:

```java
public interface WebSocketProtocol extends AtmosphereConfigAware {

    List<AtmosphereRequest> onMessage(WebSocket webSocket, String data);

    List<AtmosphereRequest> onMessage(WebSocket webSocket, byte[] data, int offset, int length);

    void onOpen(WebSocket webSocket);

    void onClose(WebSocket webSocket);

    void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t);
}
```

Register it with the `@WebSocketProtocolService` annotation or via configuration:

```xml
<init-param>
    <param-name>org.atmosphere.websocket.WebSocketProtocol</param-name>
    <param-value>com.example.MyCustomProtocol</param-value>
</init-param>
```

### Example: Multiplexed Channel Protocol

A protocol that routes messages to different Broadcasters based on a channel prefix:

```java
@WebSocketProtocolService
public class MultiplexProtocol implements WebSocketProtocol {

    private AtmosphereConfig config;

    @Override
    public void configure(AtmosphereConfig config) {
        this.config = config;
    }

    @Override
    public List<AtmosphereRequest> onMessage(WebSocket webSocket, String data) {
        // Protocol: "CHANNEL:message"
        int sep = data.indexOf(':');
        if (sep < 0) return List.of();

        String channel = data.substring(0, sep);
        String message = data.substring(sep + 1);

        // Create a request targeted at the channel's path
        var request = AtmosphereRequestImpl.newInstance()
            .pathInfo("/" + channel)
            .method("POST")
            .body(message)
            .build();

        return List.of(request);
    }

    @Override
    public List<AtmosphereRequest> onMessage(WebSocket webSocket, byte[] data,
                                              int offset, int length) {
        // Delegate to text handler after decoding
        String text = new String(data, offset, length, StandardCharsets.UTF_8);
        return onMessage(webSocket, text);
    }

    @Override
    public void onOpen(WebSocket webSocket) {}

    @Override
    public void onClose(WebSocket webSocket) {}

    @Override
    public void onError(WebSocket webSocket,
                         WebSocketProcessor.WebSocketException t) {}
}
```

## Annotation Configuration

`@WebSocketHandlerService` accepts several configuration attributes:

```java
@WebSocketHandlerService(
    path = "/ws",

    // Broadcaster implementation
    broadcaster = DefaultBroadcaster.class,

    // Cache for offline message delivery
    broadcasterCache = UUIDBroadcasterCache.class,

    // Message filters
    broadcastFilters = {JsonEnvelopeFilter.class},

    // Interceptors (appended to the default set)
    interceptors = {HeartbeatInterceptor.class, BackpressureInterceptor.class},

    // Inline Atmosphere config
    atmosphereConfig = {
        "org.atmosphere.cpr.broadcasterLifeCyclePolicy=EMPTY_DESTROY"
    },

    // Event listeners
    listeners = {MyWebSocketEventListener.class}
)
public class ConfiguredWsHandler extends WebSocketHandlerAdapter {
    // ...
}
```

### atmosphereConfig

The `atmosphereConfig` attribute lets you set Atmosphere init-params inline without `web.xml`:

```java
@WebSocketHandlerService(
    path = "/ws",
    atmosphereConfig = {
        "org.atmosphere.websocket.binaryWrite=true",
        "org.atmosphere.cpr.broadcasterLifeCyclePolicy=IDLE_DESTROY",
        "org.atmosphere.interceptor.HeartbeatInterceptor.heartbeatFrequencyInSeconds=30"
    }
)
```

## JSR 356 Integration

Atmosphere integrates with the Java WebSocket API (JSR 356 / Jakarta WebSocket). The container's WebSocket implementation is used as the underlying transport, while Atmosphere provides the higher-level programming model on top.

### How It Works

When running on a JSR 356-capable container (Jetty, Tomcat, Undertow), Atmosphere uses JSR 356 as the WebSocket transport layer by default. You do not need to write JSR 356 endpoints -- Atmosphere creates them internally and routes messages to your `WebSocketHandler` or `@ManagedService`.

### Configuration

Atmosphere auto-detects JSR 356 support. To explicitly enable or disable it:

```xml
<!-- Force JSR 356 -->
<init-param>
    <param-name>org.atmosphere.websocket.suppressJSR356</param-name>
    <param-value>false</param-value>
</init-param>

<!-- Disable JSR 356 (use container-native WebSocket support instead) -->
<init-param>
    <param-name>org.atmosphere.websocket.suppressJSR356</param-name>
    <param-value>true</param-value>
</init-param>
```

### Coexisting with JSR 356 Endpoints

If your application also has native JSR 356 `@ServerEndpoint` classes, they coexist with Atmosphere endpoints. Each handles its own path independently.

## WebSocket-Specific Configuration

These init-params control WebSocket behavior:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.websocket.maxTextMessageSize` | -1 (unlimited) | Maximum size of a text message in bytes |
| `org.atmosphere.websocket.maxBinaryMessageSize` | -1 (unlimited) | Maximum size of a binary message in bytes |
| `org.atmosphere.websocket.maxIdleTime` | 300000 (5 min) | Idle timeout in milliseconds before the WebSocket is closed |
| `org.atmosphere.websocket.binaryWrite` | false | Write binary frames instead of text frames |
| `org.atmosphere.websocket.bufferSize` | 8192 | Internal buffer size for WebSocket writes |
| `org.atmosphere.websocket.WebSocketProtocol` | `SimpleHttpProtocol` | The `WebSocketProtocol` implementation class |
| `org.atmosphere.websocket.suppressJSR356` | false | Disable JSR 356 integration |

## Error Handling

The `onError` method receives a `WebSocketProcessor.WebSocketException` which wraps the underlying cause:

```java
@Override
public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) {
    AtmosphereResource r = webSocket.resource();
    log.error("WebSocket error for client {}: {}", r.uuid(), t.getMessage());

    Throwable cause = t.getCause();
    if (cause instanceof IOException) {
        log.warn("I/O error, client likely disconnected: {}", cause.getMessage());
    } else {
        log.error("Unexpected WebSocket error", cause);
    }
}
```

## Complete Example: Collaborative Drawing

A binary-protocol drawing application where clients send draw commands as compact binary messages:

```java
@WebSocketHandlerService(
    path = "/draw",
    broadcasterCache = UUIDBroadcasterCache.class,
    interceptors = {HeartbeatInterceptor.class}
)
public class DrawingHandler extends WebSocketHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DrawingHandler.class);

    // In-memory canvas state for replay
    private final List<byte[]> canvasHistory = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_HISTORY = 10_000;

    @Override
    public void onOpen(WebSocket webSocket) throws IOException {
        log.info("Artist {} joined", webSocket.resource().uuid());

        // Replay current canvas state to new joiner
        synchronized (canvasHistory) {
            for (byte[] stroke : canvasHistory) {
                webSocket.write(stroke, 0, stroke.length);
            }
        }
    }

    @Override
    public void onByteMessage(WebSocket webSocket, byte[] data, int offset, int length)
            throws IOException {
        // Binary protocol: [1 byte command][2 bytes x][2 bytes y][3 bytes color][1 byte size]
        if (length < 9) return;

        byte[] stroke = new byte[length];
        System.arraycopy(data, offset, stroke, 0, length);

        // Store for replay
        canvasHistory.add(stroke);
        if (canvasHistory.size() > MAX_HISTORY) {
            canvasHistory.removeFirst();
        }

        // Broadcast to all other artists
        AtmosphereResource sender = webSocket.resource();
        Broadcaster b = sender.getBroadcaster();
        var others = b.getAtmosphereResources().stream()
            .filter(r -> !r.uuid().equals(sender.uuid()))
            .collect(Collectors.toSet());
        b.broadcast(stroke, others);
    }

    @Override
    public void onTextMessage(WebSocket webSocket, String data) throws IOException {
        // Text messages are used for control commands
        if ("CLEAR".equals(data)) {
            canvasHistory.clear();
            webSocket.resource().getBroadcaster().broadcast("CLEAR");
        }
    }

    @Override
    public void onClose(WebSocket webSocket) {
        log.info("Artist {} left", webSocket.resource().uuid());
    }
}
```

## Summary

| Concept | Purpose |
|---------|---------|
| `WebSocketHandler` | Interface for handling WebSocket lifecycle and messages |
| `WebSocketHandlerAdapter` | Convenience base class with empty method implementations |
| `@WebSocketHandlerService` | Annotation to register a handler at a path with configuration |
| `WebSocket` | The connection object for reading/writing frames |
| `WebSocketStreamingHandler` | Extension for streaming large payloads via `InputStream`/`Reader` |
| `WebSocketProtocol` | Custom message framing and routing layer |
| `onByteMessage` | Binary frame handler for protocols beyond text |

### When to Use @WebSocketHandlerService

- You need to handle **binary messages** (images, protobuf, custom binary protocols)
- You need **full control** over the WebSocket lifecycle (custom handshake logic, per-frame processing)
- You are building a **custom protocol** on top of WebSocket (multiplexing, sub-protocols)
- You want **minimal overhead** -- no default interceptors, no auto-broadcasting
- You are integrating with a **system that speaks WebSocket natively** and you need to bridge it

### When to Stay with @ManagedService

- You are building a **chat, notification, or real-time dashboard** application
- You want **transport negotiation** (WebSocket with SSE/long-polling fallback)
- You want **built-in message encoding/decoding** with `@Message(encoders, decoders)`
- You want **sensible defaults** (heartbeat, message tracking, cache, lifecycle management)
- You prefer the **annotation-driven** programming model

In the [next chapter](/docs/tutorial/08-interceptors/), you will learn about interceptors -- the middleware layer that sits between the transport and your handler, providing cross-cutting concerns like heartbeats, rate limiting, backpressure, and authentication.
