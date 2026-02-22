# Atmosphere Chat — Spring Boot

A real-time chat application on Spring Boot, demonstrating rooms, presence, message history, REST API, Micrometer metrics, and Actuator health checks.

## What It Demonstrates

- **`@ManagedService`** annotation-driven handler with Jackson encoding/decoding
- **Room API** — `RoomManager`, `RoomProtocolInterceptor`, message history, presence events
- **REST controller** — `GET /api/rooms` exposing room state and member details
- **Observability** — `AtmosphereMetrics` wired to Micrometer / Spring Boot Actuator
- **Spring DI** — `AtmosphereFramework` and `RoomManager` auto-exposed as beans
- **GraalVM Native Image** — works out of the box with the `native` profile

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

### application-new.js

Vanilla JavaScript using atmosphere.js 5.0:

```javascript
subscription = await atmosphere.atmosphere.subscribe(
    { url: '/atmosphere/chat', transport: 'websocket', fallbackTransport: 'long-polling' },
    { open: ..., message: ..., close: ..., reconnect: ... }
);
```

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
java -jar target/atmosphere-spring-boot-chat-4.0.0-SNAPSHOT.jar

# Native image (requires GraalVM 25+)
mvn clean package -Pnative
./target/atmosphere-spring-boot-chat
```

Open http://localhost:8080/ in multiple browser tabs to chat.

### Endpoints

| URL | Description |
|-----|-------------|
| `/` | Chat UI |
| `/api/rooms` | REST — room list with members |
| `/actuator/health` | Health check (includes Atmosphere status) |
| `/actuator/metrics/atmosphere.connections.active` | Connection gauge |

## Project Structure

```
spring-boot-chat/
├── pom.xml                              # Spring Boot 4.0.2 parent
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
