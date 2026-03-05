---
title: "Chapter 2: Getting Started"
description: "Build your first Atmosphere application: Maven dependency, a minimal @ManagedService endpoint, and an embedded Jetty server."
sidebar:
  order: 2
---

# Getting Started

This chapter walks you through building a real-time chat endpoint with Atmosphere. By the end, you will have a running server that accepts WebSocket and SSE connections.

## Prerequisites

- **JDK 21** or later
- **Maven 3.9+** (or use the Maven Wrapper `./mvnw`)

## Maven Dependency

Add the Atmosphere runtime to your project:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-runtime</artifactId>
    <version>LATEST</version> <!-- check Maven Central for latest -->
</dependency>
```

All Atmosphere modules share the `org.atmosphere` group ID. The `atmosphere-runtime` artifact is the core framework that provides `Broadcaster`, `AtmosphereResource`, `@ManagedService`, and all transport support.

## The Message Class

Before writing the endpoint, define a simple data class to carry chat messages. This is a plain POJO that Jackson can serialize and deserialize:

```java
package org.atmosphere.samples.chat;

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

    public String getMessage() { return message; }
    public String getAuthor() { return author; }
    public long getTime() { return time; }

    public void setAuthor(String author) { this.author = author; }
    public void setMessage(String message) { this.message = message; }
    public void setTime(long time) { this.time = time; }
}
```

## Encoder and Decoder

Atmosphere uses `Encoder` and `Decoder` interfaces to convert between your domain objects and the wire format. Here is a `JacksonEncoder` that converts a `Message` to JSON:

```java
package org.atmosphere.samples.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.config.managed.Encoder;

import jakarta.inject.Inject;
import java.io.IOException;

public class JacksonEncoder implements Encoder<Message, String> {

    @Inject
    private ObjectMapper mapper;

