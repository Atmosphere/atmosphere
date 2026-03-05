---
title: "Chapter 3: @ManagedService Deep Dive"
description: "Full annotation reference for @ManagedService: lifecycle methods, injection, encoders/decoders, path parameters, delivery semantics, and Broadcaster selection."
---

# @ManagedService Deep Dive

The `@ManagedService` annotation is the primary programming model for Atmosphere applications. It turns a plain Java class into a real-time endpoint with automatic connection lifecycle management, message routing, pub/sub, heartbeats, and message caching -- all without implementing any framework interfaces.

This chapter covers every aspect of `@ManagedService` in detail.

## The @ManagedService Annotation

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

### Annotation Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `path` | `String` | `"/"` | URL path for this endpoint. Supports path parameters: `/chat/{room}` |
| `listeners` | `Class[]` | `{}` | `AtmosphereResourceEventListener` implementations for tracking connection events |
| `broadcaster` | `Class` | `DefaultBroadcaster.class` | The `Broadcaster` implementation to use. Override for Redis/Kafka clustering. |
| `interceptors` | `Class[]` | `{}` | Additional `AtmosphereInterceptor` implementations to install. Appended to the default set. |
| `atmosphereConfig` | `String[]` | `{}` | Key-value configuration pairs, delimited by `=`. Multiple entries are separate array elements. |
| `broadcasterCache` | `Class` | `UUIDBroadcasterCache.class` | The `BroadcasterCache` implementation for caching messages during client disconnects. |
| `broadcastFilters` | `Class[]` | `{}` | `BroadcastFilter` implementations that transform or suppress messages before delivery. |

### Default Interceptors

When you use `@ManagedService`, the following interceptors are automatically installed (you do not need to add them to `interceptors`):

| Interceptor | Purpose |
|-------------|---------|
| `AtmosphereResourceLifecycleInterceptor` | Manages suspend/resume lifecycle for each connection |
| `ManagedServiceInterceptor` | Dispatches to `@Ready`, `@Disconnect`, `@Message`, `@Heartbeat` methods |
| `TrackMessageSizeInterceptor` | Prepends message length to ensure complete delivery |
| `HeartbeatInterceptor` | Sends periodic heartbeats to detect dead connections |
| `SuspendTrackerInterceptor` | Tracks suspended connections for proper cleanup |

You only need to specify `interceptors` when adding **additional** interceptors beyond the defaults.

### atmosphereConfig Examples

Per-endpoint configuration is passed as key-value pairs:

```java
@ManagedService(
    path = "/chat",
    atmosphereConfig = {
        "org.atmosphere.cpr.AtmosphereResource.maxInactiveActivity=120000",
        "org.atmosphere.websocket.maxTextMessageSize=65536",
        "org.atmosphere.cpr.Broadcaster.shareableThreadPool=true"
    })
public class Chat { ... }
```

You can use the constants from `ApplicationConfig` for type safety:

```java
import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;
import static org.atmosphere.cpr.ApplicationConfig.WEBSOCKET_MAXTEXTSIZE;

@ManagedService(
    path = "/chat",
    atmosphereConfig = {
        MAX_INACTIVE + "=120000",
        WEBSOCKET_MAXTEXTSIZE + "=65536"
    })
public class Chat { ... }
```

## Lifecycle Methods

A `@ManagedService` class can have methods annotated with lifecycle annotations. Each annotation marks a method to be called at a specific point in the connection lifecycle.

### @Ready -- Connection Established

```java
@Ready
public void onReady() {
    // The connection is established and suspended, ready for messages
}
```

The `@Ready` method is invoked **after** the connection has been fully established and suspended. At this point:

- The transport has been negotiated (WebSocket, SSE, or Long-Polling).
- The `AtmosphereResource` is subscribed to the Broadcaster.
- The connection is ready to receive broadcast messages.

#### Method Signatures

The `@Ready` method supports several signatures:

