---
title: "Chapter 7: WebSocket Deep Dive"
description: "Low-level WebSocket handling with @WebSocketHandlerService, binary messages, and protocol customization"
sidebar:
  order: 7
---

In [Chapter 3](/docs/tutorial/03-managed-service/) you used `@ManagedService` -- a high-level, annotation-driven model that handles connection lifecycle, message routing, and broadcasting for you. For most applications, that is the right choice. But sometimes you need to work directly with the WebSocket transport: handling raw text and binary frames, managing the WebSocket lifecycle yourself, customizing the protocol layer, or integrating with a system that speaks a binary protocol.

This chapter covers `@WebSocketHandlerService` and the `WebSocketHandler` interface -- Atmosphere's lower-level WebSocket API.

## @ManagedService vs. @WebSocketHandlerService

| Feature | `@ManagedService` | `@WebSocketHandlerService` |
|---------|-------------------|---------------------------|
| Abstraction | High-level annotations (`@Ready`, `@Message`, `@Disconnect`) | Direct handler methods (`onOpen`, `onTextMessage`, `onClose`) |
| Transport | Automatic fallback (WebSocket -> SSE -> long-polling) | WebSocket-only (with optional HTTP fallback via interceptors) |
| Message format | String/JSON with encoders/decoders | Raw text frames, binary frames, or streaming readers |
| Binary messages | Not directly supported | `onByteMessage(webSocket, data, offset, length)` |
| Streaming | Not directly supported | `onTextStream(webSocket, Reader)`, `onBinaryStream(webSocket, InputStream)` |
| Broadcasting | Via `@DeliverTo` or `Broadcaster` | Via `webSocket.broadcast(Object)` |

Use `@WebSocketHandlerService` when you need binary frames, streaming readers, or protocol-level control. Use `@ManagedService` for everything else.

## The WebSocketHandler Interface

The core interface defines five methods:

```java
public interface WebSocketHandler {

    void onOpen(WebSocket webSocket) throws IOException;

    void onClose(WebSocket webSocket);

    void onTextMessage(WebSocket webSocket, String data) throws IOException;

    void onByteMessage(WebSocket webSocket, byte[] data, int offset, int length) throws IOException;

    void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t);
}
```

All five methods must be implemented. In practice, most handlers only care about a few, so Atmosphere provides adapter classes.

## WebSocketStreamingHandlerAdapter

`WebSocketStreamingHandlerAdapter` implements both `WebSocketHandler` and `WebSocketStreamingHandler`, providing no-op defaults for all methods. It adds two streaming methods beyond the base `WebSocketHandler`:

```java
public class WebSocketStreamingHandlerAdapter implements WebSocketStreamingHandler {

    void onOpen(WebSocket webSocket) throws IOException { }
    void onClose(WebSocket webSocket) { }
    void onTextMessage(WebSocket webSocket, String data) throws IOException { }
    void onByteMessage(WebSocket webSocket, byte[] data, int offset, int length) { }
    void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) { }

    // Streaming additions
    void onTextStream(WebSocket webSocket, Reader reader) { }
    void onBinaryStream(WebSocket webSocket, InputStream inputStream) { }
}
```

Override only the methods you need. The rest are logged at TRACE level and silently return.

## Complete Example: WebSocket Chat

This is the `WebSocketChat` class from the `embedded-jetty-websocket-chat` sample:

```java
@WebSocketHandlerService(path = "/chat", broadcaster = SimpleBroadcaster.class,
        atmosphereConfig = {
            "org.atmosphere.websocket.WebSocketProtocol=" +
            "org.atmosphere.websocket.protocol.StreamingHttpProtocol"
        })
public class WebSocketChat extends WebSocketStreamingHandlerAdapter {

    private final Logger logger = LoggerFactory.getLogger(WebSocketChat.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void onOpen(WebSocket webSocket) throws IOException {
        webSocket.resource().addEventListener(new WebSocketEventListenerAdapter() {
            @Override
            public void onDisconnect(AtmosphereResourceEvent event) {
                if (event.isCancelled()) {
                    logger.info("Browser {} unexpectedly disconnected",
                        event.getResource().uuid());
                } else if (event.isClosedByClient()) {
                    logger.info("Browser {} closed the connection",
                        event.getResource().uuid());
                }
            }
        });
    }

    @Override
    public void onTextStream(WebSocket webSocket, Reader reader) {
        try (BufferedReader br = new BufferedReader(reader)) {
            webSocket.broadcast(
                mapper.writeValueAsString(mapper.readValue(br.readLine(), Data.class)));
        } catch (Exception e) {
            logger.error("Failed to parse JSON", e);
        }
    }

    public record Data(String author, String message, long time) {
        public Data() {
            this("", "", new Date().getTime());
        }

        public Data(String author, String message) {
            this(author, message, new Date().getTime());
        }
    }
}
```

Let's walk through the key details.

### The @WebSocketHandlerService Annotation

