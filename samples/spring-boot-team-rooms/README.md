# Team Rooms — Atmosphere's classic annotations, end to end

A moderated multi-room team chat with **no AI on the classpath**. Every capability below is
installed by an annotation; none of it is wired by hand.

One endpoint serves every room. Outbound messages are redacted by a global filter. Presence
counts come from a broadcaster listener. A reconnecting client replays only what is still
worth replaying. A flooder gets a `429`.

## Run

```bash
./mvnw spring-boot:run -pl samples/spring-boot-team-rooms
```

Open <http://localhost:8105/atmosphere/console/> — the bundled Atmosphere Console is the UI.
Occupancy and replay counters: <http://localhost:8105/api/presence>.

Change the room by pointing the Console at `/atmosphere/rooms/<anything>`; each distinct path
is a separate room with separate membership and separate history.

## What each annotation does here

| Annotation | Class | The production question it answers |
|---|---|---|
| `@ManagedService` + `@PathParam` | `RoomChat` | One endpoint, N rooms — routing without routing code |
| `@Singleton` | `Announcements` | One instance for a stateless, pathless endpoint |
| `@DeliverTo(ALL)` | `Announcements` | Fan a message out to *every* broadcaster, not just this one |
| `@Ready` / `@Message` / `@Disconnect` / `@Get` | `RoomChat` | Connection lifecycle and the HTTP verb on the same path |
| `@BroadcasterFilterService` | `RedactingFilter` | Moderation a client cannot bypass, on every room at once |
| `@BroadcasterListenerService` | `PresenceRegistry` | Occupancy that survives transport-level drops |
| `@AtmosphereInterceptorService` | `RateLimitInterceptor` | Reject a flooder before any handler sees the message |
| `@BroadcasterCacheService` | `ReplayCache` | Replay-on-reconnect, bounded |
| `@BroadcasterCacheInspectorService` | `RecentOnlyInspector` | *What* is worth caching, as opposed to how much |
| `@BroadcasterCacheListenerService` | `CacheAuditListener` | Make the replay path observable instead of a black box |

## Four things worth copying

**`@Singleton` and `@PathParam` do not mix.** `RoomChat` is deliberately *not* `@Singleton`.
`ManagedServiceInterceptor.mapAnnotatedService` skips per-request instantiation for a
singleton, so a shared `@PathParam` field becomes whatever the last request wrote.
`Announcements` shows where `@Singleton` *is* right: no path template, no per-connection
state. `AnnotationWiringTest` fails if anyone merges the two.

**Cache bounds belong in `configure()`, not a constructor.**
`UUIDBroadcasterCache.configure` assigns `maxPerClient`, `maxTotal` and `messageTTL` from
init parameters, overwriting anything a subclass constructor set. `ReplayCache` tightens them
*after* `super.configure`. A constructor version compiles, reads correctly, and does nothing —
`ServiceAnnotationInstallationTest` pins the placement.

**Backpressure is a rejection, not a drop.** `RateLimitInterceptor` returns
`Action.CANCELLED` with a `429` rather than silently discarding the message, and its tracking
map is capped — a map keyed by a client-controlled id with no bound is a DoS vector.

**A broadcast filter sees the ENCODED payload, not your domain type.** This is the one that
bit hardest. `ManagedAtmosphereHandler` runs the `@Message` encoder *before* the broadcast
filters and hands `IOUtils.deliver` a `RawMessage` wrapping the encoded JSON. A filter written
as `if (message instanceof Message m)` therefore never fires on the managed path — it compiles,
it is installed, its unit tests pass, and the secret goes out verbatim. `RedactingFilter`
handles `Message`, the encoded `String`, and the `RawMessage` wrapper, and
`RedactingFilterTest` feeds it all three shapes. Test your filter with what the wire actually
carries, not with the object you returned.

## Tests

```bash
./mvnw test -pl samples/spring-boot-team-rooms
```

25 tests. They assert behaviour, not absence of exceptions: neutering `RedactingFilter` to a
pass-through fails 3 of them by name, feeding it the encoded JSON or a `RawMessage` fails 2
more, loosening a cache bound past the framework default fails another, and deleting the
`configure()` override fails it a different way.
