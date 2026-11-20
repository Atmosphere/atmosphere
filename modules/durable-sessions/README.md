# Atmosphere Durable Sessions

Session persistence across server restarts. On reconnection, the client sends its session token and the server restores room memberships, broadcaster subscriptions, and metadata.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-durable-sessions</artifactId>
    <version>${project.version}</version>
</dependency>
```

## Token delivery

The server sends the session token as the `X-Atmosphere-Session-Token` response header.
A WebSocket client cannot read the upgrade response's headers, so one that sends
`X-Atmosphere-Session-Token-Inband=true` (header or query parameter; atmosphere.js does)
also gets one `X-Atmosphere-Session-Token=<token>` frame once its connection is
suspended. A client that does not ask never gets it. The client re-sends the token as
`X-Atmosphere-Session-Token` (header or query parameter) when it reconnects; the restored
session re-joins its rooms and broadcasters once the new connection is suspended. The
state of the connections still open when the framework stops is saved before they are
closed.

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

See <https://atmosphere.github.io/docs/reference/durable-sessions/> for complete documentation.

## Samples

- [Spring Boot Durable Sessions](../../samples/spring-boot-durable-sessions/)

## Requirements

- Java 21+
- `atmosphere-runtime` (transitive)