```java
@WebSocketHandlerService(path = "/chat", broadcaster = SimpleBroadcaster.class,
        atmosphereConfig = {"org.atmosphere.websocket.WebSocketProtocol=..."})
```

| Attribute | Type | Description |
|-----------|------|-------------|
| `path` | `String` | The URL path for this handler. Default: `"/"` |
| `broadcaster` | `Class<? extends Broadcaster>` | The Broadcaster implementation. Default: `DefaultBroadcaster.class` |
| `broadcastFilters` | `Class<? extends BroadcastFilter>[]` | Broadcast filters to apply |
| `broadcasterCache` | `Class<? extends BroadcasterCache>` | Cache for offline message delivery. Default: `DefaultBroadcasterCache.class` (no-op) |
| `interceptors` | `Class<? extends AtmosphereInterceptor>[]` | Interceptors to install |
| `atmosphereConfig` | `String[]` | Key-value config pairs (format: `"key=value"`) |
| `listeners` | `Class<? extends WebSocketEventListener>[]` | Event listeners for lifecycle tracking |

### SimpleBroadcaster vs. DefaultBroadcaster

The sample uses `SimpleBroadcaster.class`. The difference:

- **`DefaultBroadcaster`** -- creates its own thread pool for async broadcasting. Good for high-throughput scenarios where broadcast operations should not block the caller.
- **`SimpleBroadcaster`** -- broadcasts on the caller's thread. Lower overhead when you don't need async dispatch, and simpler to debug.

For a chat application with modest traffic, `SimpleBroadcaster` is the right choice.

### onOpen -- Connection Setup

```java
@Override
public void onOpen(WebSocket webSocket) throws IOException {
    webSocket.resource().addEventListener(new WebSocketEventListenerAdapter() {
        @Override
        public void onDisconnect(AtmosphereResourceEvent event) {
            if (event.isCancelled()) {
                logger.info("Browser {} unexpectedly disconnected",
                    event.getResource().uuid());
            } else if (event.isClosedByClient()) {
                logger.info("Browser {} closed the connection",
                    event.getResource().uuid());
            }
        }
    });
}
```

`webSocket.resource()` returns the underlying `AtmosphereResource` for this connection. Here we attach a `WebSocketEventListenerAdapter` to distinguish between clean disconnects (`isClosedByClient()`) and unexpected disconnects (`isCancelled()` -- network failure, tab crash).

### onTextStream -- Message Handling

```java
@Override
public void onTextStream(WebSocket webSocket, Reader reader) {
    try (BufferedReader br = new BufferedReader(reader)) {
        webSocket.broadcast(
            mapper.writeValueAsString(mapper.readValue(br.readLine(), Data.class)));
    } catch (Exception e) {
        logger.error("Failed to parse JSON", e);
    }
}
```

The `onTextStream` method receives a `Reader` instead of a `String`. This is useful for large messages where you want to stream the content rather than buffer it in memory. The `atmosphereConfig` in the annotation sets `StreamingHttpProtocol` to enable this behavior.

The flow:
1. Read the incoming JSON line from the `Reader`
2. Deserialize it into a `Data` record (validating the JSON structure)
3. Re-serialize and broadcast to all connected WebSocket clients

### WebSocket.broadcast()

```java
webSocket.broadcast(object);
```

This is the core broadcasting method on the `WebSocket` class. It delegates to `resource.getBroadcaster().broadcast(object)`, which fans the message out to all resources attached to the same Broadcaster. It returns the `WebSocket` instance for chaining.

## WebSocketEventListenerAdapter

`WebSocketEventListenerAdapter` provides no-op implementations for a comprehensive set of lifecycle events:

| Method | When It Fires |
|--------|---------------|
| `onHandshake(WebSocketEvent)` | WebSocket handshake complete |
| `onOpen` / `onConnect(WebSocketEvent)` | Connection established |
| `onMessage(WebSocketEvent)` | Message received |
| `onClose(WebSocketEvent)` | WebSocket close frame received |
| `onDisconnect(WebSocketEvent)` | Disconnect detected |
| `onControl(WebSocketEvent)` | Control frame (ping/pong) received |
| `onSuspend(AtmosphereResourceEvent)` | Resource suspended |
| `onResume(AtmosphereResourceEvent)` | Resource resumed |
| `onDisconnect(AtmosphereResourceEvent)` | Resource-level disconnect |
| `onBroadcast(AtmosphereResourceEvent)` | Message broadcast to this resource |
| `onHeartbeat(AtmosphereResourceEvent)` | Heartbeat received |
| `onThrowable(AtmosphereResourceEvent)` | Error on this resource |
| `onClose(AtmosphereResourceEvent)` | Resource-level close |
| `onPreSuspend(AtmosphereResourceEvent)` | Just before suspension |

Override only the events you care about. This adapter is typically used inside `onOpen` to hook into the connection lifecycle, as shown in the `WebSocketChat` example above.