```java
// No arguments -- use @Inject fields for context
@Ready
public void onReady() { }

// With AtmosphereResource parameter
@Ready
public void onReady(AtmosphereResource resource) {
    logger.info("Client {} connected via {}", resource.uuid(), resource.transport());
}
```

#### Returning a Value from @Ready

If a `@Ready` method returns a non-null value, it is delivered to the connecting client (not broadcast to all subscribers). This is useful for sending an initial state or welcome message:

```java
@Ready
public String onReady(AtmosphereResource resource) {
    return "Welcome! You are connected via " + resource.transport();
}
```

You can use encoders on `@Ready` to serialize the return value:

```java
@Ready(encoders = {JacksonEncoder.class})
public WelcomeMessage onReady() {
    return new WelcomeMessage("Connected", Instant.now());
}
```

#### Controlling Delivery with @DeliverTo

By default, the return value from `@Ready` is delivered only to the connecting resource. You can change this with the `@DeliverTo` annotation:

```java
// Deliver to the connecting resource only (default)
@Ready
@DeliverTo(DeliverTo.DELIVER_TO.RESOURCE)
public String onReady() {
    return "Welcome!";
}

// Broadcast to all subscribers of this Broadcaster
@Ready
@DeliverTo(DeliverTo.DELIVER_TO.BROADCASTER)
public String onReady() {
    return "A new user has joined!";
}

// Broadcast to ALL Broadcasters in the application
@Ready
@DeliverTo(DeliverTo.DELIVER_TO.ALL)
public String onReady() {
    return "System-wide announcement";
}
```

### @Disconnect -- Connection Closed

```java
@Disconnect
public void onDisconnect() {
    // The client has disconnected
}
```

The `@Disconnect` method is invoked when the connection is closed, either by the client or due to a network failure.

#### Method Signatures

```java
// No arguments -- use @Inject fields
@Disconnect
public void onDisconnect() { }

// With AtmosphereResourceEvent parameter
@Disconnect
public void onDisconnect(AtmosphereResourceEvent event) {
    if (event.isCancelled()) {
        // Network failure or unexpected disconnect
        logger.warn("Client {} lost connection", event.getResource().uuid());
    } else if (event.isClosedByClient()) {
        // Clean close (client called socket.close())
        logger.info("Client {} disconnected", event.getResource().uuid());
    }
}
```

The `AtmosphereResourceEvent` provides information about the disconnect:

| Method | Description |
|--------|-------------|
| `isCancelled()` | `true` if the connection was lost unexpectedly (network failure, timeout) |
| `isClosedByClient()` | `true` if the client explicitly closed the connection |
| `isClosedByApplication()` | `true` if the server closed the connection |
| `isResumedOnTimeout()` | `true` if the connection was resumed due to a suspend timeout |
| `getResource()` | The `AtmosphereResource` that disconnected |

### @Message -- Incoming Message

```java
@org.atmosphere.config.service.Message(
        encoders = {JacksonEncoder.class},
        decoders = {JacksonDecoder.class})
public Message onMessage(Message message) {
    return message; // broadcast to all subscribers
}
```

The `@Message` method is invoked when the client sends a message. This is the core of your application logic.

> **Import note**: The annotation is `org.atmosphere.config.service.Message`, not `jakarta.jms.Message` or `org.springframework.messaging.Message`. When using the short form `@Message`, ensure you have the correct import.

#### The @Message Annotation

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Message {
    Class<? extends Encoder<?,?>>[] encoders() default {};
    Class<? extends Decoder<?,?>>[] decoders() default {};
}
```

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `encoders` | `Class[]` | `{}` | Encoder chain for serializing the return value before broadcast |
| `decoders` | `Class[]` | `{}` | Decoder chain for deserializing the incoming message |

#### Method Signatures

```java
// Raw String -- no decoder needed
@org.atmosphere.config.service.Message
public String onMessage(String rawMessage) {
    return "Echo: " + rawMessage;
}

// Typed object -- requires a decoder
@org.atmosphere.config.service.Message(decoders = {JacksonDecoder.class})
public void onMessage(ChatMessage message) {
    // Process message, don't broadcast (returns void)
}

