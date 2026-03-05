---
title: "Chapter 5: Broadcaster & Pub/Sub"
description: "Named channels for broadcasting messages to subscribers with filtering, caching, and lifecycle management"
---

In [Chapter 3](/docs/tutorial/03-managed-service/) you saw `@ManagedService` handle the plumbing of connecting clients and delivering messages. Under the hood, every `@ManagedService` endpoint is backed by a **Broadcaster** -- the central pub/sub primitive in Atmosphere. This chapter takes you inside that abstraction so you can create channels explicitly, route messages between them, filter content, cache messages for offline clients, and control a Broadcaster's lifecycle.

## What Is a Broadcaster?

A Broadcaster is a named channel. You call `broadcaster.broadcast(message)` and every subscribed `AtmosphereResource` (connection) receives it. The Broadcaster handles the fan-out asynchronously, using virtual threads by default on JDK 21+.

```
          broadcast("hello")
                │
                ▼
         ┌─────────────┐
         │  Broadcaster │
         │  "/chat"     │
         └──────┬───────┘
           ┌────┼────┐
           ▼    ▼    ▼
        Client Client Client
          A      B      C
```

Key properties of a Broadcaster:

- **Named** -- identified by a string (typically the URL path, e.g. `"/chat"` or `"/stock/AAPL"`)
- **Asynchronous** -- `broadcast()` returns a `Future<Object>` immediately; delivery happens on a separate thread
- **Filtered** -- messages pass through a chain of `BroadcastFilter`s before reaching subscribers
- **Cached** -- a `BroadcasterCache` stores messages for disconnected clients so they can catch up on reconnect
- **Managed** -- a `BroadcasterLifeCyclePolicy` controls when idle or empty Broadcasters are destroyed

## BroadcasterFactory: Creating and Looking Up Channels

You never `new` a Broadcaster directly. The `BroadcasterFactory` manages all Broadcaster instances for the framework.

### Getting the Factory

Inside a `@ManagedService` class, inject it:

```java
@ManagedService(path = "/chat")
public class Chat {

    @Inject
    private BroadcasterFactory factory;
}
```

Outside of managed code, obtain it from the framework:

```java
BroadcasterFactory factory = framework.getBroadcasterFactory();
```

### Looking Up a Broadcaster

```java
// Look up an existing Broadcaster -- returns null if not found
Broadcaster chat = factory.lookup("/chat");

// Look up or create if it doesn't exist
Broadcaster chat = factory.lookup("/chat", true);

// Prefer Optional-based API (Atmosphere 4.0+)
Optional<Broadcaster> maybeTopic = factory.findBroadcaster("/topic/news");
maybeTopic.ifPresent(b -> b.broadcast("Breaking news"));
```

The `lookup(id, true)` overload is the most common pattern -- it atomically creates the Broadcaster if it doesn't already exist, guaranteeing a single instance per ID.

### Listing All Broadcasters

```java
Collection<Broadcaster> all = factory.lookupAll();
all.forEach(b -> log.info("Active broadcaster: {} ({} subscribers)",
    b.getID(), b.getAtmosphereResources().size()));
```

### Creating a Typed Broadcaster

If you have a custom `Broadcaster` subclass:

```java
MyBroadcaster b = factory.get(MyBroadcaster.class, "/custom-channel");
```

### Removing a Broadcaster

```java
factory.remove("/old-channel");
```

## Subscribing and Unsubscribing

An `AtmosphereResource` (a connected client) subscribes to a Broadcaster by being added to it:

```java
broadcaster.addAtmosphereResource(resource);
```

And unsubscribes by being removed:

```java
broadcaster.removeAtmosphereResource(resource);
```

With `@ManagedService`, subscription happens automatically when a client connects to the endpoint's path. But you can manually manage subscriptions to implement more complex topologies -- for example, subscribing a single client to multiple topic-based Broadcasters.

### Multi-Broadcaster Subscription

```java
@ManagedService(path = "/chat/{room}")
public class MultiRoomChat {

    @Inject
    private BroadcasterFactory factory;

    @Inject
    private AtmosphereResource resource;

    @PathParam("room")
    private String room;

    @Ready
    public void onReady() {
        // The client is auto-subscribed to "/chat/{room}"
        // Also subscribe to the global announcements channel
        Broadcaster global = factory.lookup("/announcements", true);
        global.addAtmosphereResource(resource);
    }

    @Disconnect
    public void onDisconnect() {
        // Atmosphere auto-removes from all Broadcasters on disconnect
    }
}
```

