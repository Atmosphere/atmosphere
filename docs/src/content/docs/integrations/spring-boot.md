---
title: "Spring Boot"
description: "Auto-configuration for Spring Boot 4.0+"
---

# Spring Boot Integration

Auto-configuration for running Atmosphere on Spring Boot 4.0+. Registers `AtmosphereServlet`, wires Spring DI into Atmosphere's object factory, and exposes `AtmosphereFramework` and `RoomManager` as Spring beans.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-boot-starter</artifactId>
    <version>4.0.8-SNAPSHOT</version>
</dependency>
```

## Quick Start

### application.yml

```yaml
atmosphere:
  packages: com.example.chat
```

### Chat.java

```java
@ManagedService(path = "/atmosphere/chat")
public class Chat {

    @Inject
    private BroadcasterFactory factory;

    @Inject
    private AtmosphereResource r;

    @Ready
    public void onReady() { }

    @Disconnect
    public void onDisconnect() { }

    @Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) {
        return message;
    }
}
```

No additional configuration is needed beyond a standard `@SpringBootApplication` class.

## Configuration Properties

All properties are under the `atmosphere.*` prefix:

| Property | Default | Description |
|----------|---------|-------------|
| `atmosphere.packages` | (none) | Comma-separated packages to scan for Atmosphere annotations |
| `atmosphere.servlet-path` | `/atmosphere/*` | Servlet URL mapping |
| `atmosphere.session-support` | `false` | Enable HTTP session support |
| `atmosphere.websocket-support` | (auto) | Explicitly enable/disable WebSocket |
| `atmosphere.broadcaster-class` | (default) | Custom `Broadcaster` implementation FQCN |
| `atmosphere.broadcaster-cache-class` | (default) | Custom `BroadcasterCache` implementation FQCN |
| `atmosphere.heartbeat-interval-in-seconds` | (default) | Server heartbeat frequency |
| `atmosphere.order` | `0` | Servlet load-on-startup order |
| `atmosphere.init-params` | (none) | Map of any `ApplicationConfig` key/value |

## Auto-Configured Beans

- `AtmosphereServlet` -- the servlet instance
- `AtmosphereFramework` -- the framework for programmatic configuration
- `RoomManager` -- the room API for presence and message history
- `AtmosphereHealthIndicator` -- Actuator health check (when `spring-boot-health` is on the classpath)

## gRPC Transport

The starter can launch a gRPC server alongside the servlet container when `atmosphere-grpc` is on the classpath:

```yaml
atmosphere:
  grpc:
    enabled: true
    port: 9090
    enable-reflection: true
```

| Property | Default | Description |
|----------|---------|-------------|
| `atmosphere.grpc.enabled` | `false` | Enable gRPC transport server |
| `atmosphere.grpc.port` | `9090` | gRPC server port |
| `atmosphere.grpc.enable-reflection` | `true` | Enable gRPC server reflection |

Define a `GrpcHandler` bean to handle gRPC events:

```java
@Bean
public GrpcHandler grpcHandler() {
    return new GrpcHandlerAdapter() {
        @Override
        public void onOpen(GrpcChannel channel) {
            log.info("gRPC client connected: {}", channel.uuid());
        }
        @Override
        public void onMessage(GrpcChannel channel, String message) {
            log.info("gRPC message: {}", message);
        }
    };
}
```

## Observability

### OpenTelemetry Tracing (Auto-Configured)

Add `opentelemetry-api` to your classpath and provide an `OpenTelemetry` bean -- the starter automatically registers `AtmosphereTracing`:

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
</dependency>
```

Every Atmosphere request generates a trace span with transport, resource UUID, broadcaster, and action attributes. Disable with `atmosphere.tracing.enabled=false`.

When `atmosphere-mcp` is also on the classpath, an `McpTracing` bean is auto-created for MCP tool/resource/prompt call tracing.

### Micrometer Metrics (Auto-Configured)

When `micrometer-core` and `MeterRegistry` are on the classpath, the starter registers `atmosphere.connections`, `atmosphere.messages`, and `atmosphere.broadcasters` gauges.

## GraalVM Native Image

The starter includes `AtmosphereRuntimeHints` for native image support:

```bash
./mvnw -Pnative package -pl samples/spring-boot-chat
./samples/spring-boot-chat/target/atmosphere-spring-boot-chat
```

Requires GraalVM JDK 25+ (Spring Boot 4.0 / Spring Framework 7 baseline).

## Samples

- [Spring Boot Chat](../samples/spring-boot-chat/) -- rooms, presence, REST API, Micrometer metrics, Actuator health
- [Spring Boot AI Chat](../samples/spring-boot-ai-chat/) -- built-in AI client
- [Spring Boot Spring AI Chat](../samples/spring-boot-spring-ai-chat/) -- Spring AI adapter
- [Spring Boot MCP Server](../samples/spring-boot-mcp-server/) -- MCP tools, resources, prompts
- [Spring Boot OTel Chat](../samples/spring-boot-otel-chat/) -- OpenTelemetry tracing with Jaeger

## See Also

- [Core Runtime](core.md)
- [AI Integration](ai.md)
- [gRPC Transport](grpc.md)
- [Observability](observability.md)
- [Module README](../modules/spring-boot-starter/README.md)
