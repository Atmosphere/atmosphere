---
title: "Chapter 5: Broadcaster & Pub/Sub"
description: "The Broadcaster as the pub/sub hub: DefaultBroadcaster, SimpleBroadcaster, BroadcasterFactory, BroadcasterCache, BroadcastFilter, and delivery semantics."
sidebar:
  order: 5
---

# Broadcaster & Pub/Sub

The `Broadcaster` is the pub/sub engine at the heart of Atmosphere. Every real-time interaction flows through it: clients subscribe by connecting to a path, and messages are delivered to all subscribers when you call `broadcast()`. This chapter covers the `Broadcaster` API, the built-in implementations, `BroadcasterFactory` for managing multiple broadcasters, `BroadcasterCache` for missed messages, `BroadcastFilter` for message transformation, and `@DeliverTo` for controlling delivery scope.

## How Broadcasting Works

When you annotate a class with `@ManagedService(path = "/chat")`, Atmosphere creates a `Broadcaster` for the `/chat` path. Every client that connects to `/chat` is subscribed to that `Broadcaster` as an `AtmosphereResource`. When your `@Message` method returns a value, that value is broadcast to every subscribed resource.

The flow:

1. Client connects to `/chat` -- Atmosphere creates an `AtmosphereResource` and adds it to the `/chat` `Broadcaster`.
2. Client sends a message -- Atmosphere invokes your `@Message` method.
3. Your method returns a value -- Atmosphere calls `broadcaster.broadcast(returnValue)`.
4. The `Broadcaster` delivers the message to every subscribed `AtmosphereResource`, using the appropriate transport for each client.

## The Broadcaster Interface

The `Broadcaster` interface (`org.atmosphere.cpr.Broadcaster`) defines the core operations:

### Broadcasting Messages

```java
// Broadcast to ALL subscribers
Future<Object> broadcast(Object message);

// Broadcast to a SPECIFIC subscriber
Future<Object> broadcast(Object message, AtmosphereResource resource);

// Broadcast to a SUBSET of subscribers
Future<Object> broadcast(Object message, Set<AtmosphereResource> subset);
```

All broadcast methods return a `Future<Object>`. Broadcasting is asynchronous by default -- the `Broadcaster` uses an `ExecutorService` to deliver messages. Use `future.get()` if you need to wait for delivery to complete.

### Managing Subscribers

```java
// Get all subscribed resources
Collection<AtmosphereResource> getAtmosphereResources();

// Add a resource (subscribe)
Broadcaster addAtmosphereResource(AtmosphereResource resource);

// Remove a resource (unsubscribe)
Broadcaster removeAtmosphereResource(AtmosphereResource resource);
```

### Identification

```java
// Get the broadcaster's ID (typically the path)
String getID();

// Set the broadcaster's ID
void setID(String name);
```

### Scheduled Broadcasting

```java
// Broadcast after a delay
Future<Object> delayBroadcast(Object message);
Future<Object> delayBroadcast(Object message, long delay, TimeUnit unit);

// Broadcast periodically
Future<Object> scheduleFixedBroadcast(Object message, long period, TimeUnit unit);
Future<Object> scheduleFixedBroadcast(Object message, long waitFor, long period, TimeUnit unit);
```

## DefaultBroadcaster

`DefaultBroadcaster` is the default implementation used by `@ManagedService`. It:

- Supports all transports (WebSocket, SSE, long-polling, streaming)
- Uses an `ExecutorService` for asynchronous message delivery
- Uses `ReentrantLock` (not `synchronized`) to avoid virtual thread pinning on JDK 21+
- Supports `BroadcasterCache` and `BroadcastFilter`
- Handles subscriber lifecycle (automatic removal on disconnect)

You rarely need to interact with `DefaultBroadcaster` directly. The `@ManagedService` annotation uses it automatically:

```java
@ManagedService(path = "/chat")  // uses DefaultBroadcaster by default
public class Chat { ... }
```

## SimpleBroadcaster