// Typed input and output -- requires both decoder and encoder
@org.atmosphere.config.service.Message(
        encoders = {JacksonEncoder.class},
        decoders = {JacksonDecoder.class})
public ChatMessage onMessage(ChatMessage message) {
    message.setTimestamp(Instant.now());
    return message; // modified message is broadcast
}
```

#### Return Value Semantics

The return value of a `@Message` method determines what happens next:

| Return | Behavior |
|--------|----------|
| Non-null object | Encoded (if encoders specified) and broadcast to all subscribers of this Broadcaster |
| `null` | Nothing is broadcast. Use this for messages you handle without echoing. |
| `void` | Same as returning `null`. Nothing is broadcast. |

You can combine `@DeliverTo` with `@Message` to control delivery scope:

```java
// Send only to the sender (like a private response)
@org.atmosphere.config.service.Message(decoders = {JacksonDecoder.class})
@DeliverTo(DeliverTo.DELIVER_TO.RESOURCE)
public String onMessage(Command command) {
    return "Acknowledged: " + command.getName();
}

// Broadcast to all subscribers (the default)
@org.atmosphere.config.service.Message(
        encoders = {JacksonEncoder.class},
        decoders = {JacksonDecoder.class})
@DeliverTo(DeliverTo.DELIVER_TO.BROADCASTER)
public ChatMessage onMessage(ChatMessage message) {
    return message;
}
```

### @Heartbeat -- Keep-Alive Ping

```java
@Heartbeat
public void onHeartbeat(AtmosphereResourceEvent event) {
    logger.trace("Heartbeat from {}", event.getResource().uuid());
}
```

The `@Heartbeat` method is called when the client sends a heartbeat message. The `HeartbeatInterceptor` (installed by default with `@ManagedService`) handles heartbeat detection. This method is typically used for logging or for updating "last seen" timestamps.

#### Method Signatures

```java
// With event parameter
@Heartbeat
public void onHeartbeat(AtmosphereResourceEvent event) { }

// No arguments
@Heartbeat
public void onHeartbeat() { }
```

### @Resume -- Connection Resumed

```java
@Resume
public void onResume() {
    // The connection has been resumed after a suspend timeout
}
```

The `@Resume` method is called when a suspended connection is resumed, either by a timeout or by an explicit call to `resource.resume()`. This is primarily relevant for long-polling, where each response cycle involves a suspend and resume.

### @Get, @Post, @Put, @Delete -- HTTP Method Handlers

In addition to the lifecycle methods, `@ManagedService` supports HTTP method annotations for handling specific HTTP requests:

```java
@ManagedService(path = "/api")
public class ApiEndpoint {

    @Get
    public void onGet(AtmosphereResource r) {
        r.getResponse().setCharacterEncoding("UTF-8");
        // Handle GET request (e.g., return current state)
    }

    @Post
    public void onPost(AtmosphereResource r) {
        // Handle POST request
    }
}
```

These methods are invoked before the connection is suspended. They are useful for initializing response headers or handling one-shot HTTP requests alongside the persistent connection.

## Injection

Atmosphere supports `jakarta.inject.Inject` for dependency injection in `@ManagedService` classes. When running under Spring Boot or Quarkus, the framework's DI container is bridged into Atmosphere's object factory, so Spring/CDI beans are injectable too.

### Framework-Provided Injectables

| Type | Scope | Description |
|------|-------|-------------|
| `AtmosphereResource` | Request | The current connection. Available in `@Ready`, `@Disconnect`, `@Message`, `@Heartbeat` methods. |
| `AtmosphereResourceEvent` | Request | The event associated with the current lifecycle callback. |
| `BroadcasterFactory` | Singleton | Factory for looking up and creating Broadcasters. |
| `AtmosphereConfig` | Singleton | The framework configuration. |
| `Broadcaster` | Named | A specific Broadcaster, identified by `@Named` qualifier. |

### Injection Examples

```java
@ManagedService(path = "/chat")
public class Chat {

