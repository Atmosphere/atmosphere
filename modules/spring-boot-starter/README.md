# Atmosphere Spring Boot Starter

Auto-configuration for running Atmosphere on Spring Boot 4.0+. Registers `AtmosphereServlet`, wires Spring DI into Atmosphere's object factory, and exposes `AtmosphereFramework` and `RoomManager` as Spring beans.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-boot-starter</artifactId>
    <version>4.0.2</version>
</dependency>
```

## Minimal Example

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

No additional configuration is needed beyond a standard `@SpringBootApplication` class. The starter auto-registers the servlet, scans for Atmosphere annotations, and integrates with Spring's `ApplicationContext`.

## Configuration Properties

All properties are under the `atmosphere.*` prefix:

| Property | Default | Description |
|----------|---------|-------------|
| `atmosphere.packages` | (none) | Comma-separated packages to scan for Atmosphere annotations |
| `atmosphere.servlet-path` | `/atmosphere/*` | Servlet URL mapping |
| `atmosphere.session-support` | `false` | Enable HTTP session support |
| `atmosphere.websocket-support` | (auto) | Explicitly enable/disable WebSocket |
| `atmosphere.broadcaster-class` | (default) | Custom `Broadcaster` implementation |
| `atmosphere.heartbeat-interval-in-seconds` | (default) | Heartbeat interval |

## Auto-Configured Beans

- `AtmosphereServlet` -- the servlet instance
- `AtmosphereFramework` -- the framework for programmatic configuration
- `RoomManager` -- the room API for presence and message history
- `AtmosphereHealthIndicator` -- Actuator health check (when `spring-boot-health` is on the classpath)

## Observability

### OpenTelemetry Tracing (Auto-Configured)

Add `opentelemetry-api` to your classpath and provide an `OpenTelemetry` bean — the starter automatically registers `AtmosphereTracing`:

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

Every Atmosphere request generates a trace span with transport, resource UUID, broadcaster, and action attributes. Disable with `atmosphere.tracing.enabled=false`.

When `atmosphere-mcp` is also on the classpath, an `McpTracing` bean is auto-created for MCP tool/resource/prompt call tracing.

### Micrometer Metrics (Auto-Configured)

When `micrometer-core` and `MeterRegistry` are on the classpath, `AtmosphereMetricsAutoConfiguration` registers `atmosphere.connections`, `atmosphere.messages`, and `atmosphere.broadcasters` gauges.

### Sample

See [Spring Boot OTel Chat](../../samples/spring-boot-otel-chat/) for a complete example with Jaeger.

## GraalVM Native Image

The starter includes `AtmosphereRuntimeHints` for native image support. Build with `mvn clean package -Pnative`.

## Sample

- [Spring Boot Chat](../../samples/spring-boot-chat/) -- rooms, presence, REST API, Micrometer metrics, Actuator health

## Requirements

- Java 21+
- Spring Boot 4.0+
