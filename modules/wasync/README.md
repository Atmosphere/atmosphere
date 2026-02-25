# wAsync — Async Java Client for Atmosphere

A fluent, lightweight WebSocket/HTTP client for the [Atmosphere Framework](https://github.com/Atmosphere/atmosphere), powered by `java.net.http` (JDK 21+). Zero external dependencies beyond SLF4J.

wAsync supports **WebSocket**, **Server-Sent Events (SSE)**, **HTTP Streaming**, **Long-Polling**, and **gRPC** transports with automatic fallback, reconnection, and a type-safe encoder/decoder pipeline.

## Quick Start

### Maven

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-wasync</artifactId>
    <version>4.0.4</version>
</dependency>
```

### Connect and Listen

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

wAsync supports four transports. Specify one or chain them for automatic fallback:

| Transport | Protocol | Use Case |
|-----------|----------|----------|
| `WEBSOCKET` | Full-duplex WebSocket | Real-time bidirectional messaging |
| `SSE` | Server-Sent Events | Server push over HTTP |
| `STREAMING` | HTTP chunked streaming | Continuous server push |
| `LONG_POLLING` | Repeated HTTP requests | Universal fallback |
| `GRPC` | gRPC bidirectional streaming | High-performance binary protocol over HTTP/2 |

### Transport Fallback

When multiple transports are specified, wAsync tries them in order and falls back automatically:

```java
var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
        .uri("http://localhost:8080/chat")
        .transport(Request.TRANSPORT.WEBSOCKET)      // try first
        .transport(Request.TRANSPORT.SSE)             // fallback
        .transport(Request.TRANSPORT.LONG_POLLING)    // last resort
        .build();
```

### SSE Example

```java
var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
        .uri("http://localhost:8080/events")
        .transport(Request.TRANSPORT.SSE)
        .build();

var socket = client.create()
        .on(Event.MESSAGE, msg -> System.out.println("SSE event: " + msg))
        .open(request);
```

### Long-Polling Example

```java
var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
        .uri("http://localhost:8080/updates")
        .transport(Request.TRANSPORT.LONG_POLLING)
        .build();

var socket = client.create()
        .on(Event.MESSAGE, msg -> System.out.println("Poll result: " + msg))
        .open(request);

// Sending data works the same way — POST under the hood
socket.fire("{\"action\": \"subscribe\", \"topic\": \"news\"}");
```

### gRPC Example

Connect to an Atmosphere gRPC server using bidirectional streaming. Requires `atmosphere-grpc`, `grpc-netty-shaded`, `grpc-protobuf`, and `grpc-stub` on the classpath.

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-grpc</artifactId>
    <version>4.0.4</version>
</dependency>
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-netty-shaded</artifactId>
    <version>1.71.0</version>
</dependency>
```

```java
var client = AtmosphereClient.newClient();

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

The gRPC transport uses Protocol Buffers over HTTP/2 for high-performance binary messaging. On connect, it automatically subscribes to the URI path as a topic.

## Events

Subscribe to lifecycle events using `on()`:

```java
socket.on(Event.OPEN, o -> { /* connected */ })
      .on(Event.MESSAGE, m -> { /* message received */ })
      .on(Event.CLOSE, c -> { /* disconnected */ })
      .on(Event.ERROR, e -> { /* error occurred */ })
      .on(Event.REOPENED, r -> { /* reconnected after disconnect */ })
      .on(Event.STATUS, s -> { /* HTTP status code (HTTP transports) */ })
      .on(Event.HEADERS, h -> { /* response headers (HTTP transports) */ });
```

## Encoders & Decoders

Transform objects before sending and after receiving with a type-safe pipeline.

### Encoder (client → server)

```java
record ChatMessage(String author, String message) {}

Encoder<ChatMessage, String> jsonEncoder = msg -> {
    return new ObjectMapper().writeValueAsString(msg);
};

var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
        .uri("ws://localhost:8080/chat")
        .transport(Request.TRANSPORT.WEBSOCKET)
        .encoder(jsonEncoder)
        .build();

var socket = client.create()
        .on(Event.MESSAGE, m -> System.out.println(m))
        .open(request);

// Fire a POJO — the encoder converts it to JSON automatically
socket.fire(new ChatMessage("Alice", "Hello!"));
```

### Decoder (server → client)

```java
Decoder<String, ChatMessage> jsonDecoder = new Decoder<>() {
    @Override
    public ChatMessage decode(Event event, String data) {
        if (event == Event.MESSAGE) {
            return new ObjectMapper().readValue(data, ChatMessage.class);
        }
        return null; // return null to skip decoding for non-message events
    }
};

var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
        .uri("ws://localhost:8080/chat")
        .transport(Request.TRANSPORT.WEBSOCKET)
        .decoder(jsonDecoder)
        .build();

var socket = client.create()
        .on(Event.MESSAGE, (Function<ChatMessage>) msg -> {
            System.out.println(msg.author() + ": " + msg.message());
        })
        .open(request);
```

### Built-in Decoders

| Decoder | Purpose |
|---------|---------|
| `PaddingAndHeartbeatDecoder` | Strips whitespace padding and filters heartbeat characters |
| `TrackMessageSizeDecoder` | Handles Atmosphere's length-prefixed message protocol |

## Connection Options

Configure reconnection, timeouts, and the HTTP client:

```java
var options = client.newOptionsBuilder()
        .reconnect(true)                       // auto-reconnect on disconnect
        .reconnectAttempts(5)                  // max retries (-1 = unlimited)
        .pauseBeforeReconnectInSeconds(3)      // delay between retries
        .waitBeforeUnlocking(2000)             // max wait for open() to return (ms)
        .requestTimeoutInSeconds(60)           // HTTP request timeout
        .httpClient(myHttpClient)              // provide your own HttpClient
        .build();

var socket = client.create(options)
        .on(Event.OPEN, o -> System.out.println("Connected"))
        .on(Event.REOPENED, r -> System.out.println("Reconnected!"))
        .open(request);
```

### Sharing an HttpClient

Share a single `HttpClient` across multiple sockets for connection pooling:

```java
var httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

var options = client.newOptionsBuilder()
        .httpClient(httpClient)
        .build();

// Both sockets share the same HttpClient
var socket1 = client.create(options).on(Event.MESSAGE, m -> {}).open(request1);
var socket2 = client.create(options).on(Event.MESSAGE, m -> {}).open(request2);
```

## Atmosphere Protocol

By default, `AtmosphereClient` enables the Atmosphere protocol handshake — the server sends a UUID on first connect, and the client uses it for tracking. Disable it if connecting to a non-Atmosphere server:

```java
var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
        .uri("ws://localhost:8080/chat")
        .transport(Request.TRANSPORT.WEBSOCKET)
        .enableProtocol(false)   // skip UUID handshake
        .build();
```

For a generic (non-Atmosphere) WebSocket/SSE client, use `Client.newClient()` instead:

```java
var client = Client.newClient();

var request = client.newRequestBuilder()
        .uri("ws://echo.websocket.org")
        .transport(Request.TRANSPORT.WEBSOCKET)
        .build();

var socket = client.create()
        .on(Event.MESSAGE, m -> System.out.println("Echo: " + m))
        .open(request);

socket.fire("ping");
```

## Socket Lifecycle

```
INIT  ──open()──▶  OPEN  ──disconnect──▶  CLOSE
                    │                        │
                    │                   reconnect()
                    │                        │
                    ◀────── REOPENED ◄───────┘
                    │
                 close()
                    │
                    ▼
                  CLOSE
```

Check status at any time:

```java
Socket.STATUS status = socket.status();
// INIT → OPEN → REOPENED → CLOSE
```

## Full Chat Example

A complete chat client connecting to an Atmosphere `@ManagedService` endpoint:

```java
import org.atmosphere.wasync.*;
import org.atmosphere.wasync.impl.AtmosphereClient;
import org.atmosphere.wasync.impl.AtmosphereRequestBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ChatClient {

    record ChatMessage(String author, String message, long time) {
        ChatMessage(String author, String message) {
            this(author, message, System.currentTimeMillis());
        }
    }

    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        var client = AtmosphereClient.newClient();

        var options = client.newOptionsBuilder()
                .reconnect(true)
                .reconnectAttempts(10)
                .pauseBeforeReconnectInSeconds(2)
                .build();

        var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
                .uri("ws://localhost:8080/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .transport(Request.TRANSPORT.SSE)
                .transport(Request.TRANSPORT.LONG_POLLING)
                .build();

        var socket = client.create(options)
                .on(Event.OPEN, o -> System.out.println("✓ Connected"))
                .on(Event.REOPENED, r -> System.out.println("✓ Reconnected"))
                .on(Event.MESSAGE, m -> {
                    try {
                        var msg = mapper.readValue(m.toString(), ChatMessage.class);
                        System.out.printf("[%s] %s%n", msg.author(), msg.message());
                    } catch (Exception e) {
                        System.out.println(m);
                    }
                })
                .on(Event.ERROR, e -> System.err.println("✗ Error: " + e))
                .on(Event.CLOSE, c -> System.out.println("✗ Disconnected"))
                .open(request);

        // Read from stdin and send
        try (var scanner = new java.util.Scanner(System.in)) {
            System.out.println("Type a message and press Enter (Ctrl+C to quit):");
            while (scanner.hasNextLine()) {
                var line = scanner.nextLine().strip();
                if (!line.isEmpty()) {
                    socket.fire(mapper.writeValueAsString(
                            new ChatMessage("Me", line)));
                }
            }
        } finally {
            socket.close();
        }
    }
}
```

## Architecture

```
┌─────────────────────────────────────────────────┐
│                  Your Application                │
├─────────────────────────────────────────────────┤
│  Socket API   │  Encoder/Decoder Pipeline       │
│  .on()        │  fire(POJO) → encode → send     │
│  .fire()      │  receive → decode → on(MESSAGE) │
│  .close()     │                                  │
├─────────────────────────────────────────────────┤
│            Transport Layer (auto-fallback)        │
│  ┌──────────┬──────┬──────────┬─────────┬──────┐ │
│  │WebSocket │ SSE  │Streaming │Long-Poll│ gRPC │ │
│  │(duplex)  │(push)│(chunked) │(repeated)│(H2) │ │
│  └──────────┴──────┴──────────┴─────────┴──────┘ │
├─────────────────────────────────────────────────┤
│  java.net.http (JDK 21+)  ·  grpc-java (opt.)   │
│  HttpClient · WebSocket · HttpRequest · gRPC     │
└─────────────────────────────────────────────────┘
```

## Requirements

- **Java 21+**
- **SLF4J** (logging facade — bring your own binding)
- **gRPC transport** (optional): `atmosphere-grpc`, `grpc-netty-shaded`, `grpc-protobuf`, `grpc-stub`
- No other dependencies for WebSocket/SSE/Streaming/Long-Polling

## Building

```bash
# Build the module
./mvnw install -pl modules/wasync

# Run tests
./mvnw test -pl modules/wasync

# Run a single test
./mvnw test -pl modules/wasync -Dtest=ChatIntegrationTest
```

## Migration from wAsync 3.x

If migrating from the standalone [wAsync](https://github.com/Atmosphere/wasync) library:

| wAsync 3.x | wAsync 4.x (this module) |
|-------------|--------------------------|
| `ClientFactory.getDefault().newClient()` | `Client.newClient()` or `AtmosphereClient.newClient()` |
| `AsyncHttpClient` (Netty-based) | `java.net.http.HttpClient` (JDK built-in) |
| `javax.servlet` / `javax.websocket` | `jakarta.servlet` / `jakarta.websocket` |
| Separate Maven artifact `org.atmosphere:wasync` | `org.atmosphere:atmosphere-wasync` (monorepo module) |
| Transport: `Request.TRANSPORT.WEBSOCKET` | Same — API preserved |
| `socket.on(Event.MESSAGE, ...)` | Same — API preserved |
| `socket.fire(...)` | Same — API preserved |

The fluent API (`on()`, `fire()`, `open()`, `close()`) is intentionally unchanged. The main difference is under the hood: virtual threads replace Netty's event loop, and `java.net.http` replaces `AsyncHttpClient`.
