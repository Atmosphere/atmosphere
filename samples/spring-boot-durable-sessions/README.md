# Durable Sessions Sample

Demonstrates Atmosphere's **durable sessions** feature — WebSocket sessions
that survive server restarts. Clients automatically re-join their rooms and
broadcaster subscriptions on reconnection.

## How It Works

1. **First connection** — the server creates a durable session backed by SQLite and
   sends a session token to the client via `X-Atmosphere-Session-Token`.
2. **Server restart** — the SQLite database persists session state (rooms,
   broadcasters, metadata) to disk.
3. **Reconnection** — the client sends its token on reconnect. The server restores
   the session and re-joins the client to all previous rooms.

## Running

```bash
./mvnw spring-boot:run -pl samples/spring-boot-durable-sessions
```

## Configuration

In `application.properties`:

```properties
# Enable durable sessions
atmosphere.durable-sessions.enabled=true

# Session TTL (default: 1440 minutes = 24 hours)
atmosphere.durable-sessions.session-ttl-minutes=1440

# Cleanup interval (default: 60 seconds)
atmosphere.durable-sessions.cleanup-interval-seconds=60
```

## Session Store

This sample uses `SqliteSessionStore` (see `SessionStoreConfig.java`).
For clustered deployments, replace with `RedisSessionStore`:

```java
@Bean
public SessionStore sessionStore() {
    return new RedisSessionStore("redis://localhost:6379");
}
```

## Client Integration

In atmosphere.js, provide the session token on reconnect:

```typescript
const sub = await atmosphere.subscribe({
  url: '/atmosphere/chat',
  transport: 'websocket',
  sessionToken: localStorage.getItem('atmo-session-token'),
});
```