    // The BroadcasterFactory -- for looking up other Broadcasters
    @Inject
    private BroadcasterFactory factory;

    // The current AtmosphereResource -- request-scoped
    @Inject
    private AtmosphereResource r;

    // The current event -- request-scoped
    @Inject
    private AtmosphereResourceEvent event;

    // A specific Broadcaster by name
    @Inject
    @Named("/chat")
    private Broadcaster chatBroadcaster;
}
```

### Spring Bean Injection

With the Spring Boot starter, you can inject any Spring bean:

```java
@ManagedService(path = "/chat")
public class Chat {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserService userService;
}
```

This works because the starter sets Atmosphere's object factory to delegate to Spring's `ApplicationContext`.

## Encoders and Decoders

Encoders and decoders are the serialization layer between your domain objects and the wire format.

### The Encoder Interface

```java
public interface Encoder<U, T> {
    T encode(U s);
}
```

An `Encoder<Message, String>` converts a `Message` to a `String`. The encoded value is what gets written to the client.

### The Decoder Interface

```java
public interface Decoder<U, T> {
    T decode(U s);
}
```

A `Decoder<String, Message>` converts a `String` (from the client) to a `Message` object, which is then passed to your `@Message` method.

### Chaining Encoders and Decoders

You can chain multiple encoders or decoders. They are invoked in order:

```java
@org.atmosphere.config.service.Message(
    encoders = {GzipEncoder.class, Base64Encoder.class},
    decoders = {Base64Decoder.class, GzipDecoder.class, JacksonDecoder.class})
public ChatMessage onMessage(ChatMessage message) {
    return message;
}
```

For decoders, the chain is: raw bytes -> Base64 decode -> Gzip decompress -> JSON parse to `ChatMessage`.

For encoders, the chain is: `ChatMessage` -> Gzip compress -> Base64 encode -> write to client.

### Jackson Encoder/Decoder Example

The most common pattern uses Jackson for JSON serialization. Here is a complete, reusable implementation:

```java
public class JacksonEncoder implements Encoder<Object, String> {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String encode(Object obj) {
        return mapper.writeValueAsString(obj);
    }
}
```

```java
public class JacksonDecoder implements Decoder<String, Message> {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Message decode(String json) {
        return mapper.readValue(json, Message.class);
    }
}
```

### Encoder/Decoder with Spring DI

Encoders and decoders are created by Atmosphere's object factory. With the Spring Boot starter, they can use Spring injection:

```java
public class JacksonEncoder implements Encoder<Message, String> {

    @Autowired
    private ObjectMapper mapper;

    @Override
    public String encode(Message m) {
        return mapper.writeValueAsString(m);
    }
}
```

This is exactly the pattern used in the `samples/spring-boot-chat/` sample application.

## Path Parameters

`@ManagedService` supports path parameters using curly-brace syntax, and `@PathParam` injects the matched values into fields.

### Defining Path Parameters

```java
@ManagedService(path = "/chat/{room}")
public class ChatRoom {

    @PathParam("room")
    private String roomName;

    @Ready
    public void onReady(AtmosphereResource r) {
        logger.info("Client {} joined room {}", r.uuid(), roomName);
    }

    @org.atmosphere.config.service.Message
    public String onMessage(String message) {
        return "[" + roomName + "] " + message;
    }
}
```

### The @PathParam Annotation

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@Documented
public @interface PathParam {
    String value() default "";
}
```

The `value` attribute specifies the template variable name from the path. If omitted, the field name is used. Path parameters are extracted when the `@ManagedService` is instantiated for a given path.

### Multiple Path Parameters

```java
@ManagedService(path = "/org/{orgId}/channel/{channelId}")
public class OrgChannel {

    @PathParam("orgId")
    private String orgId;

    @PathParam("channelId")
    private String channelId;

    @Ready
    public void onReady() {
        logger.info("Joined org={} channel={}", orgId, channelId);
    }
}
```

### How It Works

