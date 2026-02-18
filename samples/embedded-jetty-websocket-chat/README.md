# Atmosphere Chat — Embedded Jetty WebSocket

A real-time chat application using programmatic embedded Jetty with WebSocket. Demonstrates the lower-level `@WebSocketHandlerService` approach (as opposed to `@ManagedService`).

## What It Demonstrates

- **Embedded Jetty** server with programmatic setup (no web.xml)
- **`@WebSocketHandlerService`** — lower-level WebSocket handler
- **`WebSocketStreamingHandlerAdapter`** — streaming text handling
- **Jakarta WebSocket** integration with Jetty's `JakartaWebSocketServletContainerInitializer`
- **Record-based message model** using JDK 21+ records

## Server Side

### EmbeddedJettyWebSocketChat.java

Programmatically configures a Jetty server:

```java
Server server = new Server();
ServerConnector connector = new ServerConnector(server);
connector.setPort(8080);

// WebSocket must be initialized BEFORE AtmosphereServlet
JakartaWebSocketServletContainerInitializer.configure(context, null);

// AtmosphereServlet with annotation scanning
ServletHolder atmo = context.addServlet(AtmosphereServlet.class, "/chat/*");
atmo.setInitParameter(ANNOTATION_PACKAGE, "org.atmosphere.samples.chat");
atmo.setInitParameter(WEBSOCKET_SUPPORT, "true");

server.start();
```

### WebSocketChat.java

Uses the lower-level WebSocket handler API instead of `@ManagedService`:

```java
@WebSocketHandlerService(path = "/chat", broadcaster = SimpleBroadcaster.class)
public class WebSocketChat extends WebSocketStreamingHandlerAdapter {

    @Override
    public void onOpen(WebSocket webSocket) { /* add disconnect listener */ }

    @Override
    public void onTextStream(WebSocket webSocket, Reader reader) {
        // Parse JSON and broadcast to all
        webSocket.broadcast(mapper.writeValueAsString(
            mapper.readValue(br.readLine(), Data.class)));
    }

    public record Data(String author, String message, long time) { }
}
```

## Client Side

Same vanilla JavaScript client as the WAR chat sample — connects to `/chat` with WebSocket transport, prompts for a name, and exchanges JSON messages.

## Build & Run

```bash
# Build
mvn clean install

# Run the embedded server
mvn -Pserver
```

Open http://localhost:8080/ in multiple browser tabs to chat.

## Project Structure

```
embedded-jetty-websocket-chat/
├── pom.xml                              # JAR packaging + exec plugin
└── src/main/
    ├── java/org/atmosphere/samples/chat/
    │   ├── EmbeddedJettyWebSocketChat.java  # Jetty server setup (main)
    │   └── WebSocketChat.java               # @WebSocketHandlerService handler
    └── webapp/
        ├── index.html                       # Chat UI
        └── javascript/
            ├── atmosphere.js                # atmosphere.js 5.0 client
            └── application.js               # Chat client logic
```

> **Note**: For annotation-driven development (`@ManagedService`, `@RoomService`), see the [WAR chat](../chat/), [Spring Boot chat](../spring-boot-chat/), or [Quarkus chat](../quarkus-chat/) samples instead.

