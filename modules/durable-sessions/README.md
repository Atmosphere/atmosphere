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
session re-joins its rooms and broadcasters once the new connection is suspended (a
broadcaster adds a resource that is not suspended yet all the same, but logs that it is
not suspended and that messages its cache holds may then be delivered unexpectedly).
A restored connection that goes before it suspended saves nothing: it holds none of the
session's rooms and broadcasters yet, and the stored session stays as it was. The
state of the connections still open when the framework stops is saved before they are
closed.

A session whose token the server no longer knows (expired, or another store) is
replaced by a new one: the response carries the new token, which the client keeps in
place of the one it sent. The token is a bearer credential for the session's rooms and
broadcasters. atmosphere.js keeps it in the tab's `sessionStorage` so a reload rejoins
the session, and forgets it when the application closes the subscription
(`Subscription.close()`) or calls `clearSessionToken(url)`; a reconnect, a reload and
`Subscription.abandon()` of a connection that already failed keep it.

A client that leaves on purpose also ends the session on the server: a WebSocket closed
with `1000`, or an HTTP transport's close message without the going-away reason (what
`Subscription.close()` sends), removes the stored session, so its token no longer
rejoins anything. A page that is reloaded or left (`1001`, a going-away close message),
a lost connection and a server stop keep the session for its TTL.

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