## Broadcasting Messages

### Broadcast to All Subscribers

The simplest operation -- send a message to everyone subscribed to this Broadcaster:

```java
Future<Object> future = broadcaster.broadcast("Hello, everyone!");
```

The `Future` completes once the message has been delivered to all subscribers (or delivery has been attempted). You can block on it if you need synchronous confirmation:

```java
broadcaster.broadcast("critical update").get(); // blocks until delivered
```

### Targeted Broadcast (Single Subscriber)

Send a message to a specific `AtmosphereResource` within the Broadcaster:

```java
broadcaster.broadcast("Private message for you", targetResource);
```

This is useful for direct messages or user-specific notifications.

### Broadcast to a Subset

Send to a specific set of subscribers:

```java
Set<AtmosphereResource> admins = broadcaster.getAtmosphereResources().stream()
    .filter(r -> "admin".equals(r.getRequest().getAttribute("role")))
    .collect(Collectors.toSet());

broadcaster.broadcast("Admin-only alert", admins);
```

### Delayed and Scheduled Broadcasts

```java
// Delay: message is held until the next regular broadcast() call
broadcaster.delayBroadcast("will be sent with next message");

// Delay with timeout: sent after 5 seconds OR at next broadcast(), whichever comes first
broadcaster.delayBroadcast("delayed", 5, TimeUnit.SECONDS);

// Periodic: broadcast this object every 30 seconds
broadcaster.scheduleFixedBroadcast(statusUpdate, 30, TimeUnit.SECONDS);

// Periodic with initial delay: wait 10s, then every 30s
broadcaster.scheduleFixedBroadcast(statusUpdate, 10, 30, TimeUnit.SECONDS);
```

### Broadcast on Resume

For long-polling transports, you can queue a message to be delivered when the connection resumes:

```java
broadcaster.broadcastOnResume("Welcome back");
```

## BroadcastFilter: Transforming Messages

A `BroadcastFilter` intercepts messages after `broadcast()` is called but before they are delivered to subscribers. Use filters to transform, enrich, validate, or suppress messages.

### The BroadcastFilter Interface

```java
public interface BroadcastFilter {

    BroadcastAction filter(String broadcasterId, Object originalMessage, Object message);

    record BroadcastAction(ACTION action, Object message, Object originalMessage) {
        public enum ACTION { CONTINUE, ABORT, SKIP }
    }
}
```

The return value controls what happens next:

| Action | Behavior |
|--------|----------|
| `CONTINUE` | Pass the (possibly transformed) message to the next filter |
| `ABORT` | Discard the message entirely -- no subscriber receives it |
| `SKIP` | Stop the filter chain but deliver the last transformed message |

### Example: Profanity Filter

```java
public class ProfanityFilter implements BroadcastFilter {

    private static final Set<String> BLOCKED = Set.of("badword1", "badword2");

    @Override
    public BroadcastAction filter(String broadcasterId, Object original, Object message) {
        if (message instanceof String text) {
            for (String word : BLOCKED) {
                text = text.replaceAll("(?i)" + word, "***");
            }
            return new BroadcastAction(text);
        }
        return new BroadcastAction(message);
    }
}
```

### Example: JSON Envelope Filter

Wrap every message in a standard JSON envelope:

```java
public class JsonEnvelopeFilter implements BroadcastFilter {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public BroadcastAction filter(String broadcasterId, Object original, Object message) {
        try {
            var envelope = Map.of(
                "timestamp", Instant.now().toString(),
                "channel", broadcasterId,
                "data", message
            );
            return new BroadcastAction(mapper.writeValueAsString(envelope));
        } catch (Exception e) {
            return new BroadcastAction(ACTION.ABORT, message);
        }
    }
}
```

### Registering Filters

With `@ManagedService`:

```java
@ManagedService(path = "/chat", broadcastFilters = {ProfanityFilter.class, JsonEnvelopeFilter.class})
public class Chat { /* ... */ }
```

With `@WebSocketHandlerService`:

```java
@WebSocketHandlerService(path = "/ws", broadcastFilters = {ProfanityFilter.class})
public class WsHandler extends WebSocketHandlerAdapter { /* ... */ }
```