`SimpleBroadcaster` extends `DefaultBroadcaster` and is a lighter-weight implementation designed for WebSocket-only scenarios. It is typically used with `@WebSocketHandlerService`:

```java
@WebSocketHandlerService(path = "/chat", broadcaster = SimpleBroadcaster.class)
public class WebSocketChat extends WebSocketStreamingHandlerAdapter { ... }
```

You can also use it with `@ManagedService` if you know all clients will use WebSocket:

```java
@ManagedService(path = "/chat", broadcaster = SimpleBroadcaster.class)
public class Chat { ... }
```

## BroadcasterFactory

`BroadcasterFactory` is the registry that manages all active `Broadcaster` instances. You inject it and use it to look up, create, or iterate over broadcasters.

### Injection

```java
@Inject
private BroadcasterFactory factory;
```

### Looking Up Broadcasters

```java
// Look up by ID (returns null if not found)
Broadcaster b = factory.lookup("/chat");

// Look up by ID, creating if it does not exist
Broadcaster b = factory.lookup("/chat", true);

// Look up with Optional (preferred over lookup which returns null)
Optional<Broadcaster> b = factory.findBroadcaster("/chat");
```

The `findBroadcaster` method (added in 4.0) returns an `Optional` instead of null, making the absent-broadcaster case explicit at the call site.

### Looking Up with Type

```java
// Look up with a specific Broadcaster type
SimpleBroadcaster b = factory.lookup(SimpleBroadcaster.class, "/chat");

// Look up with type, creating if it does not exist
SimpleBroadcaster b = factory.lookup(SimpleBroadcaster.class, "/chat", true);
```

### Listing All Broadcasters

```java
// Get all active broadcasters
Collection<Broadcaster> all = factory.lookupAll();
```

### Creating New Broadcasters

```java
// Create a broadcaster with a generated ID
Broadcaster b = factory.get();

// Create a broadcaster with a specific ID
Broadcaster b = factory.get("/my-channel");

// Create a broadcaster with a specific type and ID
SimpleBroadcaster b = factory.get(SimpleBroadcaster.class, "/my-channel");
```

### Practical Example: Cross-Broadcaster Messaging

Using `BroadcasterFactory`, you can send messages from one endpoint to subscribers of another:

```java
@ManagedService(path = "/notifications")
public class Notifications {

    @Inject
    private BroadcasterFactory factory;

    @org.atmosphere.config.service.Message
    public void onMessage(String message) {
        // Forward the message to the /chat broadcaster as well
        factory.findBroadcaster("/chat").ifPresent(b -> b.broadcast(message));
    }
}
```

## BroadcasterCache

When a client temporarily disconnects (network glitch, page navigation, etc.), messages broadcast during the disconnection are lost unless a `BroadcasterCache` is configured. The cache stores messages and replays them when the client reconnects.

### UUIDBroadcasterCache

`UUIDBroadcasterCache` is the default cache used by `@ManagedService`. It tracks which messages each `AtmosphereResource` has received using the resource's UUID. When a client reconnects with the same UUID, any missed messages are delivered.

```java
@ManagedService(path = "/chat", broadcasterCache = UUIDBroadcasterCache.class)  // this is the default
public class Chat { ... }
```

The `UUIDBroadcasterCache` is configured automatically when you use `@ManagedService`. You do not need to set it explicitly unless you want to use a different implementation.

### How It Works

1. When a message is broadcast, the cache stores it along with the set of resource UUIDs that received it.
2. When a client reconnects, the cache checks which messages that client's UUID has not received.
3. Those missed messages are delivered to the reconnecting client.
4. Cached messages expire after a configurable time period.

## BroadcastFilter

A `BroadcastFilter` intercepts messages before they are delivered to subscribers. Filters can transform the message, pass it through unchanged, or abort delivery entirely.

### The BroadcastFilter Interface

