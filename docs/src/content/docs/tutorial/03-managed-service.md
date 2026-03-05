---
title: "Chapter 3: @ManagedService Deep Dive"
description: "Complete reference for @ManagedService: all 7 attributes, lifecycle annotations, injection, HTTP method handlers, delivery semantics, and path parameters."
sidebar:
  order: 3
---

# @ManagedService Deep Dive

The `@ManagedService` annotation is the primary programming model for Atmosphere applications. It turns a plain Java class into a real-time endpoint with automatic connection lifecycle management, message routing, pub/sub, heartbeats, and message caching -- without implementing any framework interface.

## The Annotation Signature

From the source (`modules/cpr/src/main/java/org/atmosphere/config/service/ManagedService.java`):

```java
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ManagedService {
    String path() default "/";
    Class<? extends AtmosphereResourceEventListener>[] listeners() default {};
    Class<? extends Broadcaster> broadcaster() default DefaultBroadcaster.class;
    Class<? extends AtmosphereInterceptor>[] interceptors() default {};
    String[] atmosphereConfig() default {};
    Class<? extends BroadcasterCache> broadcasterCache() default UUIDBroadcasterCache.class;
    Class<? extends BroadcastFilter>[] broadcastFilters() default {};
}
```

## The 7 Attributes

### path

The URL path where this endpoint is mapped. Clients connect to this path to subscribe to the `Broadcaster`.

```java
@ManagedService(path = "/chat")
```

The path supports template variables for dynamic routing (see the `@PathParam` section below):

```java
@ManagedService(path = "/chat/{room}")
```

### listeners

An array of `AtmosphereResourceEventListener` implementations that receive lifecycle events (connect, disconnect, suspend, resume) at the transport level. These are lower-level than the `@Ready`/`@Disconnect` annotations and are useful for metrics or custom tracking.

```java
@ManagedService(path = "/chat", listeners = {MyEventListener.class})
```

### broadcaster

The `Broadcaster` implementation to use. Defaults to `DefaultBroadcaster`, which supports all transports and uses an `ExecutorService` for async delivery. For WebSocket-only scenarios, `SimpleBroadcaster` is a lighter alternative.

```java
@ManagedService(path = "/chat", broadcaster = SimpleBroadcaster.class)
```

### interceptors

Additional `AtmosphereInterceptor` implementations appended to the default set. The defaults already include `AtmosphereResourceLifecycleInterceptor`, `ManagedServiceInterceptor`, `TrackMessageSizeInterceptor`, `HeartbeatInterceptor`, and `SuspendTrackerInterceptor`.

```java
@ManagedService(path = "/chat", interceptors = {MyCustomInterceptor.class})
```

### atmosphereConfig

Key-value configuration pairs passed to the `AtmosphereHandler` associated with this endpoint. Each entry is a `"key=value"` string. From the chat sample:

```java
import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

@ManagedService(path = "/chat", atmosphereConfig = MAX_INACTIVE + "=120000")
```

This sets the maximum inactivity timeout to 120 seconds. Multiple config entries are passed as an array:

```java
@ManagedService(path = "/chat", atmosphereConfig = {
    MAX_INACTIVE + "=120000",
    "org.atmosphere.websocket.WebSocketProtocol=org.atmosphere.websocket.protocol.StreamingHttpProtocol"
})
```

### broadcasterCache

The `BroadcasterCache` implementation for caching messages so that clients that temporarily disconnect can receive missed messages on reconnection. Defaults to `UUIDBroadcasterCache`, which uses per-resource UUIDs to track delivery.

```java
@ManagedService(path = "/chat", broadcasterCache = UUIDBroadcasterCache.class)
```

### broadcastFilters

An array of `BroadcastFilter` implementations that transform or filter messages before they are delivered to subscribers. Filters are applied in order. A filter can modify the message, pass it through unchanged, or abort delivery entirely.

```java
@ManagedService(path = "/chat", broadcastFilters = {XSSHtmlFilter.class})
```

## Lifecycle Annotations

These annotations go on **methods** inside your `@ManagedService` class. Each method is called at a specific point in the connection lifecycle.

### @Ready

Called when the connection has been suspended and is ready to receive messages. This is where you know the client is fully connected.

```java
@Ready
public void onReady() {
    logger.info("Browser {} connected", r.uuid());
}
```

The method can optionally take an `AtmosphereResource` parameter instead of using injection:

```java
@Ready
public void onReady(AtmosphereResource r) {
    logger.info("Browser {} connected", r.uuid());
}
```

`@Ready` has an `encoders` attribute for encoding a return value that will be sent only to the connecting resource:

```java
@Ready(encoders = {JacksonEncoder.class})
public WelcomeMessage onReady() {
    return new WelcomeMessage("Welcome!");
}
```

### @Disconnect

Called when the remote connection is closed, either by the client or unexpectedly. Use the injected `AtmosphereResourceEvent` to distinguish between the two cases:

