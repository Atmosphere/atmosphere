# Atmosphere Chat — Quarkus

A real-time chat application on Quarkus with native image support. Uses `@ManagedService` with WebSocket and automatic long-polling fallback.

## What It Demonstrates

- **`@ManagedService`** annotation-driven handler with Jackson encoding/decoding
- **Quarkus extension** — build-time annotation scanning via Jandex, Arc CDI integration
- **GraalVM Native Image** — works out of the box with the `native` profile
- **WebSocket** with transparent long-polling fallback
- **Zero configuration** — the extension auto-registers the servlet
- **Admin Control Plane** — live dashboard at `/admin/` with event stream, agent inspection, and operational controls

## Server Side

### Chat.java

Identical to the Spring Boot sample — the same handler works on both platforms:

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

## Client Side

The UI is a pre-built JavaScript bundle served from `src/main/resources/META-INF/resources/` (Quarkus's static resources directory). It uses `atmosphere.js` to subscribe to `/atmosphere/chat` with WebSocket transport and long-polling fallback, prompts the user for a name on first connect, and renders incoming JSON messages (`{ author, message }`) with timestamps.

## Configuration

### application.properties

```properties
quarkus.atmosphere.packages=org.atmosphere.samples.quarkus.chat
```

The extension also supports `quarkus.atmosphere.servlet-path`, `quarkus.atmosphere.session-support`, `quarkus.atmosphere.broadcaster-class`, and other properties — see the [Quarkus integration docs](https://atmosphere.github.io/docs/integrations/quarkus/) for details.

## Build & Run

```bash
# JVM mode
mvn clean package
java -jar target/quarkus-app/quarkus-run.jar

# Dev mode (live reload)
mvn quarkus:dev

# Native image (requires GraalVM 21+ or Mandrel)
mvn clean package -Pnative
./target/atmosphere-quarkus-chat-*-runner

# Native via container build (no local GraalVM needed)
mvn clean package -Pnative -Dquarkus.native.container-build=true
```

Open http://localhost:8080/ in multiple browser tabs to chat.

Open http://localhost:8080/admin/ for the admin dashboard with live event stream and operational controls.

## Project Structure

```
quarkus-chat/
├── pom.xml                                  # Quarkus 3.31.3 BOM
└── src/main/
    ├── java/org/atmosphere/samples/quarkus/chat/
    │   ├── Chat.java                        # @ManagedService handler
    │   ├── Message.java                     # Message POJO
    │   ├── JacksonEncoder.java              # Message → JSON
    │   └── JacksonDecoder.java              # JSON → Message
    └── resources/
        ├── application.properties           # Quarkus + Atmosphere config
        └── META-INF/resources/
            ├── index.html                   # Chat UI
            └── assets/                      # Bundled atmosphere.js + chat client
```

> **Portability**: The `Chat.java` handler is identical across the [WAR](../chat/), [Spring Boot](../spring-boot-chat/), and Quarkus samples — only the packaging and configuration differ.
