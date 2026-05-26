# Atmosphere Quarkus gRPC

Quarkus extension that wires Atmosphere's
[gRPC transport](../grpc/) onto Quarkus's startup / shutdown lifecycle.
The on-wire proto contract is the [AtmosphereService](../grpc/src/main/proto/atmosphere.proto)
shared with the Spring Boot starter, so a `wasync` gRPC client connects
identically against either backend.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-quarkus-grpc</artifactId>
    <version>${project.version}</version>
</dependency>
```

The deployment artifact (`atmosphere-quarkus-grpc-deployment`) is resolved
automatically by Quarkus.

## Why a separate extension

`atmosphere-quarkus-extension` wires the servlet container; this extension
adds a **standalone Netty gRPC server** managed by Quarkus lifecycle
events. The two run side-by-side on different ports — WebSocket / SSE /
Long-Polling on the Undertow HTTP port, gRPC on its own dedicated port.
Same setup as `AtmosphereGrpcAutoConfiguration` in the Spring Boot
starter, just driven by `@Observes StartupEvent` / `ShutdownEvent`
instead of `SmartLifecycle`.

The standalone-server approach was chosen over wiring into `quarkus-grpc`
(which uses Vert.x gRPC) for two reasons:

1. **Mode parity (Correctness Invariant #7).** Spring Boot's
   `AtmosphereGrpcServer` IS the Netty-backed `io.grpc.Server`. Using
   the same implementation under Quarkus guarantees byte-identical wire
   behavior; a Vert.x gRPC rewrite would require a separate equivalence
   matrix to prove that.
2. **Single dep swap.** The AgentRuntime SPI promise is "swap one Maven
   dep, the same sample works." Here the dep is
   `atmosphere-quarkus-grpc`; what changes vs. Spring Boot is lifecycle
   wiring (`@Observes` vs. `SmartLifecycle`), not the wire stack.

A future iteration may add a Vert.x gRPC adapter for apps that want the
gRPC service multiplexed onto the main HTTP port — that's not in scope
here.

## Configuration

All keys under the `quarkus.atmosphere.grpc.*` prefix:

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.atmosphere.grpc.enabled` | `false` | Start the gRPC server. Default false — dropping the dep on the classpath does not silently open a port. |
| `quarkus.atmosphere.grpc.port` | `9090` | Standalone Netty gRPC port. `0` requests an ephemeral port. |
| `quarkus.atmosphere.grpc.enable-reflection` | `true` | Expose the standard gRPC `ServerReflection` service so `grpcurl` can discover endpoints without a `.proto`. |

The Spring Boot starter ships the same toggles under `atmosphere.grpc.*`.

## Quickstart

`application.properties`:

```properties
quarkus.atmosphere.packages=com.example.chat
quarkus.atmosphere.grpc.enabled=true
quarkus.atmosphere.grpc.port=9090
```

Run:

```bash
mvn quarkus:dev
```

Startup log includes:

```
Atmosphere gRPC server listening on port 9090 (reflection=true)
Installed features: [atmosphere, atmosphere-grpc, cdi, servlet, ...]
```

Subscribe to a Broadcaster topic over gRPC:

```bash
grpcurl -plaintext \
    -d '{"type":"SUBSCRIBE","topic":"/chat"}' \
    localhost:9090 \
    org.atmosphere.grpc.AtmosphereService/Subscribe
```

Send a message:

```bash
grpcurl -plaintext \
    -d '{"type":"MESSAGE","topic":"/chat","payload":"hello"}' \
    localhost:9090 \
    org.atmosphere.grpc.AtmosphereService/Send
```

WebSocket and gRPC clients sharing the same Broadcaster topic all
receive each other's messages — Atmosphere's transport is
indifferent to which mechanism a subscriber connected through.

## Wire compatibility

The proto definition lives in `modules/grpc/src/main/proto/atmosphere.proto`.
Both `atmosphere-spring-boot-starter` and `atmosphere-quarkus-grpc`
depend on `atmosphere-grpc` (the runtime transport), so they share the
same generated `AtmosphereServiceGrpc` stubs. Any gRPC client compiled
against the same proto runs against either container — only the host
process changes.

## How it integrates

```
Quarkus app boot
   │
   ▼
QuarkusAtmosphereServlet.init()          ◄── atmosphere-quarkus-extension
   │   (creates AtmosphereFramework, signals LazyAtmosphereConfigurator)
   │
   ▼
@Observes StartupEvent                   ◄── atmosphere-quarkus-grpc
AtmosphereQuarkusGrpcLifecycle.onStart()
   │   - LazyAtmosphereConfigurator.getFramework()
   │   - AtmosphereGrpcServer.builder()
   │       .framework(...).port(...).build().start()
   ▼
io.grpc.Server (Netty) on port 9090

  ... runtime traffic ...

@Observes ShutdownEvent
AtmosphereQuarkusGrpcLifecycle.onStop()
   │   - server.stop() — awaits 5s for in-flight RPCs
```

The framework reference is obtained from the core extension's
`LazyAtmosphereConfigurator` (the same path the WebSocket configurator
uses), so this extension's startup is correctly ordered AFTER the
servlet init — no extra build-step coordination needed.

## Native image

The deployment processor declares the
`AtmosphereGrpcServer`, `AtmosphereGrpcService`, `GrpcProcessor`,
`AtmosphereMessage`, `AtmosphereServiceGrpc`, and `MessageType` classes
reflection-reachable, and marks `AtmosphereGrpcServer` as
runtime-initialized so the Netty epoll/kqueue native handles are
created in the running image (not baked into the heap).

```bash
mvn package -Pnative
```

If a custom `ServerInterceptor` or interceptor-loaded class needs
additional reflection, add it via
`quarkus.native.additional-build-args=--initialize-at-run-time=...`
in `application.properties`.

## Sample

See [`samples/quarkus-ai-chat`](../../samples/quarkus-ai-chat/) — the
sample enables this extension via `quarkus.atmosphere.grpc.enabled=true`
on port `19090` and the README walks through a `grpcurl` round-trip
against the same Broadcaster the AI chat WebSocket uses.

## Limits

- gRPC runs on its OWN port (parallel server). Multiplexing the gRPC
  service onto the main Vert.x HTTP port would require a separate
  Vert.x gRPC adapter — that is not in scope for this extension.
- Only the three RPCs the proto defines (`Stream`, `Subscribe`, `Send`)
  are exposed; the extension does not add Atmosphere-specific
  interceptors over and above what `AtmosphereGrpcServer.builder()`
  already supports. Custom `ServerInterceptor` beans can be added by
  the application via standard gRPC builder configuration if the
  consumer needs to extend the chain (post-handler hook would need an
  upstream API change in `AtmosphereGrpcServer.Builder`).