```java
@Disconnect
public void onDisconnect() {
    if (event.isCancelled()) {
        logger.info("Browser {} unexpectedly disconnected", event.getResource().uuid());
    } else if (event.isClosedByClient()) {
        logger.info("Browser {} closed the connection", event.getResource().uuid());
    }
}
```

### @Message

Called when a message is broadcast to this endpoint's `Broadcaster`. The `decoders` attribute deserializes the incoming wire format into your domain object. The `encoders` attribute serializes the return value for delivery. Returning a value broadcasts it to all subscribers.

```java
@org.atmosphere.config.service.Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
public Message onMessage(Message message) throws IOException {
    logger.info("{} just sent {}", message.getAuthor(), message.getMessage());
    return message;
}
```

The method can also accept a `String` parameter if no decoder is needed:

```java
@Message
public String onMessage(String message) {
    return "Echo: " + message;
}
```

### @Heartbeat

Called when the client sends a heartbeat ping. The `HeartbeatInterceptor` must be installed (it is included by default with `@ManagedService`).

```java
@Heartbeat
public void onHeartbeat(final AtmosphereResourceEvent event) {
    logger.trace("Heartbeat send by {}", event.getResource());
}
```

### @Resume

Called when a suspended connection is resumed (typically after a timeout or explicit `AtmosphereResource.resume()` call). This is most relevant for long-polling, where the connection is resumed after each response.

```java
@Resume
public void onResume() {
    logger.info("Connection resumed for {}", r.uuid());
}
```

## Injection

Atmosphere provides CDI-like injection via `jakarta.inject.Inject` and `jakarta.inject.Named`. The following fields can be injected into any `@ManagedService` class:

### Broadcaster (by name)

The idiomatic way to get the `Broadcaster` for your endpoint — use `@Named` with the path:

```java
@Inject
@Named("/chat")
private Broadcaster broadcaster;
```

### AtmosphereResource

The current client's resource (connection handle). This is scoped to the current request:

```java
@Inject
private AtmosphereResource r;
```

### AtmosphereResourceEvent

The current lifecycle event, used in `@Disconnect` to check `isCancelled()` and `isClosedByClient()`:

```java
@Inject
private AtmosphereResourceEvent event;
```

### Complete Injection Example

From the chat sample:

```java
@ManagedService(path = "/chat", atmosphereConfig = MAX_INACTIVE + "=120000")
public class Chat {

    @Inject
    @Named("/chat")
    private Broadcaster broadcaster;

    @Inject
    private AtmosphereResource r;

    @Inject
    private AtmosphereResourceEvent event;

    @Ready
    public void onReady() {
        logger.info("Browser {} connected (broadcaster: {})", r.uuid(), broadcaster.getID());
    }
}
```

Injection into `Encoder` and `Decoder` classes also works. For example, the `JacksonEncoder` from the chat sample injects an `ObjectMapper`:

```java
public class JacksonEncoder implements Encoder<Message, String> {

    @Inject
    private ObjectMapper mapper;

    @Override
    public String encode(Message m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
```

## HTTP Method Annotations

In addition to the lifecycle annotations, `@ManagedService` supports HTTP method annotations that let you handle specific HTTP verbs before the connection is suspended. These are useful for initializing response settings or returning data on initial load.

### @Get, @Post, @Put, @Delete

Each annotation maps to its HTTP counterpart. From the chat sample (shown as a commented-out example):

```java
@Get
public void init(AtmosphereResource r) {
    r.getResponse().setCharacterEncoding("UTF-8");
}
```

These methods are called during the initial HTTP request, before the connection is suspended for real-time communication.

## @Singleton

By default, Atmosphere creates a new instance of your `@ManagedService` class for each connecting client. Adding `@Singleton` at the class level changes this so that a single instance handles all connections:

```java
@Singleton
@ManagedService(path = "/chat")
public class Chat {
    // Single instance shared across all connections
}
```

When using `@Singleton`, the injected `AtmosphereResource` and `AtmosphereResourceEvent` are still scoped to the current request -- they change with each lifecycle callback invocation.

## @DeliverTo

By default, the return value of a `@Message` method is broadcast to all subscribers on the `Broadcaster`. The `@DeliverTo` annotation changes this delivery scope:

```java
import org.atmosphere.config.service.DeliverTo;
import org.atmosphere.config.service.DeliverTo.DELIVER_TO;
```

### DELIVER_TO.BROADCASTER (default)

Deliver to all resources subscribed to this `Broadcaster`:

```java
@Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
@DeliverTo(DELIVER_TO.BROADCASTER)
public Message onMessage(Message message) {
    return message; // sent to all subscribers on this Broadcaster
}
```

### DELIVER_TO.RESOURCE

Deliver only to the resource that sent the message (useful for acknowledgments or echo):

```java
@Message(decoders = {JacksonDecoder.class})
@DeliverTo(DELIVER_TO.RESOURCE)
public String onMessage(Message message) {
    return "Received: " + message.getMessage(); // sent only to the sender
}
```

