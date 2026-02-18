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

### application.js

Vanilla JavaScript using atmosphere.js 5.0 (no framework dependencies):

1. Subscribes to `/chat` with WebSocket transport and long-polling fallback
2. On first connection, prompts the user for a name
3. Sends JSON messages: `{ author, message }`
4. Receives and displays messages with timestamps and author attribution

```javascript
subscription = await atmosphere.atmosphere.subscribe(
    { url: '/chat', transport: 'websocket', fallbackTransport: 'long-polling' },
    {
        open:    (res) => { /* show connected status */ },
        message: (res) => { /* parse JSON, display message */ },
        close:   ()    => { /* show disconnected */ },
    }
);

subscription.push(JSON.stringify({ author: 'Alice', message: 'Hello!' }));
```

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
        └── javascript/
            ├── atmosphere.js            # atmosphere.js 5.0 client
            └── application.js           # Chat client logic
```
