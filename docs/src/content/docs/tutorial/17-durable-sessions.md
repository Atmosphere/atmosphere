---
title: "Durable Sessions"
description: "Session persistence across server restarts with InMemory, SQLite, and Redis stores"
---

When a server restarts, all in-memory state is lost — room memberships, broadcaster subscriptions, metadata. Durable sessions persist this state and restore it on reconnection.

## How It Works

1. **First connect** — server creates a `DurableSession`, returns a token via `X-Atmosphere-Session-Token`
2. **Normal operation** — state updated on every room join/leave, broadcaster subscribe/unsubscribe
3. **Disconnect** — state persisted to the store
4. **Reconnect** — client sends token, server restores everything
5. **Expiry** — background thread cleans up sessions older than TTL (default: 1 hour)

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-durable-sessions</artifactId>
    <version>4.0.8-SNAPSHOT</version>
</dependency>
```

## Quick Start

```yaml
# Spring Boot application.yml
atmosphere:
  init-params:
    org.atmosphere.cpr.AtmosphereInterceptor: >
      org.atmosphere.session.DurableSessionInterceptor
```

No changes to your `@ManagedService` code needed.

## The DurableSession Record

```java
public record DurableSession(
    String token,                    // unique session token (UUID)
    String resourceId,               // current AtmosphereResource UUID
    Set<String> rooms,               // room memberships to restore
    Set<String> broadcasters,        // broadcaster subscriptions to restore
    Map<String, String> metadata,    // custom key-value pairs
    Instant createdAt,               // session creation time
    Instant lastSeen                 // last activity time
) { }
```

## SessionStore Implementations

| Implementation | Module | Use Case |
|---------------|--------|----------|
| `InMemorySessionStore` | `atmosphere-durable-sessions` | Development |
| `SqliteSessionStore` | `atmosphere-durable-sessions-sqlite` | Single-node production |
| `RedisSessionStore` | `atmosphere-durable-sessions-redis` | Clustered production |

### SQLite

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-durable-sessions-sqlite</artifactId>
    <version>4.0.8-SNAPSHOT</version>
</dependency>
```

Auto-detected via ServiceLoader. Sessions survive server restarts.

### Redis

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-durable-sessions-redis</artifactId>
    <version>4.0.8-SNAPSHOT</version>
</dependency>
```

Any node in the cluster can restore a session:

```properties
org.atmosphere.redis.url=redis://localhost:6379
```

## Custom SessionStore

Implement the SPI for custom storage (DynamoDB, PostgreSQL, etc.):

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

Register via `META-INF/services/org.atmosphere.session.SessionStore`.

## Client Integration

The atmosphere.js client handles tokens automatically:

```typescript
const subscription = await atmosphere.subscribe({
  url: '/chat',
  transport: 'websocket',
  // Token auto-sent on reconnection via X-Atmosphere-Session-Token
});
```

## Combining with Clustering

| Setup | Session Store | Broadcaster |
|-------|--------------|-------------|
| Single node, dev | InMemory | Default |
| Single node, prod | SQLite | Default |
| Multi-node | Redis | RedisBroadcaster / KafkaBroadcaster |

```yaml
atmosphere:
  broadcaster-class: org.atmosphere.redis.RedisBroadcaster
  init-params:
    org.atmosphere.redis.url: redis://localhost:6379
    org.atmosphere.cpr.AtmosphereInterceptor: >
      org.atmosphere.session.DurableSessionInterceptor
```

## Next Steps

- [Chapter 16: Clustering](/docs/tutorial/16-clustering/) — multi-node broadcasting
- [Chapter 18: Observability](/docs/tutorial/18-observability/) — monitoring sessions