### DELIVER_TO.ALL

Deliver to all resources across **all** `Broadcaster` instances (global broadcast):

```java
@Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
@DeliverTo(DELIVER_TO.ALL)
public Message onMessage(Message message) {
    return message; // sent to every connected client across all broadcasters
}
```

## @PathParam

When using path templates, `@PathParam` injects the value of a URI template variable into a field:

```java
@ManagedService(path = "/chat/{room}")
public class ChatRoom {

    @PathParam("room")
    private String room;

    @Ready
    public void onReady() {
        logger.info("Client joined room: {}", room);
    }

    @Message(decoders = {JacksonDecoder.class}, encoders = {JacksonEncoder.class})
    public Message onMessage(Message message) {
        return message; // broadcast to all subscribers in this room
    }
}
```

If a client connects to `/chat/general`, the `room` field will contain `"general"`. Each distinct path value gets its own `Broadcaster` instance, so clients in `/chat/general` and `/chat/support` are automatically in separate broadcast groups.

The `@PathParam` value is optional. If omitted, the field name is used as the template variable name:

```java
@PathParam  // matches {room} if the field is named "room"
private String room;
```

## Putting It All Together

Here is the complete chat sample showing all the concepts from this chapter working together:

```java
@ManagedService(path = "/chat", atmosphereConfig = MAX_INACTIVE + "=120000")
public class Chat {
    private final Logger logger = LoggerFactory.getLogger(Chat.class);

    @Inject
    @Named("/chat")
    private Broadcaster broadcaster;

    @Inject
    private AtmosphereResource r;

    @Inject
    private AtmosphereResourceEvent event;

    @Heartbeat
    public void onHeartbeat(final AtmosphereResourceEvent event) {
        logger.trace("Heartbeat send by {}", event.getResource());
    }

    @Ready
    public void onReady() {
        logger.info("Browser {} connected (broadcaster: {})", r.uuid(), broadcaster.getID());
    }

    @Disconnect
    public void onDisconnect() {
        if (event.isCancelled()) {
            logger.info("Browser {} unexpectedly disconnected", event.getResource().uuid());
        } else if (event.isClosedByClient()) {
            logger.info("Browser {} closed the connection", event.getResource().uuid());
        }
    }

    @org.atmosphere.config.service.Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) throws IOException {
        logger.info("{} just sent {}", message.getAuthor(), message.getMessage());
        return message;
    }
}
```

## HttpSession Support

By default, Atmosphere does not create or track HTTP sessions. If your application uses `HttpSession` (e.g., for authentication or user state), you must tell Atmosphere to participate in session management. This is especially important for WebSocket connections, where session handling varies across servlet containers.

### Enabling Session Support

Add the `SessionSupport` listener and the `sessionSupport` parameter to `web.xml`:

```xml
<listener>
    <listener-class>org.atmosphere.cpr.SessionSupport</listener-class>
</listener>

<context-param>
    <param-name>org.atmosphere.cpr.sessionSupport</param-name>
    <param-value>true</param-value>
</context-param>
```

### Accessing the Session

Once enabled, you can access the `HttpSession` from any `AtmosphereResource`:

```java
@Ready
public void onReady(AtmosphereResource r) {
    var session = r.session();
    var username = (String) session.getAttribute("username");
    logger.info("User {} connected", username);
}
```

The `r.session()` method returns the `jakarta.servlet.http.HttpSession`. Use `r.session(false)` to get `null` instead of creating a new session if one does not exist.

## AtmosphereResourceSession

While `HttpSession` is shared across all connections from the same browser, an `AtmosphereResourceSession` provides per-connection server-side storage. Each `AtmosphereResource` can have its own session that lives from creation until the client disconnects.

### Getting the Factory

Inject the `AtmosphereResourceSessionFactory`:

```java
@Inject
private AtmosphereResourceSessionFactory sessionFactory;
```

### Storing and Retrieving Attributes

```java
@Ready
public void onReady(AtmosphereResource r) {
    var session = sessionFactory.getSession(r);
    session.setAttribute("connectedAt", Instant.now());
}

@Disconnect
public void onDisconnect(AtmosphereResource r) {
    var session = sessionFactory.getSession(r, false);
    if (session != null) {
        var connectedAt = session.getAttribute("connectedAt", Instant.class);
        logger.info("Client was connected for {}",
            Duration.between(connectedAt, Instant.now()));
    }
}
```

The two-argument `getSession(resource, false)` returns `null` if no session exists yet, avoiding unnecessary session creation. The one-argument `getSession(resource)` always creates a session if one does not exist.

This is useful for attaching per-connection metadata (user identity, preferences, state) without coupling to the servlet `HttpSession`.

## Next Steps

The next chapter covers how Atmosphere handles multiple transports transparently and how to use `@WebSocketHandlerService` for lower-level WebSocket access.
