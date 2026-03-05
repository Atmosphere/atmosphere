---
title: "Chapter 4: Transports"
description: "How Atmosphere handles WebSocket, SSE, long-polling, streaming, and gRPC transparently, with auto-negotiation and the @WebSocketHandlerService API."
sidebar:
  order: 4
---

# Transports

Atmosphere is transport-agnostic. The same server-side code handles WebSocket, SSE, long-polling, streaming, and gRPC clients simultaneously. This chapter explains how each transport works, how auto-negotiation selects the best one, and how to drop down to the lower-level `@WebSocketHandlerService` when you need direct WebSocket control.

## Supported Transports

| Transport | Description | Connection Pattern |
|-----------|-------------|-------------------|
| **WebSocket** | Full-duplex, bidirectional channel over a single TCP connection | Persistent |
| **SSE** (Server-Sent Events) | Server-to-client streaming over a long-lived HTTP response | Persistent (server-to-client only) |
| **Long-Polling** | Client sends a request, server holds it until data is available, then responds | Repeated request/response cycles |
| **Streaming** | Server writes to an open HTTP response without closing it | Persistent (server-to-client only) |
| **gRPC** | Bidirectional streaming over HTTP/2 using Protocol Buffers (`atmosphere-grpc` module) | Persistent |

All five transports deliver the same messages. The transport choice affects performance characteristics (latency, overhead, compatibility), not your application logic. gRPC is covered in detail in [Chapter 20](/docs/tutorial/20-grpc-kotlin/).

## Transport-Agnostic Design

The central principle is that your `@ManagedService` class never needs to know which transport a client is using. When you return a value from a `@Message` method, Atmosphere inspects each subscribed `AtmosphereResource` to determine its transport and writes the message using the appropriate protocol:

- A **WebSocket** client receives a WebSocket text frame.
- An **SSE** client receives a `data:` event on the event stream.
- A **long-polling** client receives an HTTP response body, then immediately reconnects.
- A **streaming** client receives the data appended to the open HTTP response.
- A **gRPC** client receives an `AtmosphereMessage` on its bidirectional stream.

This means a single `Broadcaster` can have subscribers using different transports, and all of them receive the same message simultaneously:

```java
@ManagedService(path = "/chat")
public class Chat {

    @org.atmosphere.config.service.Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) {
        // This return value is delivered to ALL subscribers,
        // regardless of whether they connected via WebSocket, SSE, or long-polling
        return message;
    }
}
```

## Auto-Negotiation

Transport negotiation is handled by the client library (`atmosphere.js`) in coordination with the server. The typical flow is:

1. The client attempts a WebSocket upgrade.
2. If the WebSocket handshake succeeds, the connection stays on WebSocket.
3. If the handshake fails (e.g., a proxy strips the `Upgrade` header), the client falls back to SSE.
4. If SSE is not available, the client falls back to long-polling.

This fallback happens transparently. Your server code does not change.

## Configuration

Transport support is controlled via `ApplicationConfig` parameters, which can be set as servlet init parameters or in the `atmosphereConfig` attribute of `@ManagedService`.

### Enabling WebSocket

WebSocket is enabled via `ApplicationConfig.WEBSOCKET_SUPPORT`:

```java
atmosphereServlet.setInitParameter(ApplicationConfig.WEBSOCKET_SUPPORT, "true");
```

### Content Type

The content type for WebSocket messages is set via `ApplicationConfig.WEBSOCKET_CONTENT_TYPE`:

```java
atmosphereServlet.setInitParameter(ApplicationConfig.WEBSOCKET_CONTENT_TYPE, "application/json");
```

### Embedded Jetty Example

From the embedded Jetty chat sample, showing all transport-related configuration:

```java
ServletHolder atmosphereServlet = new ServletHolder(AtmosphereServlet.class);
atmosphereServlet.setInitParameter(
    ApplicationConfig.ANNOTATION_PACKAGE, "org.atmosphere.samples.chat");
atmosphereServlet.setInitParameter(
    ApplicationConfig.WEBSOCKET_CONTENT_TYPE, "application/json");
atmosphereServlet.setInitParameter(
    ApplicationConfig.WEBSOCKET_SUPPORT, "true");
atmosphereServlet.setInitOrder(1);
atmosphereServlet.setAsyncSupported(true);
context.addServlet(atmosphereServlet, "/chat/*");
```

Note that `setAsyncSupported(true)` is required for long-polling and SSE transports, which rely on the Servlet 3.0+ async processing model.

## @WebSocketHandlerService

When `@ManagedService` is too high-level and you need direct access to WebSocket frames, use `@WebSocketHandlerService`. This annotation maps a `WebSocketStreamingHandlerAdapter` subclass to a path and gives you raw `onOpen`, `onTextStream`, `onClose`, and `onError` callbacks.

From the WebSocket chat sample (`samples/embedded-jetty-websocket-chat/src/main/java/org/atmosphere/samples/chat/WebSocketChat.java`):

