# Atmosphere Durable Sessions

Session persistence across server restarts. On reconnection, the client sends its session token and the server restores room memberships, broadcaster subscriptions, and metadata.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-durable-sessions</artifactId>
    <version>4.0.33</version>
</dependency>
```

## Quick Start

```properties
atmosphere.durable-sessions.enabled=true
```

Three `SessionStore` implementations:

| Implementation | Module | Use Case |
|---------------|--------|----------|
| `InMemorySessionStore` | `atmosphere-durable-sessions` | Development |
| `SqliteSessionStore` | `atmosphere-durable-sessions-sqlite` | Single-node |
| `RedisSessionStore` | `atmosphere-durable-sessions-redis` | Clustered |

## Key Classes

| Class | Purpose |
|-------|---------|
| `SessionStore` | SPI for session persistence |
| `DurableSession` | Record holding session state (token, rooms, broadcasters, metadata) |
| `DurableSessionInterceptor` | `AtmosphereInterceptor` for session lifecycle |
| `InMemorySessionStore` | In-memory implementation for development |

## Full Documentation

See [docs/durable-sessions.md](../../docs/durable-sessions.md) for complete documentation.

## Samples

- [Spring Boot Durable Sessions](../../samples/spring-boot-durable-sessions/)

## Requirements

- Java 21+
- `atmosphere-runtime` (transitive)