```java
public interface BroadcastFilter {

    record BroadcastAction(ACTION action, Object message, Object originalMessage) {

        public enum ACTION {
            CONTINUE,  // pass to next filter
            ABORT,     // discard the message
            SKIP       // stop filtering, deliver the current message
        }
    }

    BroadcastAction filter(String broadcasterId, Object originalMessage, Object message);
}
```

The `filter` method receives the broadcaster ID, the original message, and the (possibly already transformed) message. It returns a `BroadcastAction` that tells the framework what to do:

- **`CONTINUE`** -- pass the (possibly transformed) message to the next filter in the chain.
- **`ABORT`** -- discard the message entirely; it will not be delivered.
- **`SKIP`** -- stop the filter chain and deliver the message as-is.

### Configuring Filters

Filters are added via the `broadcastFilters` attribute of `@ManagedService`:

```java
@ManagedService(path = "/chat", broadcastFilters = {XSSHtmlFilter.class})
public class Chat { ... }
```

Multiple filters are applied in the order they are listed.

Alternatively, annotate the filter class itself with `@BroadcasterFilterService` and Atmosphere will discover and register it at startup:

```java
@BroadcasterFilterService
public class ProfanityFilter implements BroadcastFilter { /* ... */ }
```

### PerRequestBroadcastFilter

The standard `BroadcastFilter` sees the message but not the target client. When you need to transform or filter differently per subscriber (e.g., based on role or session data), implement `PerRequestBroadcastFilter`. It adds a four-argument `filter` method that receives the `AtmosphereResource` being delivered to:

```java
public class RoleFilter implements PerRequestBroadcastFilter {
    @Override
    public BroadcastAction filter(String broadcasterId,
                                   AtmosphereResource r,
                                   Object originalMessage,
                                   Object message) {
        // Access r.getRequest() for session/auth info
        if (isAdmin(r)) {
            return new BroadcastAction(ACTION.CONTINUE, message);
        }
        return new BroadcastAction(ACTION.CONTINUE, redact(message));
    }

    @Override
    public BroadcastAction filter(String broadcasterId,
                                   Object originalMessage,
                                   Object message) {
        return new BroadcastAction(ACTION.CONTINUE, message);
    }
}
```

The four-argument method is called once per subscriber, per message. The three-argument method (from `BroadcastFilter`) is called once per broadcast, before the per-resource pass. Return `CONTINUE` from the three-argument method to let the per-resource filter run.

## BroadcasterListener

A `BroadcasterListener` receives lifecycle events from a `Broadcaster` -- creation, destruction, resource add/remove, and message queuing. Annotate the class with `@BroadcasterListenerService` for automatic discovery:

```java
@BroadcasterListenerService
public class MyListener implements BroadcasterListener {
    public void onPostCreate(Broadcaster b) { /* new broadcaster */ }
    public void onComplete(Broadcaster b) { /* broadcast delivered */ }
    public void onPreDestroy(Broadcaster b) { /* broadcaster shutting down */ }
    public void onAddAtmosphereResource(Broadcaster b, AtmosphereResource r) { /* client joined */ }
    public void onRemoveAtmosphereResource(Broadcaster b, AtmosphereResource r) { /* client left */ }
    public void onMessage(Broadcaster b, Deliver deliver) { /* message queued */ }
}
```

This is useful for monitoring, auditing, or triggering side effects when broadcasters are created/destroyed or when clients subscribe/unsubscribe.

## @DeliverTo

By default, the return value of a `@Message` method is broadcast to all subscribers on the endpoint's `Broadcaster`. The `@DeliverTo` annotation changes the delivery scope.

### DELIVER_TO.BROADCASTER

The default behavior -- deliver to all subscribers on this `Broadcaster`:

```java
@org.atmosphere.config.service.Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
@DeliverTo(DELIVER_TO.BROADCASTER)
public Message onMessage(Message message) {
    return message;  // sent to all subscribers on this broadcaster
}
```

### DELIVER_TO.RESOURCE

Deliver only to the resource that sent the message. Useful for acknowledgments or request/response patterns:

