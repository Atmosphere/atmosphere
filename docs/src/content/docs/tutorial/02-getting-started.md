---
title: "Chapter 2: Getting Started"
description: "Build your first Atmosphere application with Spring Boot, a @ManagedService endpoint, and the atmosphere.js TypeScript client."
---

# Getting Started

This chapter walks you through building a complete real-time chat application. By the end, you will have a running server that accepts WebSocket, SSE, and long-polling connections, and a browser client that connects and exchanges JSON messages.

## Deployment Options

Atmosphere 4.0 supports three deployment models. Choose the one that fits your stack:

| Option | Best for | Configuration |
|--------|----------|---------------|
| **Spring Boot starter** | Spring ecosystem, microservices, production apps | `atmosphere-spring-boot-starter` auto-configures everything |
| **Quarkus extension** | Quarkus ecosystem, native image, GraalVM | `quarkus.atmosphere.*` build-time config |
| **Standalone WAR / embedded Jetty** | Custom setups, legacy containers, maximum control | `AtmosphereServlet` registered manually |

This chapter focuses on the **Spring Boot starter** path, as it requires the least boilerplate. The Quarkus and standalone approaches are covered in the integration reference docs.

## Prerequisites

- **JDK 21** or later (virtual threads are enabled by default)
- **Maven 3.9+** (or use the Maven Wrapper `./mvnw`)
- **Node.js 18+** and npm (for the client, or use a CDN)
- A web browser with DevTools (Chrome, Firefox, or Safari)

## Step 1: Create the Project

