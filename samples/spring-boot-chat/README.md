# Atmosphere Chat — Spring Boot

A real-time chat application on Spring Boot, demonstrating rooms, presence, message history, REST API, Micrometer metrics, and Actuator health checks.

## What It Demonstrates

- **`@ManagedService`** annotation-driven handler with Jackson encoding/decoding
- **Room API** — `RoomManager`, `RoomProtocolInterceptor`, message history, presence events
- **REST controller** — `GET /api/rooms` exposing room state and member details
- **Observability** — `AtmosphereMetrics` wired to Micrometer / Spring Boot Actuator
- **Spring DI** — `AtmosphereFramework` and `RoomManager` auto-exposed as beans
- **GraalVM Native Image** — the `native` profile builds this sample into a
  native binary, with the `@ManagedService` chat endpoint discovered at build
  time so it serves connections there; see [Native Image](#native-image) for
  exactly what the CI lane asserts and what it does not

## Server Side

### Chat.java — Real-Time Handler

```java
@ManagedService(path = "/atmosphere/chat", atmosphereConfig = MAX_INACTIVE + "=120000")
public class Chat {

    @Inject private BroadcasterFactory factory;
    @Inject private AtmosphereResource r;

    @Ready
    public void onReady() { /* client connected */ }

    @Disconnect
    public void onDisconnect() { /* client left */ }

    @Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) {
        return message; // returning broadcasts to all subscribers
    }
}
```

### RoomsConfig.java — Room Setup

Configures the Room API with presence tracking and message history:

```java
@Configuration
public class RoomsConfig {

    @Bean
    public RoomManager roomManager() {
        return RoomManager.getOrCreate(framework);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void setupRooms() {
        framework.interceptor(new RoomProtocolInterceptor());

        Room lobby = roomManager().room("lobby");
        lobby.enableHistory(50);  // replay last 50 messages to new joiners
        lobby.onPresence(event -> log.info("{} {} room '{}'",
            event.member(), event.type(), event.room().name()));
    }
}
```

### ChatRoomsController.java — REST API

```java
@RestController
@RequestMapping("/api/rooms")
public class ChatRoomsController {

    @GetMapping
    public List<Map<String, Object>> listRooms() {
        // Returns room name, member count, member details
    }
}
```

### ObservabilityConfig.java — Metrics

```java
@Configuration
public class ObservabilityConfig {

    @EventListener(ApplicationReadyEvent.class)
    public void installMetrics() {
        AtmosphereMetrics.install(framework, meterRegistry);
    }
}
```

Metrics available at `/actuator/metrics/atmosphere.*`:

| Metric | Type | Description |
|--------|------|-------------|
| `atmosphere.connections.active` | Gauge | Active WebSocket/SSE connections |
| `atmosphere.connections.total` | Counter | Total connections opened |
| `atmosphere.messages.broadcast` | Counter | Messages broadcast |
| `atmosphere.broadcasters.active` | Gauge | Active broadcasters |

## Client Side

### index.html

A tabbed UI with three panels:

1. **💬 Chat** — Real-time message exchange with connection status
2. **🏠 Rooms** — Lists rooms, member counts, and member details (calls `GET /api/rooms`)
3. **📊 Observability** — Live health check and Atmosphere metrics from Actuator

### Bundled React app

The UI is a pre-built React app bundled with Vite; the compiled assets live under `src/main/resources/static/assets/` and are loaded by `index.html` as a single ES module. The bundled app uses `atmosphere.js` (WebSocket + long-polling fallback) to subscribe to `/atmosphere/chat` and exchange JSON frames shaped like `{ author, message }`.

## Configuration

### application.yml

```yaml
atmosphere:
  packages: org.atmosphere.samples.springboot.chat

management:
  endpoints:
    web:
      exposure:
        include: health,metrics
  endpoint:
    health:
      show-details: always
```

## Build & Run

```bash
# JVM mode
mvn clean package
java -jar target/atmosphere-spring-boot-chat-*.jar

# Native image (requires GraalVM 25+)
mvn clean package -Pnative
./target/atmosphere-spring-boot-chat
```

Open http://localhost:8080/ in multiple browser tabs to chat.

### Native Image

Annotation discovery normally reads `.class` files off the classpath, and a
native image has none. The `native` profile moves that work to build time: it
binds Spring's `process-aot` goal, which runs the scan on a JVM — reading the
same `atmosphere.packages` property the runtime would have used — and records
the result into `META-INF/atmosphere/annotated-classes.txt`. The same step
registers `Chat`, plus the types its annotations name by class literal
(`JacksonEncoder`, `JacksonDecoder`), for reflection. At startup the framework
reads *every* copy of that file on the classpath (`classpath*:`) and merges
them, so each jar contributes the classes it owns rather than the first one
found hiding the rest.

Nothing else needs configuring here. `atmosphere-runtime` ships GraalVM
reachability metadata for the classes the framework loads by name, at
`META-INF/native-image/org.atmosphere/atmosphere-runtime/reachability-metadata.json`,
and GraalVM reads it straight out of the jar.

The `Spring Boot Native Image` job in `.github/workflows/native-image-ci.yml`
runs whenever this sample changes. It builds the binary, starts it, opens a real
long-polling connection to `/atmosphere/chat`, and fails unless the `@Ready`
method in `Chat.java` ran — a log line that can only appear if the class was
discovered, the handler registered, `@Inject` resolved and the lifecycle fired.
Answering `/actuator/health` is not the assertion; that was the standard this
lane was written to replace.

The same job then drives `scripts/native/NativeTransportProbe.java` — a
zero-dependency JDK client — against the running binary: two WebSocket clients
prove broadcaster fan-out and the `JacksonEncoder`/`JacksonDecoder` `@Message`
round-trip, an SSE subscriber receives a message sent over a WebSocket, and the
Room Protocol runs end to end (`join`/`join_ack`, presence fan-out to the other
member, room broadcast). **Not asserted against the native binary:** transport
negotiation and fallback (an atmosphere.js client behaviour — the probe pins
each transport explicitly), history replay on join, `GET /api/rooms`,
`@Disconnect`/`@Heartbeat`, and the Console. The tests below exercise those on
the JVM.

### Endpoints

| URL | Description |
|-----|-------------|
| `/` | Chat UI |
| `/api/rooms` | REST — room list with members |
| `/actuator/health` | Health check (includes Atmosphere status) |
| `/actuator/metrics/atmosphere.connections.active` | Connection gauge |

## Tests

```bash
mvn test -pl samples/spring-boot-chat
```

| Test | Proves |
|------|--------|
| `WAsyncChatIntegrationTest` | Chat broadcast delivery over WebSocket, SSE, and long-polling using the wAsync Java client |
| `ChatRoomsIntegrationTest` | Room API wiring — lobby pre-created with history, `GET /api/rooms` shape |
| `RoomPresenceDeliveryTest` | **Presence actually tracks membership.** Two real WebSocket subscribers join/leave over the Room Protocol; a `presence/join` then `presence/leave` frame is delivered to the other member, `Room#size()` / `memberInfo()` and `GET /api/rooms` advance to two members and shrink back, and server-side `PresenceEvent`s fire. The Java/JVM mirror of the browser-side `presence-count.spec.ts` / `rooms-api.spec.ts` Playwright specs. |

## Project Structure

```
spring-boot-chat/
├── pom.xml                              # Spring Boot 4.1.0 parent
└── src/main/
    ├── java/org/atmosphere/samples/springboot/chat/
    │   ├── ChatApplication.java         # @SpringBootApplication entry point
    │   ├── Chat.java                    # @ManagedService handler
    │   ├── RoomsConfig.java             # Room API + presence + history
    │   ├── ObservabilityConfig.java     # Micrometer metrics
    │   ├── ChatRoomsController.java     # REST /api/rooms
    │   ├── Message.java                 # Message POJO
    │   ├── JacksonEncoder.java          # Message → JSON
    │   └── JacksonDecoder.java          # JSON → Message
    └── resources/
        ├── application.yml              # Spring Boot + Atmosphere config
        └── static/
            ├── index.html               # Tabbed chat UI
            └── assets/                  # Bundled atmosphere.js + chat client
```