```java
@org.atmosphere.config.service.Message(decoders = {JacksonDecoder.class})
@DeliverTo(DELIVER_TO.RESOURCE)
public String onMessage(Message message) {
    return "Received: " + message.getMessage();  // sent only to the sender
}
```

### DELIVER_TO.ALL

Deliver to all resources across **all** `Broadcaster` instances. This is a global broadcast:

```java
@org.atmosphere.config.service.Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
@DeliverTo(DELIVER_TO.ALL)
public Message onMessage(Message message) {
    return message;  // sent to every connected client, on every broadcaster
}
```

### Import

The enum and annotation are in `org.atmosphere.config.service`:

```java
import org.atmosphere.config.service.DeliverTo;
import org.atmosphere.config.service.DeliverTo.DELIVER_TO;
```

## Broadcaster Scope

In addition to `@DeliverTo` (which controls delivery at the method level), the `Broadcaster` itself has a scope that controls which resources it can reach:

```java
public enum SCOPE {
    REQUEST,      // only the current request's AtmosphereResource
    APPLICATION,  // all resources in the current web application (default)
    VM            // all resources in the current JVM
}
```

Most applications use the default `APPLICATION` scope.

## Patterns and Best Practices

### One Broadcaster Per Topic

The natural pattern is one `Broadcaster` per topic or channel. With `@ManagedService`, this happens automatically based on the path:

```java
@ManagedService(path = "/chat/{room}")
public class ChatRoom {
    @PathParam("room")
    private String room;
    // Each room value (/chat/general, /chat/support) gets its own Broadcaster
}
```

### Programmatic Broadcasting

When you need to broadcast from outside a `@ManagedService` class (e.g., from a REST controller or a scheduled task), use `BroadcasterFactory`:

```java
@Inject
private BroadcasterFactory factory;

public void notifyAll(String message) {
    factory.findBroadcaster("/notifications").ifPresent(b -> b.broadcast(message));
}
```

### Targeted Delivery

To send a message to a specific client, use the two-argument `broadcast`:

```java
@Inject
private BroadcasterFactory factory;

public void notifyUser(AtmosphereResource target, String message) {
    factory.findBroadcaster("/notifications").ifPresent(b -> b.broadcast(message, target));
}
```

## Broadcaster vs. Room

The `Broadcaster` is a low-level pub/sub primitive. If your application needs presence tracking, stable member identity, or message history, consider the [Room API](/docs/tutorial/06-rooms/) instead. Rooms wrap Broadcasters and add higher-level features:

| Feature | Broadcaster | Room |
|---------|-------------|------|
| Level | Low-level | High-level |
| Identity | Path-based ID | Named group |
| Presence | Manual tracking | Built-in events |
| Direct messaging | Manual UUID lookup | `room.sendTo(memberId, msg)` |
| Message history | Via BroadcasterCache | `room.enableHistory(n)` |
| Client protocol | Manual | Built into atmosphere.js |

For most chat and collaboration apps, prefer Rooms. Use Broadcaster directly when you need fine-grained control over message delivery, custom filters, or lifecycle policies.

## Summary

| Concept | Purpose |
|---------|---------|
| `Broadcaster` | Pub/sub hub; delivers messages to subscribed `AtmosphereResource` instances |
| `DefaultBroadcaster` | Default implementation; supports all transports, async delivery |
| `SimpleBroadcaster` | Lighter implementation; typically used with WebSocket-only endpoints |
| `BroadcasterFactory` | Registry for looking up, creating, and iterating over `Broadcaster` instances |
| `UUIDBroadcasterCache` | Default cache; replays missed messages on reconnection |
| `BroadcastFilter` | Intercepts and transforms messages before delivery |
| `PerRequestBroadcastFilter` | Per-subscriber message filtering (e.g., role-based redaction) |
| `BroadcasterListener` | Lifecycle events: creation, destruction, subscribe, unsubscribe |
| `@DeliverTo` | Controls delivery scope: `RESOURCE`, `BROADCASTER`, or `ALL` |
