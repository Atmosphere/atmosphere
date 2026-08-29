# Low-Level Handlers — the layer under `@ManagedService`

Two feeds, same verbs, one layer apart. `OpsFeedHandler` is a raw `AtmosphereHandler`
registered by `@AtmosphereHandlerService`. `ManagedOpsFeed` answers the same HTTP verbs
through `@ManagedService`. Reading them side by side shows exactly what the annotation
sugars — and what you lose by moving up.

## Run

```bash
./mvnw spring-boot:run -pl samples/spring-boot-low-level-handlers
```

- Raw feed: `http://localhost:8106/atmosphere/raw/ops`
- Managed feed: `http://localhost:8106/atmosphere/managed/ops`
- Console: <http://localhost:8106/atmosphere/console/>
- Listener counters: <http://localhost:8106/api/health>

## The trade, concretely

| | Raw (`@AtmosphereHandlerService`) | Managed (`@ManagedService`) |
|---|---|---|
| Verb dispatch | your own `switch` in `onRequest` | `@Get` / `@Post` / `@Put` / `@Delete` |
| Suspend / resume | you call `resource.suspend()` | `@Ready`, `@Resume` |
| Encoders / decoders | you read the body yourself | `@Message(encoders=…, decoders=…)` |
| Is the registered handler | **yes — your class** | no, a `ManagedAtmosphereHandler` wrapper |
| `@RoomAuth` works | **yes** | **no, silently** |

## Why `@RoomAuth` is on the raw handler and cannot move

`RoomProtocolInterceptor.scanAuthorizer` reads `@RoomAuth` off the class of the
**registered** handler. For a `@ManagedService` or `@RoomService` POJO the registered handler
is a `ManagedAtmosphereHandler` wrapper, so an annotation on your class is never seen and **no
authorizer is installed** — with no error. That is worse than having none, because the
annotation reads as protection that is not there.

It resolves only when your class *is* the registered handler, which is what
`@AtmosphereHandlerService` produces. `RawVsManagedParityTest` asserts both halves: that the
raw handler carries it, and that the managed twin deliberately does not.

**Caveat:** `scanAuthorizer` stops at the first match, so exactly one `RoomAuthorizer` is
installed framework-wide no matter how many handlers declare one.

`OncallRoomAuthorizer` **fails closed**: an absent or unknown identity may read, never
publish. Flipping that to fail open fails the test that names it.

## Three listener layers, side by side

The same connection lifecycle is observable at three depths, and they do not see the same
events. `/api/health` shows all three at once.

| Annotation | Class | Sees |
|---|---|---|
| `@AtmosphereResourceListenerService` | `ConnectionHealth` | per-resource suspend + disconnect, keyed by uuid |
| `@AsyncSupportListenerService` | `TransportHealth` | container-level timeouts and closes, including ones that never reach a resource event |
| `@AtmosphereFrameworkListenerService` | `FrameworkUptime` | framework init/destroy |

`FrameworkUptime` stamps its clock in `onPostInit`, never in `onPreInit` or a constructor —
it reports *confirmed* runtime state, not configuration intent. A unit test asserts uptime is
still zero before init runs.

**Mind which interface you implement.** `@AtmosphereResourceListenerService` installs
`AtmosphereResourceListener` — `onSuspended(String uuid)` / `onDisconnect(String uuid)`. That is
*not* `AtmosphereResourceEventListener` (`onSuspend(AtmosphereResourceEvent)`, `onResume`,
`onThrowable`, …), whose adapter has a confusingly similar name. Extending the wrong one
compiles, carries the annotation, and installs **nothing**: the processor's `newClassInstance`
fails and the exception is swallowed into a warn. This sample shipped that exact bug — its
counters sat at zero until the 2026-08-28 sweep drove a real connect/disconnect and noticed.

## Tests

```bash
./mvnw test -pl samples/spring-boot-low-level-handlers
```

15 tests covering the raw/managed parity claim, the `@RoomAuth` placement constraint, the
fail-closed authorization contract, and the three listener layers.