```java
@WebSocketHandlerService(path = "/chat", broadcaster = SimpleBroadcaster.class,
    atmosphereConfig = {"org.atmosphere.websocket.WebSocketProtocol=" +
        "org.atmosphere.websocket.protocol.StreamingHttpProtocol"})
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

### Key Differences from @ManagedService

| Aspect | @ManagedService | @WebSocketHandlerService |
|--------|----------------|-------------------------|
| Programming model | Annotation-driven lifecycle | Override handler methods |
| Transport support | All transports automatically | WebSocket-focused |
| Message handling | `@Message` with encoders/decoders | Raw `onTextStream(WebSocket, Reader)` |
| Connection lifecycle | `@Ready`, `@Disconnect`, `@Heartbeat` | `onOpen(WebSocket)`, event listeners |
| Broadcaster | `DefaultBroadcaster` by default | Typically `SimpleBroadcaster` |
| Injection | `@Inject` fields | Access via `webSocket.resource()` |

### @WebSocketHandlerService Attributes

The annotation supports the following attributes (similar to `@ManagedService`):

- **path** -- the URL mapping
- **broadcaster** -- the `Broadcaster` class (often `SimpleBroadcaster` for WebSocket-only)
- **broadcastFilters** -- `BroadcastFilter` implementations
- **broadcasterCache** -- `BroadcasterCache` implementation
- **interceptors** -- `AtmosphereInterceptor` implementations
- **atmosphereConfig** -- key-value configuration
- **listeners** -- `AtmosphereResourceEventListener` implementations

### WebSocket API

The `WebSocket` object passed to handler methods provides:

- **`webSocket.resource()`** -- the underlying `AtmosphereResource`
- **`webSocket.broadcast(message)`** -- broadcast to all subscribers on the associated `Broadcaster`
- **`webSocket.write(message)`** -- write directly to this single WebSocket connection

## When to Use Which

Use **`@ManagedService`** for most applications. It handles all transports, provides clean lifecycle annotations, supports injection, and manages the `Broadcaster` automatically. The chat sample built in Chapter 2 is a complete, production-ready real-time endpoint in under 50 lines of code.

Use **`@WebSocketHandlerService`** when you need:

- Direct access to raw WebSocket frames (binary or text streams)
- Custom WebSocket protocol handling
- A WebSocket-only endpoint where long-polling/SSE fallback is not needed
- Fine-grained control over how messages are parsed and broadcast

## Atmosphere Protocol

Atmosphere uses a lightweight protocol between the client (`atmosphere.js`) and the server to manage connection identity, transport negotiation, and message framing. If you are writing a custom client or debugging wire-level behavior, understanding this protocol is essential.

### Protocol Headers

The client attaches the following headers (or query parameters) to every request:

| Header / Query Param | Description |
|----------------------|-------------|
| `X-Atmosphere-Framework` | The Atmosphere client version (e.g., `5.0.0`) |
| `X-Atmosphere-Transport` | The transport in use: `websocket`, `sse`, `long-polling`, `streaming`, or `close` |
| `X-Atmosphere-tracking-id` | A UUID that uniquely identifies this client. Initially `0`; the server assigns the real value on first response |
| `X-Cache-Date` | Timestamp for cache coordination |
| `X-atmo-protocol` | When `true`, the server sends back the tracking ID and cache date on the first request so subsequent requests carry the correct values |

### Handshake Flow

1. The client sends its first request with `X-Atmosphere-tracking-id=0` and `X-atmo-protocol=true`.
2. The server assigns a UUID, creates an `AtmosphereResource`, and returns the tracking ID in the response header `X-Atmosphere-tracking-id`.
3. All subsequent requests from this client include the assigned tracking ID, allowing the server to correlate requests to the same logical connection.
4. When the client disconnects, it sends a final request with `X-Atmosphere-Transport=close` and the tracking ID.

A typical first request on the wire looks like:

```
GET /chat?X-Atmosphere-tracking-id=0&X-Atmosphere-Framework=5.0.0
    &X-Atmosphere-Transport=websocket&X-atmo-protocol=true HTTP/1.1
Host: localhost:8080
```

And the close request:

```
GET /chat?X-Atmosphere-Transport=close
    &X-Atmosphere-tracking-id=e24de98c-6624-c552-5572-6edbffd270e3 HTTP/1.1
Host: localhost:8080
```

### Message Framing with TrackMessageSizeInterceptor

When multiple messages arrive in a single TCP frame (common with HTTP streaming and SSE), the client needs a way to delimit them. The `TrackMessageSizeInterceptor` solves this by prepending the byte length of each message before it is written to the wire.

For example, without the interceptor, the client might receive `{"author":"Alice"}{"author":"Bob"}` as a single chunk. With the interceptor, it receives `18{"author":"Alice"}16{"author":"Bob"}`, allowing `atmosphere.js` to split the messages correctly.

Enable it via annotation:

```java
@ManagedService(path = "/chat",
    interceptors = {TrackMessageSizeInterceptor.class})
```

Or via `web.xml`:

```xml
<init-param>
    <param-name>org.atmosphere.cpr.AtmosphereInterceptor</param-name>
    <param-value>org.atmosphere.client.TrackMessageSizeInterceptor</param-value>
</init-param>
```

On the client side, set `trackMessageLength: true` in the subscription options to enable the corresponding parsing logic in `atmosphere.js`.

Content types that should not be framed (e.g., HTML pages served through the same servlet) can be excluded:

```xml
<init-param>
    <param-name>org.atmosphere.client.TrackMessageSizeInterceptor.excludedContentType</param-name>
    <param-value>html, xml, jpeg</param-value>
</init-param>
```

See [Chapter 8: Interceptors](/docs/tutorial/08-interceptors/) for more on `TrackMessageSizeInterceptor` and other built-in interceptors.

## Next Steps

The next chapter covers the `Broadcaster` in depth -- how pub/sub routing works, the difference between `DefaultBroadcaster` and `SimpleBroadcaster`, `BroadcasterCache` for missed messages, and `BroadcastFilter` for message transformation.