Programmatically via `BroadcasterConfig`:

```java
Broadcaster b = factory.lookup("/chat", true);
b.getBroadcasterConfig().addFilter(new ProfanityFilter());
b.getBroadcasterConfig().addFilter(new JsonEnvelopeFilter());
```

Filters execute in the order they are added. The output of one filter becomes the input to the next.

### PerRequestBroadcastFilter: Per-Client Transformation

A regular `BroadcastFilter` runs once per broadcast. A `PerRequestBroadcastFilter` runs once per subscriber, giving you access to the individual `AtmosphereResource`. This is useful when different clients need different representations of the same message.

```java
public interface PerRequestBroadcastFilter extends BroadcastFilter {

    BroadcastAction filter(String broadcasterId, AtmosphereResource r,
                           Object originalMessage, Object message);
}
```

#### Example: Locale-Aware Filter

```java
public class LocaleFilter implements PerRequestBroadcastFilter {

    @Override
    public BroadcastAction filter(String id, AtmosphereResource r,
                                   Object original, Object message) {
        var locale = r.getRequest().getLocale();
        if (message instanceof Translatable t) {
            return new BroadcastAction(t.translate(locale));
        }
        return new BroadcastAction(message);
    }

    @Override
    public BroadcastAction filter(String id, Object original, Object message) {
        // Global filter -- no per-request context, just pass through
        return new BroadcastAction(message);
    }
}
```

#### Example: Role-Based Redaction

```java
public class RedactFilter implements PerRequestBroadcastFilter {

    @Override
    public BroadcastAction filter(String id, AtmosphereResource r,
                                   Object original, Object message) {
        String role = (String) r.getRequest().getAttribute("role");
        if (!"admin".equals(role) && message instanceof SensitiveData data) {
            return new BroadcastAction(data.redacted());
        }
        return new BroadcastAction(message);
    }

    @Override
    public BroadcastAction filter(String id, Object original, Object message) {
        return new BroadcastAction(message);
    }
}
```

## BroadcasterCache: Offline Message Delivery

When a client disconnects (network glitch, tab switch, reconnect cycle), messages broadcast during the disconnection are lost unless you enable caching. A `BroadcasterCache` stores messages so they can be replayed when the client reconnects.

### UUIDBroadcasterCache

The default cache for `@ManagedService` is `UUIDBroadcasterCache`. It tracks messages per client UUID and delivers them on reconnect.

```java
@ManagedService(path = "/chat", broadcasterCache = UUIDBroadcasterCache.class)
public class Chat { /* ... */ }
```

`@ManagedService` enables `UUIDBroadcasterCache` by default, so you typically don't need to declare it explicitly. If you are using `@WebSocketHandlerService` (which defaults to no cache), you should set it:

```java
@WebSocketHandlerService(path = "/ws", broadcasterCache = UUIDBroadcasterCache.class)
public class WsHandler extends WebSocketHandlerAdapter { /* ... */ }
```

### How It Works

1. When a message is broadcast, the cache stores it keyed by each subscriber's UUID
2. When the write to a subscriber succeeds, the cached copy for that subscriber is removed
3. If the write fails (client disconnected), the message stays in the cache
4. When the client reconnects, `retrieveFromCache()` returns all pending messages
5. A background task periodically evicts expired entries

### Cache Configuration

Configure via servlet init-params or `ApplicationConfig` properties:

```xml
<init-param>
    <param-name>org.atmosphere.cpr.broadcaster.cache.maxPerClient</param-name>
    <param-value>500</param-value>
</init-param>
<init-param>
    <param-name>org.atmosphere.cpr.broadcaster.cache.messageTTL</param-name>
    <param-value>120</param-value> <!-- seconds -->
</init-param>
<init-param>
    <param-name>org.atmosphere.cpr.broadcaster.cache.maxTotal</param-name>
    <param-value>50000</param-value>
</init-param>
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxPerClient` | 1000 | Max cached messages per client. Oldest messages are evicted when exceeded. |
| `messageTTL` | 300 (seconds) | Messages older than this are evicted regardless of delivery status. |
| `maxTotal` | 100,000 | Global cap across all clients. The oldest messages globally are evicted first. |
| `clientIdleTime` | 60 (seconds) | Time after which an inactive client's cache is purged. |
| `invalidateCacheInterval` | 30 (seconds) | How often the background eviction task runs. |