## Choosing the Right Message Handler

`WebSocketStreamingHandlerAdapter` provides four message-handling methods. Which one fires depends on the WebSocket protocol configuration:

| Method | Input Type | Use When |
|--------|------------|----------|
| `onTextMessage(WebSocket, String)` | Full `String` | Default; simple text messages |
| `onByteMessage(WebSocket, byte[], offset, length)` | `byte[]` | Binary protocols, file uploads |
| `onTextStream(WebSocket, Reader)` | `java.io.Reader` | Large text messages, `StreamingHttpProtocol` |
| `onBinaryStream(WebSocket, InputStream)` | `java.io.InputStream` | Large binary messages |

To use the streaming variants (`onTextStream` / `onBinaryStream`), set the WebSocket protocol to `StreamingHttpProtocol`:

```java
@WebSocketHandlerService(path = "/chat",
    atmosphereConfig = {
        "org.atmosphere.websocket.WebSocketProtocol=" +
        "org.atmosphere.websocket.protocol.StreamingHttpProtocol"
    })
```

## ApplicationConfig WebSocket Settings

Atmosphere exposes several `ApplicationConfig` constants for tuning WebSocket behavior. These can be set via `atmosphereConfig` on the annotation, as servlet init-params, or programmatically:

| Constant | Value | Description |
|----------|-------|-------------|
| `ApplicationConfig.WEBSOCKET_PROTOCOL` | `org.atmosphere.websocket.WebSocketProtocol` | WebSocket protocol handler class |
| `ApplicationConfig.WEBSOCKET_SUPPORT` | `org.atmosphere.useWebSocket` | Enable/disable WebSocket support |
| `ApplicationConfig.WEBSOCKET_IDLETIME` | `org.atmosphere.websocket.maxIdleTime` | Max idle time before closing |
| `ApplicationConfig.WEBSOCKET_BUFFER_SIZE` | `org.atmosphere.websocket.bufferSize` | WebSocket buffer size |
| `ApplicationConfig.WEBSOCKET_MAXTEXTSIZE` | `org.atmosphere.websocket.maxTextMessageSize` | Max text message size |
| `ApplicationConfig.WEBSOCKET_MAXBINARYSIZE` | `org.atmosphere.websocket.maxBinaryMessageSize` | Max binary message size |

Example with multiple config values:

```java
@WebSocketHandlerService(path = "/binary-feed",
    atmosphereConfig = {
        "org.atmosphere.websocket.maxBinaryMessageSize=1048576",
        "org.atmosphere.websocket.maxIdleTime=300000"
    })
public class BinaryFeed extends WebSocketStreamingHandlerAdapter {

    @Override
    public void onByteMessage(WebSocket webSocket, byte[] data, int offset, int length) {
        // Handle binary data up to 1MB
        var payload = new byte[length];
        System.arraycopy(data, offset, payload, 0, length);
        webSocket.broadcast(payload);
    }
}
```

## When to Use @WebSocketHandlerService

Use `@WebSocketHandlerService` when you need:

1. **Binary messages** -- `onByteMessage` gives you raw bytes, which `@ManagedService` does not support directly.
2. **Streaming readers** -- `onTextStream` / `onBinaryStream` avoid buffering large messages in memory.
3. **Protocol customization** -- The `atmosphereConfig` attribute lets you swap in custom WebSocket protocol handlers.
4. **Lifecycle control** -- Direct access to `onOpen`, `onClose`, and `onError` without the annotation indirection of `@Ready` and `@Disconnect`.
5. **WebSocket-only endpoints** -- When you know your clients always use WebSocket and you don't need transport fallback.

For everything else -- annotation-based routing, transport-agnostic endpoints, encoder/decoder chains, `@PathParam` variables -- stick with `@ManagedService`.

## Summary

| Concept | Purpose |
|---------|---------|
| `WebSocketHandler` | Core interface: `onOpen`, `onTextMessage`, `onByteMessage`, `onClose`, `onError` |
| `WebSocketStreamingHandlerAdapter` | No-op adapter adding `onTextStream` and `onBinaryStream` for large messages |
| `@WebSocketHandlerService` | Annotation for registering a handler with path, broadcaster, config, and interceptors |
| `WebSocket.broadcast(Object)` | Broadcasts to all resources on the same Broadcaster |
| `WebSocketEventListenerAdapter` | Fine-grained lifecycle hooks (handshake, message, close, heartbeat, etc.) |
| `SimpleBroadcaster` | Broadcasts on the caller's thread (lower overhead) |
| `DefaultBroadcaster` | Broadcasts asynchronously using its own thread pool |
| `StreamingHttpProtocol` | WebSocket protocol that delivers messages as `Reader`/`InputStream` streams |

In the [next chapter](/docs/tutorial/08-interceptors/), you will learn about interceptors -- Atmosphere's middleware layer for heartbeats, message tracking, authentication, and the `RoomProtocolInterceptor` you saw in the previous chapter.