    @Override
    public String encode(Message m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
```

And the corresponding `JacksonDecoder`:

```java
package org.atmosphere.samples.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.config.managed.Decoder;

import jakarta.inject.Inject;
import java.io.IOException;

public class JacksonDecoder implements Decoder<String, Message> {

    @Inject
    private ObjectMapper mapper;

    @Override
    public Message decode(String s) {
        try {
            return mapper.readValue(s, Message.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
```

Notice that both the encoder and decoder use `@Inject` to receive an `ObjectMapper`. Atmosphere's built-in CDI-like injection handles this automatically.

## Your First @ManagedService Endpoint

This is the complete chat endpoint, taken directly from the Atmosphere chat sample (`samples/chat/src/main/java/org/atmosphere/samples/chat/Chat.java`):

```java
package org.atmosphere.samples.chat;

import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Heartbeat;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.samples.chat.custom.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

@Config
@ManagedService(path = "/chat", atmosphereConfig = MAX_INACTIVE + "=120000")
public class Chat {
    private final Logger logger = LoggerFactory.getLogger(Chat.class);

    @Inject
    @Named("/chat")
    private Broadcaster broadcaster;

    @Inject
    private AtmosphereResource r;

    @Inject
    private AtmosphereResourceEvent event;

    @Heartbeat
    public void onHeartbeat(final AtmosphereResourceEvent event) {
        logger.trace("Heartbeat send by {}", event.getResource());
    }

    @Ready
    public void onReady() {
        logger.info("Browser {} connected (broadcaster: {})", r.uuid(), broadcaster.getID());
    }

    @Disconnect
    public void onDisconnect() {
        if (event.isCancelled()) {
            logger.info("Browser {} unexpectedly disconnected", event.getResource().uuid());
        } else if (event.isClosedByClient()) {
            logger.info("Browser {} closed the connection", event.getResource().uuid());
        }
    }

    @org.atmosphere.config.service.Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) throws IOException {
        logger.info("{} just sent {}", message.getAuthor(), message.getMessage());
        return message;
    }
}
```

Here is what each piece does:

- **`@ManagedService(path = "/chat")`** -- registers this class as a real-time endpoint at `/chat`. Atmosphere creates a `Broadcaster` for this path and subscribes every connecting client.
- **`atmosphereConfig = MAX_INACTIVE + "=120000"`** -- sets the maximum inactivity timeout to 120 seconds.
- **`@Inject` fields** -- Atmosphere injects `Broadcaster` (via `@Named` with the path), `AtmosphereResource`, and `AtmosphereResourceEvent` automatically. Uses `jakarta.inject.Inject` and `jakarta.inject.Named`.
- **`@Ready`** -- called when a client connection is suspended and ready to receive messages.
- **`@Disconnect`** -- called when the client disconnects. The `AtmosphereResourceEvent` tells you whether the disconnect was clean (`isClosedByClient()`) or unexpected (`isCancelled()`).
- **`@Heartbeat`** -- called when the client sends a heartbeat ping.
- **`@Message`** -- called when a message is broadcast. The `decoders` attribute deserializes incoming JSON into a `Message` object. The `encoders` attribute serializes the return value back to JSON before broadcasting. Returning a value from `@Message` broadcasts it to all subscribers on this path.

## Running with Embedded Jetty

To run the endpoint without a WAR container, use embedded Jetty. This is from the `samples/embedded-jetty-websocket-chat` sample:

```java
package org.atmosphere.samples.chat;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereServlet;

public class EmbeddedJettyWebSocketChat {

    public static void main(String[] args) throws Exception {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(8080);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        // Configure WebSocket BEFORE AtmosphereServlet init
        JakartaWebSocketServletContainerInitializer.configure(context,
            (servletContext, serverContainer) -> { });

        // Register AtmosphereServlet
        ServletHolder atmosphereServlet = new ServletHolder(AtmosphereServlet.class);
        atmosphereServlet.setInitParameter(
            ApplicationConfig.ANNOTATION_PACKAGE, "org.atmosphere.samples.chat");
        atmosphereServlet.setInitParameter(
            ApplicationConfig.WEBSOCKET_CONTENT_TYPE, "application/json");
        atmosphereServlet.setInitParameter(
            ApplicationConfig.WEBSOCKET_SUPPORT, "true");
        atmosphereServlet.setInitOrder(1);
        atmosphereServlet.setAsyncSupported(true);
        context.addServlet(atmosphereServlet, "/chat/*");

        server.setHandler(context);
        server.start();
        server.join();
    }
}
```

The key configuration points:

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `ANNOTATION_PACKAGE` | `"org.atmosphere.samples.chat"` | Tells Atmosphere which package to scan for `@ManagedService` classes |
| `WEBSOCKET_CONTENT_TYPE` | `"application/json"` | Sets the content type for WebSocket messages |
| `WEBSOCKET_SUPPORT` | `"true"` | Enables WebSocket transport |
| `setInitOrder(1)` | | Ensures the servlet is loaded on startup |
| `setAsyncSupported(true)` | | Required for long-polling and SSE transports |

Note that `JakartaWebSocketServletContainerInitializer.configure()` must be called **before** `AtmosphereServlet` initializes, so that the WebSocket `ServerContainer` is available in the `ServletContext` when Atmosphere starts up.

## Running with Spring Boot

If you prefer Spring Boot, add the starter dependency instead:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-boot-starter</artifactId>
    <version>LATEST</version> <!-- check Maven Central for latest -->
</dependency>
```

The auto-configuration handles servlet registration for you. Your `@ManagedService` class is identical -- only the path prefix differs by convention. From the Spring Boot chat sample (`samples/spring-boot-chat`):

```java
@ManagedService(path = "/atmosphere/chat", atmosphereConfig = MAX_INACTIVE + "=120000")
public class Chat {

    @Inject
    @Named("/atmosphere/chat")
    private Broadcaster broadcaster;

    @Inject
    private AtmosphereResource r;

    @Inject
    private AtmosphereResourceEvent event;

    @Ready
    public void onReady() {
        logger.info("Browser {} connected (broadcaster: {})", r.uuid(), broadcaster.getID());
    }

    @Disconnect
    public void onDisconnect() {
        if (event.isCancelled()) {
            logger.info("Browser {} unexpectedly disconnected", event.getResource().uuid());
        } else if (event.isClosedByClient()) {
            logger.info("Browser {} closed the connection", event.getResource().uuid());
        }
    }

    @org.atmosphere.config.service.Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) throws IOException {
        logger.info("{} just sent {}", message.getAuthor(), message.getMessage());
        return message;
    }
}
```

The only difference from the standalone version is the path (`/atmosphere/chat` instead of `/chat`). The `@Inject`, `@Ready`, `@Disconnect`, and `@Message` annotations work identically.

## What Just Happened

With the code above, you now have a server that:

1. Listens for client connections at `/chat` (or `/atmosphere/chat` for Spring Boot).
2. Auto-negotiates the transport -- WebSocket if the client supports it, SSE or long-polling as fallback.
3. Subscribes each connecting client to a `Broadcaster` keyed by the path.
4. When any client sends a JSON message, the `@Message` method decodes it, and the returned value is broadcast to **all** subscribers.
5. Heartbeats keep the connection alive. The `@Heartbeat` method is called on each ping.
6. When a client disconnects, the `@Disconnect` method fires and the resource is automatically removed from the `Broadcaster`.

## Next Steps

The next chapter takes a deeper look at every attribute and lifecycle annotation of `@ManagedService`.
