# Observability

Atmosphere provides built-in support for Micrometer metrics, OpenTelemetry tracing, backpressure management, and cache configuration.

## Micrometer Metrics

```java
MeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
AtmosphereMetrics metrics = AtmosphereMetrics.install(framework, registry);
metrics.instrumentRoomManager(roomManager);
```

| Metric | Type | Description |
|--------|------|-------------|
| `atmosphere.connections.active` | Gauge | Active connections |
| `atmosphere.broadcasters.active` | Gauge | Active broadcasters |
| `atmosphere.connections.total` | Counter | Total connections opened |
| `atmosphere.messages.broadcast` | Counter | Messages broadcast |
| `atmosphere.broadcast.timer` | Timer | Broadcast latency |
| `atmosphere.rooms.active` | Gauge | Active rooms |
| `atmosphere.rooms.members` | Gauge | Members per room (tagged) |

With the Spring Boot starter, metrics are auto-configured when `micrometer-core` and `MeterRegistry` are on the classpath.

## OpenTelemetry Tracing

```java
framework.interceptor(new AtmosphereTracing(GlobalOpenTelemetry.get()));
```

Creates spans for every request with the following attributes:

| Attribute | Description |
|-----------|-------------|
| `atmosphere.resource.uuid` | Resource UUID |
| `atmosphere.transport` | Transport type (WEBSOCKET, SSE, LONG_POLLING) |
| `atmosphere.action` | Action result (CONTINUE, SUSPEND, RESUME) |
| `atmosphere.broadcaster` | Broadcaster ID |
| `atmosphere.room` | Room name (if applicable) |

With the Spring Boot starter, tracing is auto-configured when an `OpenTelemetry` bean is present. Disable with `atmosphere.tracing.enabled=false`.

### MCP Tracing

When `atmosphere-mcp` is on the classpath, `McpTracing` adds spans for tool, resource, and prompt invocations:

| Attribute | Description |
|-----------|-------------|
| `mcp.tool.name` | Tool/resource/prompt name |
| `mcp.tool.type` | `"tool"`, `"resource"`, or `"prompt"` |
| `mcp.tool.arg_count` | Number of arguments |
| `mcp.tool.error` | `true` if invocation failed |

## Backpressure

```java
framework.interceptor(new BackpressureInterceptor());
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.backpressure.highWaterMark` | `1000` | Max pending messages per client |
| `org.atmosphere.backpressure.policy` | `drop-oldest` | `drop-oldest`, `drop-newest`, or `disconnect` |

## Cache Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.cache.UUIDBroadcasterCache.maxPerClient` | `1000` | Max cached messages per client |
| `org.atmosphere.cache.UUIDBroadcasterCache.messageTTL` | `300` | Per-message TTL in seconds |
| `org.atmosphere.cache.UUIDBroadcasterCache.maxTotal` | `100000` | Global cache size limit |

## Samples

- [Spring Boot OTel Chat](../samples/spring-boot-otel-chat/) -- OpenTelemetry tracing with Jaeger
- [Spring Boot Chat](../samples/spring-boot-chat/) -- Micrometer metrics and Actuator health

## See Also

- [Core Runtime](core.md)
- [Spring Boot Integration](spring-boot.md) -- auto-configured observability
- [MCP Server](mcp.md) -- MCP tracing
