# Atmosphere gRPC Transport

Bidirectional streaming transport for Atmosphere using grpc-java. Clients can subscribe, publish, and receive messages over gRPC alongside WebSocket, SSE, and long-polling clients on the same Broadcaster.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-grpc</artifactId>
    <version>4.0.22</version>
</dependency>
```

## Quick Start

### Standalone gRPC Server

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

### Spring Boot

```yaml
atmosphere:
  grpc:
    enabled: true
    port: 9090
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `AtmosphereGrpcServer` | Builder and lifecycle for embedded gRPC server |
| `GrpcHandler` | User-facing callback interface for gRPC events |
| `GrpcHandlerAdapter` | Default no-op implementation of `GrpcHandler` |
| `GrpcChannel` | Wraps gRPC stream + AtmosphereResource |

## Full Documentation

See [docs/grpc.md](../../docs/grpc.md) for complete documentation.

## Samples

- [gRPC Chat](../../samples/grpc-chat/)

## Requirements

- Java 21+
- `atmosphere-runtime` (transitive)
- grpc-java 1.71+