Each unique path creates a separate instance of the `@ManagedService` class and a separate Broadcaster. So `/chat/general` and `/chat/support` each get their own:

- `ChatRoom` instance (with `roomName = "general"` or `roomName = "support"`)
- `Broadcaster` (with ID `/chat/general` or `/chat/support`)
- Set of subscribers

This is why `@ManagedService` classes are **not singletons by default** -- a new instance is created per unique path.

## The @Singleton Annotation

By default, a new `@ManagedService` instance is created for each unique path match. If your service does not use path parameters and you want a single shared instance, annotate it with `@Singleton`:

```java
@Singleton
@ManagedService(path = "/notifications")
public class NotificationService {

    private final AtomicLong connectionCount = new AtomicLong();

    @Ready
    public void onReady() {
        connectionCount.incrementAndGet();
    }

    @Disconnect
    public void onDisconnect() {
        connectionCount.decrementAndGet();
    }
}
```

With `@Singleton`, all connections to `/notifications` share the same `NotificationService` instance. The injected `AtmosphereResource` and `AtmosphereResourceEvent` are still request-scoped -- they change with each lifecycle callback.

## Broadcaster Selection

### Default Behavior

When a client connects to a `@ManagedService`, the framework creates (or looks up) a Broadcaster with the same ID as the path and subscribes the client to it. All clients connected to the same path share the same Broadcaster.

### Looking Up Other Broadcasters

Use the injected `BroadcasterFactory` to interact with Broadcasters outside of the default one:

```java
@ManagedService(path = "/chat/{room}")
public class ChatRoom {

    @Inject
    private BroadcasterFactory factory;

    @PathParam("room")
    private String roomName;

    @org.atmosphere.config.service.Message(
            decoders = {JacksonDecoder.class},
            encoders = {JacksonEncoder.class})
    public Message onMessage(Message message) {
        if (message.getMessage().startsWith("/broadcast-all")) {
            // Send to all rooms
            for (Broadcaster b : factory.lookupAll()) {
                b.broadcast(message);
            }
            return null; // don't broadcast on this Broadcaster too
        }
        return message; // normal broadcast to current room
    }
}
```

### The findBroadcaster Method

Prefer `findBroadcaster()` (returns `Optional`) over the older `lookup()` (returns null):

```java
// Preferred -- returns Optional
Optional<Broadcaster> lobby = factory.findBroadcaster("/chat/lobby");
lobby.ifPresent(b -> b.broadcast("Announcement from another room"));

// Legacy -- returns null
Broadcaster b = factory.lookup("/chat/lobby");
if (b != null) {
    b.broadcast("Announcement");
}
```

### Creating Broadcasters On Demand

```java
// Look up by ID, create if it doesn't exist
Broadcaster b = factory.lookup("/chat/new-room", true);

// Create with a specific Broadcaster implementation
Broadcaster b = factory.get(JGroupsBroadcaster.class, "/cluster/chat");
```

## Broadcasting Patterns

### Broadcast to All Subscribers

The most common pattern: return a value from `@Message`.

```java
@org.atmosphere.config.service.Message
public String onMessage(String message) {
    return message; // everyone gets it
}
```

### Broadcast to a Specific Resource

```java
@ManagedService(path = "/chat")
public class DirectMessage {

    @Inject
    private BroadcasterFactory factory;

    @org.atmosphere.config.service.Message(decoders = {JacksonDecoder.class})
    public void onMessage(DirectMessagePayload payload) {
        // Find the target user's Broadcaster
        factory.findBroadcaster("/user/" + payload.getTargetUserId())
               .ifPresent(b -> b.broadcast(payload.getText()));
    }
}
```

### Broadcast to a Subset

Using the `Broadcaster.broadcast(Object, Set<AtmosphereResource>)` overload:

```java
@org.atmosphere.config.service.Message
public void onMessage(String message) {
    Broadcaster b = r.getBroadcaster();
    Set<AtmosphereResource> admins = b.getAtmosphereResources().stream()
            .filter(res -> isAdmin(res))
            .collect(Collectors.toSet());
    b.broadcast("Admin notice: " + message, admins);
}
```

