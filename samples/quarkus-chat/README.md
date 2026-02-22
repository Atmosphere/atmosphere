# Atmosphere Chat — Quarkus

A real-time chat application on Quarkus with native image support. Uses `@ManagedService` with WebSocket and automatic long-polling fallback.

## What It Demonstrates

- **`@ManagedService`** annotation-driven handler with Jackson encoding/decoding
- **Quarkus extension** — build-time annotation scanning via Jandex, Arc CDI integration
- **GraalVM Native Image** — works out of the box with the `native` profile
- **WebSocket** with transparent long-polling fallback
- **Zero configuration** — the extension auto-registers the servlet

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

### application.js

Vanilla JavaScript using atmosphere.js 5.0 — same client code as the other samples:

```javascript
subscription = await atmosphere.atmosphere.subscribe(
    { url: '/atmosphere/chat', transport: 'websocket', fallbackTransport: 'long-polling' },
    { open: ..., message: ..., close: ..., reconnect: ... }
);
```

1. Connects with WebSocket, falls back to long-polling automatically
2. Prompts user for a name, then broadcasts JSON messages
3. Displays messages with timestamps and author attribution

## Configuration

### application.properties

```properties
quarkus.atmosphere.packages=org.atmosphere.samples.quarkus.chat
```

The extension also supports `quarkus.atmosphere.servlet-path`, `quarkus.atmosphere.session-support`, `quarkus.atmosphere.broadcaster-class`, and other properties — see [Quarkus Getting Started](https://github.com/Atmosphere/atmosphere/wiki/Getting-Started-with-Quarkus) on the wiki.

## Build & Run

```bash
# JVM mode
mvn clean package
java -jar target/quarkus-app/quarkus-run.jar

# Dev mode (live reload)
mvn quarkus:dev

# Native image (requires GraalVM 21+ or Mandrel)
mvn clean package -Pnative
./target/atmosphere-quarkus-chat-4.0.0-SNAPSHOT-runner

# Native via container build (no local GraalVM needed)
mvn clean package -Pnative -Dquarkus.native.container-build=true
```

Open http://localhost:8080/ in multiple browser tabs to chat.

## Project Structure

```
quarkus-chat/
├── pom.xml                                  # Quarkus 3.21+ BOM
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