### Programmatic Configuration

```java
Broadcaster b = factory.lookup("/chat", true);
BroadcasterCache cache = b.getBroadcasterConfig().getBroadcasterCache();
if (cache instanceof UUIDBroadcasterCache uuidCache) {
    uuidCache.setMaxPerClient(500);
    uuidCache.setMessageTTL(TimeUnit.MINUTES.toMillis(2));
    uuidCache.setMaxTotal(50_000);
}
```

### Cache Metrics

`UUIDBroadcasterCache` exposes metrics you can use for monitoring:

```java
if (cache instanceof UUIDBroadcasterCache uuidCache) {
    log.info("Cache size: {}, hits: {}, misses: {}, evictions: {}",
        uuidCache.totalSize(),
        uuidCache.hitCount(),
        uuidCache.missCount(),
        uuidCache.evictionCount());
}
```

### BroadcasterCacheInspector

You can inspect (and optionally reject) messages before they enter the cache:

```java
cache.inspector(message -> {
    // Return false to prevent this message from being cached
    if (message.message() instanceof EphemeralNotification) {
        return false;
    }
    return true;
});
```

This is useful for messages that are only meaningful in real-time (typing indicators, cursor positions) and should not be replayed.

## BroadcasterLifeCyclePolicy

When a Broadcaster has no subscribers, should it stay alive, release resources, or be destroyed? The `BroadcasterLifeCyclePolicy` controls this behavior.

### Available Policies

| Policy | Behavior |
|--------|----------|
| `NEVER` | The Broadcaster lives forever. Never destroyed or cleaned up. |
| `IDLE_DESTROY` | Destroy the Broadcaster (remove from factory) after an idle timeout with no activity. |
| `IDLE_RESUME` | Resume all suspended resources and destroy the Broadcaster after idle timeout. |
| `EMPTY_DESTROY` | Destroy the Broadcaster immediately when the last subscriber leaves. |
| `IDLE_EMPTY_DESTROY` | Destroy only if the Broadcaster is both idle AND has no subscribers. |
| `EMPTY` | Release external resources (but keep the Broadcaster) when last subscriber leaves. |
| `IDLE` | Release external resources after idle timeout (Broadcaster stays). |

### Setting the Policy

```java
Broadcaster b = factory.lookup("/temp-channel", true);
b.setBroadcasterLifeCyclePolicy(BroadcasterLifeCyclePolicy.EMPTY_DESTROY);
```

### Custom Idle Timeout

Use the `Builder` for policies with timeouts:

```java
var policy = new BroadcasterLifeCyclePolicy.Builder()
    .policy(ATMOSPHERE_RESOURCE_POLICY.IDLE_DESTROY)
    .idleTime(5, TimeUnit.MINUTES)
    .build();

broadcaster.setBroadcasterLifeCyclePolicy(policy);
```

### Framework-Wide Default

Set the default policy for all Broadcasters via init-param:

```xml
<init-param>
    <param-name>org.atmosphere.cpr.broadcasterLifeCyclePolicy</param-name>
    <param-value>EMPTY_DESTROY</param-value>
</init-param>
```

### Lifecycle Listeners

React to lifecycle events:

```java
broadcaster.addBroadcasterLifeCyclePolicyListener(
    new BroadcasterLifeCyclePolicyListenerAdapter() {
        @Override
        public void onIdle() {
            log.info("Broadcaster {} is idle", broadcaster.getID());
        }

        @Override
        public void onDestroy() {
            log.info("Broadcaster {} destroyed", broadcaster.getID());
        }

        @Override
        public void onEmpty() {
            log.info("Broadcaster {} has no subscribers", broadcaster.getID());
        }
    }
);
```

### Choosing the Right Policy

| Scenario | Recommended Policy |
|----------|--------------------|
| Permanent channels (`/chat`, `/notifications`) | `NEVER` |
| Per-user or per-session channels | `EMPTY_DESTROY` |
| Time-limited rooms (meetings, game lobbies) | `IDLE_DESTROY` with timeout |
| Resource-intensive channels (DB connections, JMS) | `IDLE` or `EMPTY` to release resources without destroying |

## Multi-Broadcaster Patterns

### One Broadcaster Per Topic

```java
@ManagedService(path = "/topic/{name}")
public class TopicEndpoint {

    @PathParam("name")
    private String topicName;

    @Message
    public String onMessage(String msg) {
        // Automatically broadcast to all subscribers of /topic/{name}
        return msg;
    }
}
```

