# Durable Sessions

Sessions survive server restarts. On reconnection, the client sends its session token and the server restores room memberships, broadcaster subscriptions, and metadata.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-durable-sessions</artifactId>
    <version>4.0.14</version>
</dependency>
```

## Quick Start

```properties
atmosphere.durable-sessions.enabled=true
```

The `DurableSessionInterceptor` handles the full lifecycle:

1. Client connects without a token -- a new session is created and the token is returned in the `X-Atmosphere-Session-Token` response header
2. Client reconnects with the token -- broadcaster/room memberships are restored, the resource ID is updated
3. On disconnect -- current state is saved
4. Expired sessions are cleaned up on a background thread (default: 1 hour TTL)

## SessionStore Implementations

Three `SessionStore` implementations ship with Atmosphere:

| Implementation | Module | Use Case |
|---------------|--------|----------|
| `InMemorySessionStore` | `atmosphere-durable-sessions` | Development and testing |
| `SqliteSessionStore` | `atmosphere-durable-sessions-sqlite` | Single-node production |
| `RedisSessionStore` | `atmosphere-durable-sessions-redis` | Clustered production |

### InMemory

Sessions are stored in a `ConcurrentHashMap`. Lost on restart -- suitable for development only.

### SQLite

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-durable-sessions-sqlite</artifactId>
    <version>4.0.14</version>
</dependency>
```

Sessions are persisted to a local SQLite database. Suitable for single-node deployments.

### Redis

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-durable-sessions-redis</artifactId>
    <version>4.0.14</version>
</dependency>
```

Sessions are stored in Redis. Suitable for clustered deployments where any node can restore a session.

## DurableSession Record

```java
public record DurableSession(
    String token,                    // unique session token
    String resourceId,               // AtmosphereResource UUID
    Set<String> rooms,               // room memberships
    Set<String> broadcasters,        // broadcaster subscriptions
    Map<String, String> metadata,    // custom key-value pairs
    Instant createdAt,               // session creation time
    Instant lastSeen                 // last activity time
) { }
```

## Custom SessionStore

Implement the `SessionStore` SPI for custom storage:

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

## Samples

- [Spring Boot Durable Sessions](../samples/spring-boot-durable-sessions/) -- session persistence example

## See Also

- [Core Runtime](core.md)
- [Redis Clustering](redis.md)
