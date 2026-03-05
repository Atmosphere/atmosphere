---
title: "wAsync (Java)"
description: "Async Java client for WebSocket, SSE, gRPC"
---

# wAsync -- Java Client

A fluent, lightweight WebSocket/HTTP client for the Atmosphere Framework, powered by `java.net.http` (JDK 21+). Zero external dependencies beyond SLF4J.

Supports **WebSocket**, **Server-Sent Events (SSE)**, **HTTP Streaming**, **Long-Polling**, and **gRPC** transports with automatic fallback, reconnection, and a type-safe encoder/decoder pipeline.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-wasync</artifactId>
    <version>4.0.8-SNAPSHOT</version>
</dependency>
```

## Quick Start

```java
import org.atmosphere.wasync.*;
import org.atmosphere.wasync.impl.AtmosphereClient;
import org.atmosphere.wasync.impl.AtmosphereRequestBuilder;

var client = AtmosphereClient.newClient();

var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
        .uri("ws://localhost:8080/chat")
        .transport(Request.TRANSPORT.WEBSOCKET)
        .build();

var socket = client.create()
        .on(Event.OPEN, o -> System.out.println("Connected!"))
        .on(Event.MESSAGE, m -> System.out.println("Received: " + m))
        .on(Event.CLOSE, c -> System.out.println("Disconnected"))
        .on(Event.ERROR, e -> System.err.println("Error: " + e))
        .open(request);

socket.fire("Hello from wAsync!");
```

## Transports

| Transport | Protocol | Use Case |
|-----------|----------|----------|
| `WEBSOCKET` | Full-duplex WebSocket | Real-time bidirectional messaging |
| `SSE` | Server-Sent Events | Server push over HTTP |
| `STREAMING` | HTTP chunked streaming | Continuous server push |
| `LONG_POLLING` | Repeated HTTP requests | Universal fallback |
| `GRPC` | gRPC bidirectional streaming | High-performance binary over HTTP/2 |

### Transport Fallback

Chain transports for automatic fallback:

```java
var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
        .uri("http://localhost:8080/chat")
        .transport(Request.TRANSPORT.WEBSOCKET)      // try first
        .transport(Request.TRANSPORT.SSE)             // fallback
        .transport(Request.TRANSPORT.LONG_POLLING)    // last resort
        .build();
```

### gRPC Transport

Connect to an Atmosphere gRPC server. Requires `atmosphere-grpc`, `grpc-netty-shaded`, `grpc-protobuf`, and `grpc-stub` on the classpath.

```java
var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
        .uri("grpc://localhost:9090/chat")
        .transport(Request.TRANSPORT.GRPC)
        .build();

var socket = client.create()
        .on(Event.OPEN, o -> System.out.println("gRPC connected"))
        .on(Event.MESSAGE, m -> System.out.println("Received: " + m))
        .open(request);

socket.fire("Hello via gRPC!");
```

## Events

```java
socket.on(Event.OPEN, o -> { /* connected */ })
      .on(Event.MESSAGE, m -> { /* message received */ })
      .on(Event.CLOSE, c -> { /* disconnected */ })
      .on(Event.ERROR, e -> { /* error occurred */ })
      .on(Event.REOPENED, r -> { /* reconnected after disconnect */ })
      .on(Event.STATUS, s -> { /* HTTP status code */ })
      .on(Event.HEADERS, h -> { /* response headers */ });
```

## Encoders and Decoders

### Encoder (client -> server)

```java
Encoder<ChatMessage, String> jsonEncoder = msg ->
    new ObjectMapper().writeValueAsString(msg);

var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
        .uri("ws://localhost:8080/chat")
        .transport(Request.TRANSPORT.WEBSOCKET)
        .encoder(jsonEncoder)
        .build();

socket.fire(new ChatMessage("Alice", "Hello!"));
```

### Decoder (server -> client)

```java
Decoder<String, ChatMessage> jsonDecoder = new Decoder<>() {
    @Override
    public ChatMessage decode(Event event, String data) {
        if (event == Event.MESSAGE) {
            return new ObjectMapper().readValue(data, ChatMessage.class);
        }
        return null;
    }
};
```

## Connection Options

```java
var options = client.newOptionsBuilder()
        .reconnect(true)
        .reconnectAttempts(5)
        .pauseBeforeReconnectInSeconds(3)
        .waitBeforeUnlocking(2000)
        .requestTimeoutInSeconds(60)
        .httpClient(myHttpClient)
        .build();

var socket = client.create(options)
        .on(Event.OPEN, o -> System.out.println("Connected"))
        .open(request);
```

## Architecture

```
+--------------------------------------------------+
|                  Your Application                 |
+--------------------------------------------------+
|  Socket API   |  Encoder/Decoder Pipeline         |
|  .on()        |  fire(POJO) -> encode -> send     |
|  .fire()      |  receive -> decode -> on(MESSAGE)  |
|  .close()     |                                    |
+--------------------------------------------------+
|            Transport Layer (auto-fallback)         |
|  WebSocket | SSE | Streaming | Long-Poll | gRPC   |
+--------------------------------------------------+
|  java.net.http (JDK 21+) | grpc-java (optional)   |
+--------------------------------------------------+
```

## Samples

- [Spring Boot Chat](../samples/spring-boot-chat/) -- server with wAsync-compatible endpoint
- [gRPC Chat](../samples/grpc-chat/) -- gRPC transport example

## See Also

- [Core Runtime](core.md) -- server-side API
- [atmosphere.js](client-javascript.md) -- TypeScript/browser client
- [gRPC Transport](grpc.md)
- [Module README](../modules/wasync/README.md)
