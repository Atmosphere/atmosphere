# Atmosphere Chat — WAR Deployment

A real-time chat application deployed as a standard WAR file. Uses `@ManagedService` with WebSocket and automatic long-polling fallback.

## What It Demonstrates

- **`@ManagedService`** annotation-driven handler with `@Ready`, `@Disconnect`, `@Heartbeat`, `@Message`
- **Jackson encoding/decoding** via `@Message(encoders = ..., decoders = ...)`
- **Dependency injection** of `BroadcasterFactory`, `AtmosphereResource`, `Broadcaster`
- **WAR packaging** with `web.xml` configuration
- **WebSocket** with transparent long-polling fallback

## Server Side

### Chat.java

The entire server is one annotated class:

```java
@ManagedService(path = "/chat", atmosphereConfig = MAX_INACTIVE + "=120000")
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

### web.xml

Registers `AtmosphereServlet` with annotation scanning:

```xml
<servlet>
    <servlet-class>org.atmosphere.cpr.AtmosphereServlet</servlet-class>
    <init-param>
        <param-name>org.atmosphere.cpr.packages</param-name>
        <param-value>org.atmosphere.samples</param-value>
    </init-param>
    <load-on-startup>0</load-on-startup>
    <async-supported>true</async-supported>
</servlet>
<servlet-mapping>
    <url-pattern>/chat/*</url-pattern>
</servlet-mapping>
```

## Client Side

The chat UI is the bundled Atmosphere Console, committed under
`src/main/webapp/atmosphere/console/` and kept fresh by
`scripts/sync-console-bundle.sh` (gated in CI). `/` redirects there; the
tiny `ConsoleInfoServlet` at `/api/console/info` points it at the `/chat`
broadcast endpoint.

- The Console subscribes to `/chat` with WebSocket + long-polling fallback via the official `atmosphere.js` client
- Messages flow as `{ author, message }` JSON frames encoded/decoded by the Jackson encoder/decoder on the server

The server POM pulls in the pre-built browser bundle via the WebJars-style dependency `org.atmosphere.client:javascript:4.0.1`, which is what previous versions of this sample used to wire a hand-written `application.js`. The current sample ships the shared Atmosphere Console instead — UI changes belong in `modules/spring-boot-starter/frontend/`, then re-sync.

## Build & Run

```bash
# Build
mvn clean install

# Run with embedded Jetty via Maven plugin
mvn jetty:run
```

Open http://localhost:8080/atmosphere-chat/ in multiple browser tabs to chat.

## Deploying to a Servlet Container

Build the WAR and deploy to any Servlet 6.0+ container:

```bash
mvn clean package
# Deploy target/atmosphere-chat.war to Tomcat, GlassFish, Jetty, etc.
```

## Project Structure

```
chat/
├── pom.xml                              # WAR packaging
└── src/main/
    ├── java/org/atmosphere/samples/chat/
    │   ├── Chat.java                    # @ManagedService handler
    │   ├── Message.java                 # Message POJO
    │   ├── JacksonEncoder.java          # Message → JSON
    │   └── JacksonDecoder.java          # JSON → Message
    └── webapp/
        ├── WEB-INF/web.xml              # AtmosphereServlet config
        ├── index.html                   # Chat UI
        └── assets/                      # Bundled atmosphere.js + chat client
```
