# Durable Sessions Sample

Demonstrates Atmosphere's **durable sessions** feature — WebSocket sessions
that survive server restarts. Clients automatically re-join their rooms and
broadcaster subscriptions on reconnection.

## How It Works

1. **First connection** — the server creates a durable session backed by SQLite and
   sends the client its session token: as the `X-Atmosphere-Session-Token` response
   header, and, to a WebSocket client that asks for it
   (`X-Atmosphere-Session-Token-Inband=true`, which atmosphere.js sends), also in band
   as one `X-Atmosphere-Session-Token=<token>` frame once the connection is
   suspended. A WebSocket client cannot read the upgrade response's headers; a client
   that does not ask never gets the frame.
2. **Server restart** — the SQLite database persists session state (rooms,
   broadcasters, metadata) to disk. The state of the clients still connected when the
   server stops is saved before the framework closes their connections.
3. **Reconnection** — the client sends its token on reconnect. The server restores
   the session and, once the new connection is suspended, re-joins it to its previous
   rooms and broadcasters.

## Idle timeout and heartbeat

The chat closes a connection it has heard nothing from for two minutes
(`MAX_INACTIVE=120000`), so a dead peer the network never reports does not stay subscribed.
Its handshake asks every client for a heartbeat every 30 seconds
(`CLIENT_HEARTBEAT_INTERVAL_IN_SECONDS=30`), which atmosphere.js sends on its own, so a quiet
but live tab is never closed. A client closed by that idle timeout gets the messages broadcast
while it was away when it reconnects (the `UUIDBroadcasterCache` keeps them for its tracking id).

## Messages missed during an outage

When a client loses its connection, the chat's `UUIDBroadcasterCache` keeps what is broadcast
for that client's tracking id and replays it when the client reconnects. The cache drops a
client it has not seen for `clientIdleTime` (60 s by default), and the bundled Console keeps
retrying for longer than that: 10 attempts, waiting 3 s and doubling up to 25 s between them,
up to about 195 s in all. With the default, an outage of ~100 s lost every message broadcast
during it even though the Console reconnected. This sample therefore sets, in
`application.properties` (values in seconds):

```properties
atmosphere.init-params[org.atmosphere.cache.UUIDBroadcasterCache.clientIdleTime]=300
atmosphere.init-params[org.atmosphere.cache.UUIDBroadcasterCache.messageTTL]=300
```

A client the Console is still trying to reconnect keeps its missed messages; the cache reaper
(every 30 s) only drops a client already idle longer than 300 s, so it never cuts that short.
`MissedMessageRetentionTest` pins both limits against the Console's retry schedule. This cache
is in memory: a server restart restores the session (rooms, subscriptions) but not the
messages broadcast before it.

## Running

```bash
./mvnw spring-boot:run -pl samples/spring-boot-durable-sessions
```

## Configuration

In `application.properties`:

```properties
# Enable durable sessions
atmosphere.durable-sessions.enabled=true

# Session TTL — Spring Boot Duration suffix (default: 24h)
atmosphere.durable-sessions.session-ttl=1440m

# Cleanup interval — Spring Boot Duration suffix (default: 60s)
atmosphere.durable-sessions.cleanup-interval=60s
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

This sample bundles only the SQLite backend; the Redis path additionally requires adding the `org.atmosphere:atmosphere-durable-sessions-redis` Maven module to the sample pom (in place of `-sqlite`).

## Client Integration

atmosphere.js handles the token on its own, over every transport: it asks for the
in-band token on WebSocket, keeps the token the server assigns (header or frame, never
handed to your `message` handler), sends it on every reconnect, and keeps a copy in the
tab's `sessionStorage` so a page reload rejoins the session too. Nothing to write:

```typescript
const sub = await atmosphere.subscribe({
  url: '/atmosphere/chat',
  transport: 'websocket',
});
```

The token is a bearer credential: whoever holds it rejoins the session's rooms and
broadcasters. `sub.close()` forgets the kept copy (in memory and in `sessionStorage`), so a
later subscription or a reload starts a new session; `sub.abandon()`, a reconnect and a
reload keep it. Call `clearSessionToken(url)` (exported by `atmosphere.js`) to drop it at
any other time, a sign-out for one.

Pass `sessionToken` only to choose the session yourself (it then wins over the one the
client keeps, until the server hands back a different token because it no longer knew
yours: that one is sent from then on). A raw WebSocket client opts in with
`ws://host/atmosphere/chat?X-Atmosphere-Session-Token-Inband=true`, reads the
`X-Atmosphere-Session-Token=<token>` frame, and reconnects with
`X-Atmosphere-Session-Token=<token>` as a header or query parameter
(`e2e/durable-session-restart.spec.ts` does exactly that).
