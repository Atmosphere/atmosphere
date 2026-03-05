---
title: "gRPC Transport"
description: "Bidirectional streaming via grpc-java"
---

# gRPC Transport

Bidirectional streaming transport for Atmosphere using grpc-java. Clients can subscribe, publish, and receive messages over gRPC alongside WebSocket, SSE, and long-polling clients on the same Broadcaster.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-grpc</artifactId>
    <version>LATEST</version> <!-- check Maven Central for latest -->
</dependency>
```

## Standalone Server

Use the gRPC transport without a servlet container:

```java
var framework = new AtmosphereFramework();

try (var server = AtmosphereGrpcServer.builder()
        .framework(framework)
        .port(9090)
        .handler(new MyGrpcHandler())
        .build()) {
    server.start();
    server.awaitTermination();
}
```

### GrpcHandler

Implement `GrpcHandler` to handle gRPC lifecycle events:

```java
public class MyGrpcHandler implements GrpcHandler {
    @Override
    public void onOpen(GrpcChannel channel) {
        log.info("gRPC client connected: {}", channel.uuid());
    }

    @Override
    public void onMessage(GrpcChannel channel, String message) {
        log.info("Received: {}", message);
    }

    @Override
    public void onBinaryMessage(GrpcChannel channel, byte[] data) {
        log.info("Binary message: {} bytes", data.length);
    }

    @Override
    public void onClose(GrpcChannel channel) {
        log.info("Client disconnected: {}", channel.uuid());
    }

    @Override
    public void onError(GrpcChannel channel, Throwable t) {
        log.error("Error on {}: {}", channel.uuid(), t.getMessage());
    }
}
```

Or extend `GrpcHandlerAdapter` for a no-op base.

### Builder Options

```java
AtmosphereGrpcServer.builder()
    .framework(framework)       // required
    .port(9090)                 // default: 9090
    .handler(handler)           // default: no-op adapter
    .enableReflection(true)     // default: true
    .interceptor(myInterceptor) // optional gRPC ServerInterceptor
    .build();
```

## Spring Boot Integration

When `atmosphere-grpc` is on the classpath, the Spring Boot starter can launch a gRPC server automatically:

```yaml
atmosphere:
  grpc:
    enabled: true
    port: 9090
    enable-reflection: true
```

Define a `GrpcHandler` bean:

```java
@Bean
public GrpcHandler grpcHandler() {
    return new GrpcHandlerAdapter() {
        @Override
        public void onOpen(GrpcChannel channel) {
            log.info("gRPC client connected: {}", channel.uuid());
        }
    };
}
```

## Message Types (Protobuf)

| Type | Description |
|------|-------------|
| `SUBSCRIBE` | Subscribe to a topic/broadcaster |
| `UNSUBSCRIBE` | Unsubscribe from a topic |
| `MESSAGE` | Text or binary payload |
| `HEARTBEAT` | Keepalive ping |
| `ACK` | Server acknowledgment |

## Testing with grpcurl

```bash
grpcurl -plaintext -d '{"type":"SUBSCRIBE","topic":"/chat"}' \
  localhost:9090 atmosphere.AtmosphereService/Stream
```

## GrpcChannel API

```java
channel.uuid()                          // channel identifier
channel.write("Hello")                  // send text
channel.write(bytes)                    // send binary
channel.write("/topic", "message")      // send to specific topic
channel.isOpen()                        // check if open
channel.close()                         // close the channel
channel.resource()                      // get AtmosphereResource
```

## Java Client (wAsync)

Connect from a Java client using gRPC transport:

```java
var client = AtmosphereClient.newClient();

var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
        .uri("grpc://localhost:9090/chat")
        .transport(Request.TRANSPORT.GRPC)
        .build();

var socket = client.create()
        .on(Event.MESSAGE, m -> System.out.println("Received: " + m))
        .open(request);

socket.fire("Hello via gRPC!");
```

Requires `grpc-netty-shaded`, `grpc-protobuf`, and `grpc-stub` on the classpath.

## Samples

- [gRPC Chat](../samples/grpc-chat/) -- standalone gRPC server example

## See Also

- [Core Runtime](core.md)
- [Spring Boot Integration](spring-boot.md) -- auto-configured gRPC in Spring Boot
- [wAsync Java Client](client-java.md) -- gRPC transport support