Each unique path gets its own Broadcaster. A client connecting to `/topic/sports` subscribes to a different channel than `/topic/tech`.

### Cross-Broadcaster Publishing

Sometimes a message on one channel should also appear on another:

```java
@ManagedService(path = "/chat/{room}")
public class ChatRoom {

    @Inject
    private BroadcasterFactory factory;

    @PathParam("room")
    private String room;

    @Message
    public String onMessage(String msg) {
        // Also publish to the "all-rooms" feed
        factory.findBroadcaster("/feed/all-rooms")
               .ifPresent(b -> b.broadcast("[" + room + "] " + msg));

        return msg; // broadcast to current room
    }
}
```

### BroadcasterListener: Global Broadcast Events

Listen to events across all Broadcasters:

```java
factory.addBroadcasterListener(new BroadcasterListenerAdapter() {
    @Override
    public void onPostCreate(Broadcaster b) {
        log.info("New broadcaster created: {}", b.getID());
    }

    @Override
    public void onComplete(Broadcaster b) {
        log.info("Broadcast complete on {}", b.getID());
    }

    @Override
    public void onPreDestroy(Broadcaster b) {
        log.info("Broadcaster being destroyed: {}", b.getID());
    }
});
```

## Broadcaster Scope

By default, a Broadcaster delivers to all its subscribers. You can change this with `setScope()`:

| Scope | Behavior |
|-------|----------|
| `APPLICATION` (default) | Broadcast to all subscribers in this web application |
| `REQUEST` | Broadcast only to the resource associated with the current request |
| `VM` | Broadcast across all web applications in the JVM |

```java
broadcaster.setScope(Broadcaster.SCOPE.REQUEST);
```

The `REQUEST` scope is useful for request-response patterns where you want to use the Broadcaster machinery (filters, cache) but send a reply only to the originating client.

## Complete Example: Stock Ticker

Putting it all together -- a stock ticker with per-symbol channels, price formatting, and offline caching:

```java
@ManagedService(path = "/stock/{symbol}",
    broadcasterCache = UUIDBroadcasterCache.class,
    broadcastFilters = {PriceFormatFilter.class})
public class StockTicker {

    @Inject
    private BroadcasterFactory factory;

    @Inject
    private AtmosphereResource resource;

    @PathParam("symbol")
    private String symbol;

    @Ready
    public void onReady() {
        log.info("Client subscribed to {}", symbol);
    }

    @Disconnect
    public void onDisconnect() {
        log.info("Client unsubscribed from {}", symbol);
    }
}
```

A separate service publishes prices:

```java
@Singleton
public class PricePublisher {

    @Inject
    private BroadcasterFactory factory;

    public void publishPrice(String symbol, BigDecimal price) {
        factory.findBroadcaster("/stock/" + symbol)
               .ifPresent(b -> b.broadcast(
                   Map.of("symbol", symbol,
                          "price", price,
                          "time", Instant.now())));
    }
}
```

The filter formats the price:

```java
public class PriceFormatFilter implements BroadcastFilter {

    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    @Override
    public BroadcastAction filter(String id, Object original, Object message) {
        try {
            return new BroadcastAction(mapper.writeValueAsString(message));
        } catch (Exception e) {
            return new BroadcastAction(ACTION.ABORT, message);
        }
    }
}
```

## Summary

| Concept | Purpose |
|---------|---------|
| `Broadcaster` | Named pub/sub channel; delivers messages to all subscribers |
| `BroadcasterFactory` | Creates, looks up, and manages all Broadcasters |
| `BroadcastFilter` | Transforms or suppresses messages before delivery |
| `PerRequestBroadcastFilter` | Per-subscriber message transformation |
| `BroadcasterCache` | Stores messages for disconnected clients |
| `UUIDBroadcasterCache` | Default cache with per-client limits, TTL, and global cap |
| `BroadcasterLifeCyclePolicy` | Controls when idle/empty Broadcasters are destroyed |
| `BroadcasterListener` | Global hooks for Broadcaster creation, completion, and destruction |

In the [next chapter](/docs/tutorial/06-rooms/), you will see how the **Room** API builds on Broadcasters to provide a higher-level abstraction with presence tracking, member metadata, message history, and AI virtual members.