### Don't Broadcast

Return `null` or `void` to handle a message without broadcasting:

```java
@org.atmosphere.config.service.Message(decoders = {JacksonDecoder.class})
public void onMessage(PingMessage ping) {
    // Process ping, don't echo to anyone
    logger.debug("Ping from {}: {}", r.uuid(), ping.getPayload());
}
```

## Broadcast Filters

`BroadcastFilter` implementations can transform or suppress messages before they reach subscribers. Specify them in the `@ManagedService` annotation:

```java
@ManagedService(path = "/chat", broadcastFilters = {ProfanityFilter.class})
public class Chat { ... }
```

A filter implements `BroadcastFilter`:

```java
public class ProfanityFilter implements BroadcastFilter {

    @Override
    public BroadcastAction filter(String broadcasterId,
                                   Object originalMessage,
                                   Object message) {
        if (message instanceof String text && containsProfanity(text)) {
            return new BroadcastAction(BroadcastAction.ACTION.ABORT);
        }
        return new BroadcastAction(message);
    }
}
```

Filter actions:

| Action | Effect |
|--------|--------|
| `BroadcastAction(message)` | Continue with the (possibly modified) message |
| `BroadcastAction(ACTION.ABORT)` | Suppress the message entirely |

## Complete Example: Multi-Room Chat

Putting it all together, here is a production-quality multi-room chat endpoint:

```java
@ManagedService(
    path = "/chat/{room}",
    atmosphereConfig = MAX_INACTIVE + "=120000",
    broadcastFilters = {ProfanityFilter.class})
public class MultiRoomChat {

    private final Logger logger = LoggerFactory.getLogger(MultiRoomChat.class);

    @Inject
    private BroadcasterFactory factory;

    @Inject
    private AtmosphereResource r;

    @Inject
    private AtmosphereResourceEvent event;

    @PathParam("room")
    private String room;

    @Ready(encoders = {JacksonEncoder.class})
    @DeliverTo(DeliverTo.DELIVER_TO.BROADCASTER)
    public ChatEvent onReady() {
        logger.info("Client {} joined room '{}'", r.uuid(), room);
        return new ChatEvent("system", r.uuid() + " joined the room", room);
    }

    @Disconnect
    public void onDisconnect() {
        if (event.isClosedByClient()) {
            logger.info("Client {} left room '{}'", event.getResource().uuid(), room);
            // Notify others
            r.getBroadcaster().broadcast(
                new ChatEvent("system", event.getResource().uuid() + " left", room));
        }
    }

    @Heartbeat
    public void onHeartbeat(AtmosphereResourceEvent event) {
        logger.trace("Heartbeat from {} in room '{}'",
                event.getResource().uuid(), room);
    }

    @org.atmosphere.config.service.Message(
            encoders = {JacksonEncoder.class},
            decoders = {JacksonDecoder.class})
    public ChatEvent onMessage(ChatEvent chatEvent) {
        logger.info("[{}] {}: {}",
                room, chatEvent.getAuthor(), chatEvent.getText());
        chatEvent.setRoom(room);
        return chatEvent; // broadcast to all in this room
    }
}
```

Each room (`/chat/general`, `/chat/support`, `/chat/dev`) gets its own Broadcaster and its own instance of `MultiRoomChat`. Messages are isolated to their room. The `ProfanityFilter` applies to all rooms.

## Sample Reference

| Sample | Path | Key Features Demonstrated |
|--------|------|---------------------------|
| Spring Boot Chat | `samples/spring-boot-chat/` | `@ManagedService`, `@Inject`, Jackson codecs, rooms, observability |
| WAR Chat | `samples/chat/` | `@ManagedService` with `@Named` Broadcaster injection, `@Config` custom annotation |

## What is Next

- **[Chapter 4: Transport-Agnostic Design](/docs/tutorial/04-transports/)** -- How the framework negotiates transports, the suspend/resume model, SSE vs WebSocket, and virtual threads.
