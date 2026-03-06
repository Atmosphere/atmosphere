---
title: "Durable Sessions"
description: "Persist session state across server restarts with the SessionStore SPI, SQLite, Redis, and automatic reconnection via DurableSessionInterceptor."
sidebar:
  order: 17
---

When a server restarts, all in-memory state is lost -- room memberships, broadcaster subscriptions, metadata. Durable sessions persist this state and restore it automatically when a client reconnects.

## How It Works

The durable session lifecycle is driven by the `DurableSessionInterceptor`:

1. **First connect** -- the interceptor creates a `DurableSession`, saves it via the `SessionStore`, and returns a token to the client in the `X-Atmosphere-Session-Token` response header.
2. **During the connection** -- the interceptor registers a disconnect listener that captures the resource's current rooms and broadcaster subscriptions into the session store.
3. **Reconnect** -- if the client sends the token back in the `X-Atmosphere-Session-Token` request header (or as a query parameter), the interceptor restores the session: it re-joins the resource to its previous broadcasters and rooms using `BroadcasterFactory.lookup()` and `RoomManager.room().join()`.
4. **Expiration** -- a background thread periodically calls `store.removeExpired(ttl)` to clean up sessions that have not been seen within the configured TTL (default: 24 hours).

## The DurableSession Record

`DurableSession` is a Java record that captures the full state snapshot:

```java
public record DurableSession(
        String token,
        String resourceId,
        Set<String> rooms,
        Set<String> broadcasters,
        Map<String, String> metadata,
        Instant createdAt,
        Instant lastSeen
) { }
```

It provides factory and copy methods:

- `DurableSession.create(token, resourceId)` -- creates a new session with the current timestamp
- `withRooms(Set<String>)` -- returns a copy with updated rooms
- `withBroadcasters(Set<String>)` -- returns a copy with updated broadcaster IDs
- `withMetadata(Map<String, String>)` -- returns a copy with updated metadata
- `withResourceId(String)` -- returns a copy with a new resource ID and refreshed `lastSeen`

## The SessionStore SPI

The `SessionStore` interface defines the persistence contract:

```java
public interface SessionStore {
    void save(DurableSession session);
    Optional<DurableSession> restore(String token);
    void remove(String token);
    void touch(String token);
    List<DurableSession> removeExpired(Duration ttl);
    default void close() { }
}
```

Three implementations are provided:

| Implementation | Module | Description |
|---------------|--------|-------------|
| `InMemorySessionStore` | `durable-sessions` | `ConcurrentHashMap`-backed, for testing and development. Sessions are lost on restart. |
| `SqliteSessionStore` | `durable-sessions-sqlite` | Embedded SQLite database, zero-config. Perfect for single-node deployments. |
| `RedisSessionStore` | `durable-sessions-redis` | Lettuce-backed Redis store. Shared across cluster nodes. |

### InMemorySessionStore

The default store when no other is configured. Uses a `ConcurrentHashMap` and is only suitable for development and testing since sessions are lost when the JVM exits.

### SqliteSessionStore

Zero-configuration embedded storage. Just add the dependency:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-durable-sessions-sqlite</artifactId>
    <version>LATEST</version> <!-- check Maven Central for latest -->
</dependency>
```

`SqliteSessionStore` creates a SQLite database file with a `durable_sessions` table using WAL journal mode for concurrent read performance. Three constructors are available:

```java
var store = new SqliteSessionStore();                           // default: atmosphere-sessions.db
var store = new SqliteSessionStore(Path.of("/data/sessions")); // custom path
var store = SqliteSessionStore.inMemory();                     // for testing
```

Parent directories are created automatically if they do not exist.

### RedisSessionStore

For clustered deployments where multiple nodes need access to the same session data:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-durable-sessions-redis</artifactId>
    <version>LATEST</version> <!-- check Maven Central for latest -->
</dependency>
```

`RedisSessionStore` uses Lettuce and stores sessions as JSON hashes with a TTL.

## Spring Boot Integration

The `atmosphere-spring-boot-starter` auto-configures durable sessions when a `SessionStore` bean is present. The sample application at `samples/spring-boot-durable-sessions/` demonstrates the full setup.

### SessionStoreConfig.java

From `samples/spring-boot-durable-sessions/`:

```java
@Configuration
public class SessionStoreConfig {

    @Bean
    public SessionStore sessionStore() {
        return new SqliteSessionStore(Path.of("data/sessions.db"));
    }
}
```

That is the only configuration needed. The auto-configuration picks up the `SessionStore` bean and registers the `DurableSessionInterceptor` automatically.

### Chat.java

The `@ManagedService` class does not need any durable-session-specific code. The interceptor handles everything transparently:

```java
@ManagedService(path = "/atmosphere/chat", atmosphereConfig = MAX_INACTIVE + "=120000")
public class Chat {

    private final Logger logger = LoggerFactory.getLogger(Chat.class);

    @Inject
    private AtmosphereResource r;

    @Inject
    private AtmosphereResourceEvent event;

    @Ready
    public void onReady() {
        logger.info("Client {} connected (session will persist across restarts)", r.uuid());
    }

    @Disconnect
    public void onDisconnect() {
        if (event.isCancelled()) {
            logger.info("Client {} unexpectedly disconnected — session saved",
                    event.getResource().uuid());
        } else {
            logger.info("Client {} closed — session saved",
                    event.getResource().uuid());
        }
    }

    @org.atmosphere.config.service.Message(
            encoders = {JacksonEncoder.class},
            decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) {
        logger.info("{} says: {}", message.getAuthor(), message.getMessage());
        return message;
    }
}
```

### DurableSessionsApplication.java

The main class is a standard Spring Boot application:

```java
@SpringBootApplication
public class DurableSessionsApplication {
    public static void main(String[] args) {
        SpringApplication.run(DurableSessionsApplication.class, args);
    }
}
```

## Programmatic Registration

If you are not using Spring Boot auto-configuration, register the interceptor manually:

```java
var store = new SqliteSessionStore(Path.of("data/sessions.db"));
framework.interceptor(new DurableSessionInterceptor(store));
```

The constructor also accepts optional TTL and save interval parameters:

```java
var interceptor = new DurableSessionInterceptor(
    store,
    Duration.ofHours(24),   // session TTL
    Duration.ofMinutes(1)   // cleanup interval
);
framework.interceptor(interceptor);
```

## The DurableSessionInterceptor in Detail

The interceptor runs at `BEFORE_DEFAULT` priority, so it executes before application interceptors.

On each `inspect()` call:

1. It checks for an `X-Atmosphere-Session-Token` header (or query parameter).
2. If a token is found and the session exists in the store, it restores broadcaster subscriptions via `BroadcasterFactory.lookup()` and room memberships via `RoomManager.room().join()`. The session is updated with the new resource ID.
3. If no token is found (or the session expired), a new `DurableSession` is created with a UUID token and saved to the store. The token is returned in the response header.
4. In both cases, a disconnect listener is registered that captures the resource's current rooms and broadcasters into the store when the connection closes.

The interceptor guards against double-save when both `onDisconnect` and `onClose` fire for the same resource by tracking resource UUIDs in a `ConcurrentHashMap.newKeySet()`.

## ConversationPersistence for AI Memory

Separate from session state, the `ConversationPersistence` SPI (in the `atmosphere-ai` module) persists AI conversation history so that users can resume conversations across server restarts:

```java
public interface ConversationPersistence {
    Optional<String> load(String conversationId);
    void save(String conversationId, String data);
    void remove(String conversationId);
    default boolean isAvailable() { return true; }
}
```

Two implementations are provided:

| Implementation | Module |
|---------------|--------|
| `SqliteConversationPersistence` | `durable-sessions-sqlite` |
| `RedisConversationPersistence` | `durable-sessions-redis` |

These share the same backend connections as the corresponding `SessionStore` implementations. The `PersistentConversationMemory` class handles serialization and sliding-window logic on top of the persistence SPI.

## Combining with Clustering

Durable sessions and clustering serve different purposes and work well together:

- **Clustering** (Chapter 16) ensures messages reach all nodes during normal operation
- **Durable sessions** ensure clients can reconnect after a node failure or restart and resume their previous state

For a fully resilient deployment, combine a clustered broadcaster with `RedisSessionStore` so that session state is accessible from any node:

```java
@Bean
public SessionStore sessionStore() {
    return new RedisSessionStore("redis://localhost:6379");
}
```

## Summary

- The `SessionStore` SPI has three implementations: `InMemorySessionStore` (testing), `SqliteSessionStore` (single node), and `RedisSessionStore` (clustered)
- `DurableSessionInterceptor` transparently saves and restores room memberships and broadcaster subscriptions using the `X-Atmosphere-Session-Token` header
- `DurableSession` is a Java record capturing token, resource ID, rooms, broadcasters, metadata, and timestamps
- Spring Boot auto-configures the interceptor when a `SessionStore` bean is present
- `ConversationPersistence` provides a parallel SPI for persisting AI conversation history
- No application code changes are needed -- the interceptor works transparently with any `@ManagedService`

Next up: [Chapter 18: Observability](/docs/tutorial/18-observability/) covers Micrometer metrics, OpenTelemetry tracing, and health checks.