Start with a standard Spring Boot project. You can use [start.spring.io](https://start.spring.io/) or create the POM manually.

### pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/maven-v4_0_0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>atmosphere-quickstart</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <java.version>21</java.version>
        <atmosphere.version>4.0.11-SNAPSHOT</atmosphere.version>
        <spring-boot.version>4.0.2</spring-boot.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Atmosphere Spring Boot starter (includes atmosphere-runtime) -->
        <dependency>
            <groupId>org.atmosphere</groupId>
            <artifactId>atmosphere-spring-boot-starter</artifactId>
            <version>${atmosphere.version}</version>
        </dependency>

        <!-- Spring Boot Web (provides the embedded Tomcat/Jetty container) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Jakarta Inject for @Inject support in @ManagedService classes -->
        <dependency>
            <groupId>jakarta.inject</groupId>
            <artifactId>jakarta.inject-api</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <version>${spring-boot.version}</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

The key dependency is `atmosphere-spring-boot-starter`. It transitively includes `atmosphere-runtime` and registers the `AtmosphereServlet` with Spring Boot's embedded container. You do not need to configure a `web.xml` or register the servlet manually.

## Step 2: Configure Atmosphere

### src/main/resources/application.yml

```yaml
atmosphere:
  packages: com.example.chat
```

That is the only required configuration. The `atmosphere.packages` property tells Atmosphere which Java package(s) to scan for annotated classes like `@ManagedService`. Multiple packages can be separated by commas:

```yaml
atmosphere:
  packages: com.example.chat, com.example.notifications
```

### Optional Configuration Properties

The starter supports additional properties under the `atmosphere.*` prefix:

| Property | Default | Description |
|----------|---------|-------------|
| `atmosphere.packages` | (required) | Comma-separated packages to scan for `@ManagedService` and other annotations |
| `atmosphere.servlet-path` | `/atmosphere/*` | URL pattern for the Atmosphere servlet |
| `atmosphere.use-virtual-threads` | `true` | Use JDK 21 virtual threads for message dispatching |
| `atmosphere.heartbeat-interval` | `60` | Heartbeat interval in seconds |

## Step 3: Write the Chat Endpoint

### src/main/java/com/example/chat/Chat.java

```java
package com.example.chat;

import jakarta.inject.Inject;

import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Heartbeat;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.BroadcasterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

@ManagedService(path = "/atmosphere/chat", atmosphereConfig = MAX_INACTIVE + "=120000")
public class Chat {

    private final Logger logger = LoggerFactory.getLogger(Chat.class);

    @Inject
    private BroadcasterFactory factory;

    @Inject
    private AtmosphereResource r;

    @Inject
    private AtmosphereResourceEvent event;

    @Heartbeat
    public void onHeartbeat(final AtmosphereResourceEvent event) {
        logger.trace("Heartbeat from {}", event.getResource().uuid());
    }

    @Ready
    public void onReady() {
        logger.info("Client {} connected via {}", r.uuid(), r.transport());
        logger.info("BroadcasterFactory: {}", factory.getClass().getName());
    }

    @Disconnect
    public void onDisconnect() {
        if (event.isCancelled()) {
            logger.info("Client {} unexpectedly disconnected", event.getResource().uuid());
        } else if (event.isClosedByClient()) {
            logger.info("Client {} closed the connection", event.getResource().uuid());
        }
    }

    @org.atmosphere.config.service.Message(
            encoders = {JacksonEncoder.class},
            decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) {
        logger.info("{} says: {}", message.getAuthor(), message.getMessage());
        return message; // returning the message broadcasts it to all subscribers
    }
}
```

Let us walk through this class line by line.

### The @ManagedService Annotation

```java
@ManagedService(path = "/atmosphere/chat", atmosphereConfig = MAX_INACTIVE + "=120000")
```

- **`path`**: The URL path where this endpoint is registered. Clients connect to `ws://localhost:8080/atmosphere/chat` (WebSocket) or `http://localhost:8080/atmosphere/chat` (SSE/Long-Polling).
- **`atmosphereConfig`**: Key-value pairs for per-endpoint configuration. `MAX_INACTIVE=120000` means connections idle for more than 120 seconds are closed.

The `@ManagedService` annotation is a meta-annotation that automatically configures:
- A `UUIDBroadcasterCache` for caching missed messages
- An `AtmosphereResourceLifecycleInterceptor` for managing connection lifecycle
- A `TrackMessageSizeInterceptor` for ensuring messages are delivered completely
- A `HeartbeatInterceptor` for keeping connections alive
- A `SuspendTrackerInterceptor` for tracking suspended connections

### Injection

```java
@Inject
private BroadcasterFactory factory;

@Inject
private AtmosphereResource r;

@Inject
private AtmosphereResourceEvent event;
```

Atmosphere supports `jakarta.inject.Inject` for injecting framework objects into `@ManagedService` classes. When the Spring Boot starter is active, Spring beans are also injectable. The injected `AtmosphereResource` and `AtmosphereResourceEvent` are request-scoped -- they refer to the current connection in the context of the lifecycle method being invoked.

### Lifecycle Methods

- **`@Ready`**: Called when the connection is established and suspended (ready for receiving messages).
- **`@Disconnect`**: Called when the client disconnects, either cleanly or unexpectedly.
- **`@Heartbeat`**: Called when the client sends a heartbeat ping.
- **`@Message`**: Called when the client sends a message. The return value is broadcast to all subscribers.

### Return Value Semantics

```java
@org.atmosphere.config.service.Message(
        encoders = {JacksonEncoder.class},
        decoders = {JacksonDecoder.class})
public Message onMessage(Message message) {
    return message; // broadcasts to ALL subscribers of this Broadcaster
}
```

When a `@Message` method returns a non-null value, Atmosphere broadcasts it to **all** subscribers of the Broadcaster associated with this `@ManagedService`. This is the most common pattern for chat applications: one client sends, everyone receives.

If you return `null`, nothing is broadcast. This is useful when you want to handle the message without echoing it to others.

## Step 4: Define the Message and Codecs

### src/main/java/com/example/chat/Message.java

```java
package com.example.chat;

import java.util.Date;

public class Message {

    private String message;
    private String author;
    private long time;

    public Message() {
        this("", "");
    }

    public Message(String author, String message) {
        this.author = author;
        this.message = message;
        this.time = new Date().getTime();
    }

    public String getMessage() {
        return message;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }
}
```

### src/main/java/com/example/chat/JacksonEncoder.java

```java
package com.example.chat;

import tools.jackson.databind.ObjectMapper;
import org.atmosphere.config.managed.Encoder;

public class JacksonEncoder implements Encoder<Message, String> {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String encode(Message m) {
        return mapper.writeValueAsString(m);
    }
}
```

### src/main/java/com/example/chat/JacksonDecoder.java

```java
package com.example.chat;

import tools.jackson.databind.ObjectMapper;
import org.atmosphere.config.managed.Decoder;

public class JacksonDecoder implements Decoder<String, Message> {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Message decode(String s) {
        return mapper.readValue(s, Message.class);
    }
}
```

The `Encoder` interface converts your domain object to a wire format (typically JSON). The `Decoder` interface converts incoming wire data to your domain object. They are specified on the `@Message` annotation:

```java
@Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
```

You can chain multiple encoders and decoders. They are invoked in order, and the last one's output is used.

> **Note**: The Spring Boot chat sample uses `@Autowired` to inject a Spring-managed `ObjectMapper` into encoders and decoders. This works because the Atmosphere Spring Boot starter wires Spring's bean factory into Atmosphere's object creation. The example above uses a simple `new ObjectMapper()` for simplicity.

## Step 5: The Spring Boot Application Class

### src/main/java/com/example/chat/ChatApplication.java

```java
package com.example.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
```

That is it. No additional configuration beans, no servlet registration, no WebSocket endpoint registration. The `atmosphere-spring-boot-starter` auto-configuration handles everything.

## Step 6: The Client

### Option A: Using npm (recommended for production)

Install the `atmosphere.js` TypeScript client:

```bash
npm install atmosphere.js
```

Then in your JavaScript or TypeScript:

```javascript
import atmosphere from 'atmosphere.js';

const request = {
    url: 'http://localhost:8080/atmosphere/chat',
    contentType: 'application/json',
    transport: 'websocket',
    fallbackTransport: 'long-polling',
    trackMessageLength: true,
};

request.onOpen = function (response) {
    console.log('Connected via', response.transport);
};

request.onMessage = function (response) {
    const message = response.responseBody;
    try {
        const json = JSON.parse(message);
        console.log(`${json.author}: ${json.message}`);
    } catch (e) {
        console.log('Raw message:', message);
    }
};

request.onClose = function (response) {
    console.log('Disconnected');
};

request.onError = function (response) {
    console.error('Error:', response.reasonPhrase);
};

const socket = atmosphere.subscribe(request);

// Send a message
function send(author, text) {
    socket.push(JSON.stringify({
        author: author,
        message: text,
        time: Date.now()
    }));
}
```

### Option B: Inline HTML Page (quick testing)

Create `src/main/resources/static/index.html`:

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Atmosphere Chat</title>
    <script src="https://cdn.jsdelivr.net/npm/atmosphere.js@5/dist/atmosphere.min.js"></script>
    <style>
        body { font-family: system-ui, sans-serif; max-width: 600px; margin: 2rem auto; }
        #messages { border: 1px solid #ccc; height: 400px; overflow-y: scroll; padding: 1rem; }
        .message { margin: 0.5rem 0; }
        .author { font-weight: bold; }
        #controls { margin-top: 1rem; display: flex; gap: 0.5rem; }
        #controls input { flex: 1; padding: 0.5rem; }
        #controls button { padding: 0.5rem 1rem; }
    </style>
</head>
<body>
    <h1>Atmosphere Chat</h1>
    <div id="messages"></div>
    <div id="controls">
        <input id="author" type="text" placeholder="Your name" value="User" />
        <input id="msg" type="text" placeholder="Type a message..." />
        <button onclick="send()">Send</button>
    </div>
    <p id="status">Connecting...</p>

    <script>
        const messagesDiv = document.getElementById('messages');
        const statusEl = document.getElementById('status');
        let socket;

        const request = {
            url: '/atmosphere/chat',
            contentType: 'application/json',
            transport: 'websocket',
            fallbackTransport: 'long-polling',
            trackMessageLength: true,
        };

        request.onOpen = function (response) {
            statusEl.textContent = 'Connected via ' + response.transport;
        };

        request.onMessage = function (response) {
            const raw = response.responseBody;
            if (raw.length === 0) return;
            try {
                const json = JSON.parse(raw);
                const div = document.createElement('div');
                div.className = 'message';
                div.innerHTML =
                    '<span class="author">' + json.author + ':</span> ' +
                    json.message;
                messagesDiv.appendChild(div);
                messagesDiv.scrollTop = messagesDiv.scrollHeight;
            } catch (e) {
                // Heartbeat or non-JSON message
            }
        };

        request.onClose = function () {
            statusEl.textContent = 'Disconnected';
        };

        request.onError = function (response) {
            statusEl.textContent = 'Error: ' + response.reasonPhrase;
        };

        socket = atmosphere.subscribe(request);

        function send() {
            const author = document.getElementById('author').value;
            const msg = document.getElementById('msg').value;
            if (!msg) return;

            socket.push(JSON.stringify({
                author: author,
                message: msg,
                time: Date.now()
            }));
            document.getElementById('msg').value = '';
        }

        // Send on Enter key
        document.getElementById('msg').addEventListener('keydown', function(e) {
            if (e.key === 'Enter') send();
        });
    </script>
</body>
</html>
```

## Step 7: Run the Application

### From the command line

```bash
mvn spring-boot:run
```

Or, if you have the Maven Wrapper:

```bash
./mvnw spring-boot:run
```

### From your IDE

Run the `ChatApplication.main()` method directly.

### Verify

1. Open `http://localhost:8080/` in your browser. You should see the chat interface.
2. Open a second browser tab (or a different browser) to `http://localhost:8080/`.
3. Type a message in one tab and click Send. It should appear in both tabs.
4. Check the server logs -- you should see connection and message events:

```
INFO  Chat - Client abc-123 connected via WEBSOCKET
INFO  Chat - BroadcasterFactory: org.atmosphere.cpr.DefaultBroadcasterFactory
INFO  Chat - User says: Hello!
```

### Verifying Transport Negotiation

Open your browser's DevTools (Network tab) and observe:

- **WebSocket**: You will see a single request with status 101 (Switching Protocols) and a `ws://` connection in the WebSocket tab.
- **If WebSocket fails**: The client will automatically retry with long-polling. You will see repeated HTTP requests to `/atmosphere/chat` with `X-Atmosphere-Transport: long-polling` in the headers.

You can force a specific transport for testing by changing the client configuration:

```javascript
// Force SSE
request.transport = 'sse';
request.fallbackTransport = 'long-polling';

// Force long-polling
request.transport = 'long-polling';
request.fallbackTransport = 'long-polling';
```

## Understanding the Request Flow

Here is what happens when a client connects and sends a message:

```
Client                          Server
  |                               |
  |-- GET /atmosphere/chat ------>|  (1) HTTP request with Atmosphere headers
  |                               |  (2) AtmosphereServlet dispatches to framework
  |                               |  (3) Framework creates AtmosphereResource
  |                               |  (4) Transport negotiation (WebSocket? SSE? LP?)
  |<-- 101 Switching Protocols ---|  (5) WebSocket upgrade (or SSE/LP response)
  |                               |  (6) @Ready method invoked
  |                               |  (7) Resource added to Broadcaster "/atmosphere/chat"
  |                               |
  |-- {"author":"A","msg":"Hi"} ->|  (8) Client sends message
  |                               |  (9) Decoder: JSON string -> Message object
  |                               | (10) @Message method invoked, returns Message
  |                               | (11) Encoder: Message object -> JSON string
  |                               | (12) Broadcaster delivers to all subscribers
  |<- {"author":"A","msg":"Hi"} --|  (13) Each subscriber receives the message
  |                               |
```

## Alternative: Quarkus Quickstart

If you prefer Quarkus, the configuration is equally simple:

### src/main/resources/application.properties

```properties
quarkus.atmosphere.packages=com.example.chat
```

The `Chat.java`, `Message.java`, encoder, and decoder classes are identical. The only differences are the build configuration (Quarkus BOM instead of Spring Boot BOM) and the configuration file format. See the [Quarkus integration reference](/docs/integrations/quarkus/) for full details.

## Alternative: Standalone WAR / Embedded Jetty

For maximum control, you can register `AtmosphereServlet` directly. The `samples/embedded-jetty-websocket-chat` sample demonstrates this approach:

```java
// Programmatic Jetty configuration (no Spring, no Quarkus)
ServletHolder atmosphereServlet = new ServletHolder(AtmosphereServlet.class);
atmosphereServlet.setInitParameter(
    ApplicationConfig.ANNOTATION_PACKAGE,
    "com.example.chat");
atmosphereServlet.setInitParameter(
    ApplicationConfig.WEBSOCKET_SUPPORT, "true");
atmosphereServlet.setInitOrder(1);
atmosphereServlet.setAsyncSupported(true);
context.addServlet(atmosphereServlet, "/chat/*");
```

The `@ManagedService` classes are the same regardless of deployment model.

## Sample Reference

The Atmosphere repository includes several working samples that demonstrate the concepts in this chapter:

| Sample | Path | Description |
|--------|------|-------------|
| Spring Boot Chat | `samples/spring-boot-chat/` | Full chat with rooms, observability, Jackson codecs |
| Quarkus Chat | `samples/quarkus-chat/` | Same chat app on Quarkus 3.21+ |
| Embedded Jetty Chat | `samples/embedded-jetty-websocket-chat/` | Standalone Jetty with programmatic configuration |
| WAR Chat | `samples/chat/` | Traditional WAR deployment |

Each sample is a self-contained Maven project that you can build and run independently.

## Troubleshooting

### "No @ManagedService classes found"

Check that your `atmosphere.packages` configuration points to the correct package. The scanner only looks in the packages you specify.

### WebSocket connection fails immediately

Ensure your embedded container supports WebSocket. Spring Boot's default Tomcat includes WebSocket support. If you are behind a reverse proxy (nginx, Apache), ensure it is configured to forward WebSocket upgrades:

```nginx
location /atmosphere/ {
    proxy_pass http://localhost:8080/atmosphere/;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
}
```

### Messages are not delivered

Verify that `trackMessageLength: true` is set in the client configuration. The `TrackMessageSizeInterceptor` (enabled by default with `@ManagedService`) prepends message length, and the client must be configured to parse it.

### ClassNotFoundException for Jackson

Ensure Jackson is on the classpath. Spring Boot's `spring-boot-starter-web` includes Jackson by default. For standalone deployments, add `jackson-databind` explicitly.

## What is Next

- **[Chapter 3: @ManagedService Deep Dive](/docs/tutorial/03-managed-service/)** -- Full annotation reference, all lifecycle methods, injection, path parameters, delivery semantics.
- **[Chapter 4: Transport-Agnostic Design](/docs/tutorial/04-transports/)** -- How transport negotiation works under the hood, SSE vs WebSocket, virtual threads.
