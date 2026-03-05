---
title: "Chapter 18: Observability"
description: "Micrometer metrics, OpenTelemetry tracing, and health checks for Atmosphere applications with Spring Boot Actuator integration."
sidebar:
  order: 18
---

A real-time framework that you cannot observe is a real-time framework you cannot trust. Atmosphere provides three observability pillars: **Micrometer metrics** for gauges, counters, and timers; **OpenTelemetry tracing** for distributed request tracing; and **health checks** for liveness and readiness probes.

## AtmosphereMetrics -- Micrometer Integration

The `AtmosphereMetrics` class in `org.atmosphere.metrics` registers gauges, counters, and timers on any Micrometer `MeterRegistry`. It requires `io.micrometer:micrometer-core` on the classpath (an optional dependency of `atmosphere-runtime`).

### Installation

A single call wires everything up:

```java
AtmosphereMetrics.install(framework, meterRegistry);
```

This registers a `BroadcasterListener` and a `FrameworkListener` on the framework. The method returns the `AtmosphereMetrics` instance for optional further instrumentation.

### Metrics Published

| Metric | Type | Description |
|--------|------|-------------|
| `atmosphere.connections.active` | Gauge | Currently active connections |
| `atmosphere.connections.total` | Counter | Total connections opened |
| `atmosphere.connections.disconnects` | Counter | Total disconnects |
| `atmosphere.broadcasters.active` | Gauge | Currently active broadcasters |
| `atmosphere.messages.broadcast` | Counter | Total messages broadcast |
| `atmosphere.messages.delivered` | Counter | Total messages delivered to individual resources |
| `atmosphere.broadcast.timer` | Timer | Broadcast completion latency |

### Room Metrics

Call `instrumentRoom(room)` or `instrumentRoomManager(roomManager)` for room-level observability:

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `atmosphere.rooms.active` | Gauge | -- | Total active rooms |
| `atmosphere.rooms.members` | Gauge | `room` | Current members in a specific room |
| `atmosphere.rooms.messages` | Counter | `room` | Messages broadcast in a specific room |

### Cache Metrics

Call `instrumentCache(uuidBroadcasterCache)` to monitor the `UUIDBroadcasterCache`:

| Metric | Type | Description |
|--------|------|-------------|
| `atmosphere.cache.size` | Gauge | Total cached messages |
| `atmosphere.cache.evictions` | FunctionCounter | Cache evictions |
| `atmosphere.cache.hits` | FunctionCounter | Cache retrieval hits |
| `atmosphere.cache.misses` | FunctionCounter | Cache retrieval misses |

### Backpressure Metrics

Call `instrumentBackpressure(interceptor)` on a `BackpressureInterceptor`:

| Metric | Type | Description |
|--------|------|-------------|
| `atmosphere.backpressure.drops` | FunctionCounter | Messages dropped by backpressure |
| `atmosphere.backpressure.disconnects` | FunctionCounter | Clients disconnected by backpressure |

## Spring Boot Actuator Integration

### ObservabilityConfig.java

The `samples/spring-boot-chat/` sample demonstrates wiring metrics into a Spring Boot application:

```java
@Configuration
public class ObservabilityConfig {

    private static final Logger logger = LoggerFactory.getLogger(ObservabilityConfig.class);

    private final AtmosphereFramework framework;
    private final MeterRegistry meterRegistry;

    public ObservabilityConfig(AtmosphereFramework framework, MeterRegistry meterRegistry) {
        this.framework = framework;
        this.meterRegistry = meterRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void installMetrics() {
        AtmosphereMetrics.install(framework, meterRegistry);
        logger.info("Atmosphere Micrometer metrics installed — see /actuator/metrics/atmosphere.*");
    }
}
```

The `@EventListener(ApplicationReadyEvent.class)` ensures the framework is fully initialized before metrics are installed.

### Viewing Metrics

With Spring Boot Actuator on the classpath, metrics are available at:

```
GET /actuator/metrics/atmosphere.connections.active
GET /actuator/metrics/atmosphere.messages.broadcast
GET /actuator/metrics/atmosphere.broadcast.timer
```

List all Atmosphere metrics:

```bash
curl http://localhost:8080/actuator/metrics | jq '.names[] | select(startswith("atmosphere"))'
```

## AtmosphereTracing -- OpenTelemetry Integration

The `AtmosphereTracing` class in `org.atmosphere.metrics` is an `AtmosphereInterceptorAdapter` that creates trace spans for each incoming request. It requires `io.opentelemetry:opentelemetry-api` on the classpath.

### Span Lifecycle

The interceptor creates a `SERVER` span on `inspect()` with the following attributes:

| Attribute | Description |
|-----------|-------------|
| `atmosphere.resource.uuid` | The resource UUID |
| `atmosphere.transport` | Transport type (WEBSOCKET, SSE, LONG_POLLING, etc.) |
| `atmosphere.action` | Action result (CONTINUE, SUSPEND) |
| `atmosphere.broadcaster` | The broadcaster ID |
| `atmosphere.disconnect.reason` | Reason for disconnect (client, timeout, application) |
| `atmosphere.room` | Room name (for room operations) |

For non-suspended requests, the span ends immediately after `postInspect()`. For suspended (long-lived) connections, a `TracingResourceListener` tracks lifecycle events as span events:

- `atmosphere.suspend` -- resource suspended
- `atmosphere.resume` -- resource resumed
- `atmosphere.broadcast` -- message delivered
- `atmosphere.disconnect` -- resource disconnected
- `atmosphere.close` -- connection closed

Errors are recorded with `span.recordException()` and the span status is set to `ERROR`.

### Room-Level Tracing

The `startRoomSpan(operation, roomName, uuid)` method creates internal spans for room operations (join, leave, broadcast). The caller is responsible for ending the span.

### Installation

Register the interceptor with an `OpenTelemetry` instance or a `Tracer`:

```java
OpenTelemetry otel = GlobalOpenTelemetry.get();
framework.interceptor(new AtmosphereTracing(otel));
```

Or with a custom tracer:

```java
Tracer tracer = otel.getTracer("my-app", "1.0.0");
framework.interceptor(new AtmosphereTracing(tracer));
```

## Spring Boot OTel Chat Sample

The `samples/spring-boot-otel-chat/` sample demonstrates OpenTelemetry integration with Jaeger.

### OtelConfig.java

```java
@Configuration
public class OtelConfig {

    private static final Logger logger = LoggerFactory.getLogger(OtelConfig.class);

    @Bean
    public OpenTelemetry openTelemetry() {
        var otel = AutoConfiguredOpenTelemetrySdk.initialize().getOpenTelemetrySdk();
        logger.info("OpenTelemetry SDK initialized — AtmosphereTracing will auto-register");
        return otel;
    }
}
```

The SDK auto-configures from environment variables:

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OTLP collector endpoint |
| `OTEL_SERVICE_NAME` | `atmosphere-otel-chat` | Service name shown in Jaeger |

### Chat.java

The `@ManagedService` class has no tracing-specific code. The `AtmosphereTracing` interceptor is auto-configured:

```java
@ManagedService(path = "/atmosphere/chat", atmosphereConfig = MAX_INACTIVE + "=120000")
public class Chat {

    private static final Logger logger = LoggerFactory.getLogger(Chat.class);

    @Inject
    private AtmosphereResource r;

    @Inject
    private AtmosphereResourceEvent event;

    @Ready
    public void onReady() {
        logger.info("Browser {} connected — trace span started", r.uuid());
    }

    @Disconnect
    public void onDisconnect() {
        if (event.isCancelled()) {
            logger.info("Browser {} unexpectedly disconnected", event.getResource().uuid());
        } else if (event.isClosedByClient()) {
            logger.info("Browser {} closed the connection", event.getResource().uuid());
        }
    }

    @Message
    public String onMessage(String message) {
        logger.info("Received message — tracing active: {}", message);
        return message;
    }
}
```

### Running with Jaeger

Start Jaeger and the application:

```bash
# Start Jaeger all-in-one
docker run -d --name jaeger \
  -p 16686:16686 \
  -p 4317:4317 \
  jaegertracing/all-in-one:latest

# Run the sample
OTEL_SERVICE_NAME=atmosphere-chat \
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 \
./mvnw spring-boot:run -pl samples/spring-boot-otel-chat
```

Open Jaeger at `http://localhost:16686` and select the `atmosphere-chat` service to see spans for connect, message, and disconnect events.

## AtmosphereHealth -- Health Checks

The `AtmosphereHealth` class provides a framework-level health check:

```java
AtmosphereHealth health = new AtmosphereHealth(framework);
Map<String, Object> status = health.check();
```

The returned map contains:

| Key | Type | Description |
|-----|------|-------------|
| `status` | String | `UP` or `DOWN` |
| `version` | String | Atmosphere version |
| `connections` | int | Total active connections across all broadcasters |
| `broadcasters` | int | Number of active broadcasters |
| `handlers` | int | Number of registered AtmosphereHandlers |
| `interceptors` | int | Number of registered interceptors |

The `isHealthy()` method returns `true` if the framework has not been destroyed.

### Spring Boot Actuator Health

The `atmosphere-spring-boot-starter` includes an `AtmosphereHealthIndicator` that exposes Atmosphere health at `/actuator/health`:

```json
{
  "status": "UP",
  "components": {
    "atmosphere": {
      "status": "UP",
      "details": {
        "version": "LATEST",
        "connections": 42,
        "broadcasters": 3,
        "handlers": 2,
        "interceptors": 5
      }
    }
  }
}
```

## Structured Logging -- MDCInterceptor

`MDCInterceptor` populates SLF4J MDC keys on every request so log lines include Atmosphere context:

```java
framework.interceptor(new MDCInterceptor());
```

### MDC Keys

| Key | Value |
|-----|-------|
| `atmosphere.uuid` | Unique resource identifier |
| `atmosphere.transport` | Transport type |
| `atmosphere.broadcaster` | Broadcaster ID |

### Logback Pattern Example

```
%d{HH:mm:ss.SSS} [%thread] %-5level [uuid=%X{atmosphere.uuid} transport=%X{atmosphere.transport}] %logger{36} - %msg%n
```

MDC keys are automatically included as top-level fields in JSON layouts (logstash-logback-encoder, logback-contrib).

## BackpressureInterceptor

The `BackpressureInterceptor` protects against slow consumers by limiting the number of pending messages per client:

```java
framework.interceptor(new BackpressureInterceptor());
```

### Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.backpressure.highWaterMark` | `1000` | Max pending messages per client |
| `org.atmosphere.backpressure.policy` | `drop-oldest` | `drop-oldest`, `drop-newest`, or `disconnect` |

### Policies

| Policy | Behavior |
|--------|----------|
| `drop-oldest` | Drops the oldest pending message to make room (default) |
| `drop-newest` | Drops the incoming message |
| `disconnect` | Disconnects the slow client |

### Monitoring

```java
BackpressureInterceptor bp = new BackpressureInterceptor();
bp.pendingCount(uuid);     // Pending messages for a client
bp.totalDrops();           // Total messages dropped
bp.totalDisconnects();     // Total clients disconnected
```

Drop and disconnect counts are also exposed via Micrometer as `atmosphere.backpressure.drops` and `atmosphere.backpressure.disconnects`.

## Cache Configuration

The `UUIDBroadcasterCache` (installed by default with `@ManagedService`) supports tuning:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.cache.UUIDBroadcasterCache.maxPerClient` | `1000` | Max cached messages per client |
| `org.atmosphere.cache.UUIDBroadcasterCache.messageTTL` | `300` | Per-message TTL in seconds |
| `org.atmosphere.cache.UUIDBroadcasterCache.maxTotal` | `100000` | Global cache size limit |

## Grafana Dashboard

A ready-to-import Grafana dashboard is available at `samples/shared-resources/grafana/atmosphere-dashboard.json`. It includes panels for connections, message rates, broadcast latency (p50/p95/p99), room members, cache performance, and backpressure.

## Summary

- `AtmosphereMetrics.install(framework, meterRegistry)` registers Micrometer gauges, counters, and timers for connections, broadcasters, messages, rooms, cache, and backpressure
- `AtmosphereTracing` is an interceptor that creates OpenTelemetry spans covering the full request lifecycle with attributes for resource UUID, transport, action, broadcaster, and disconnect reason
- `MDCInterceptor` populates SLF4J MDC keys (`atmosphere.uuid`, `atmosphere.transport`, `atmosphere.broadcaster`) on every request for structured logging
- `BackpressureInterceptor` protects against slow consumers with configurable policies (drop-oldest, drop-newest, disconnect)
- `UUIDBroadcasterCache` supports tuning via `maxPerClient`, `messageTTL`, and `maxTotal` parameters
- `AtmosphereHealth` provides a health check snapshot with connection counts, broadcaster counts, and framework status
- Spring Boot Actuator integration exposes metrics at `/actuator/metrics/atmosphere.*` and health at `/actuator/health`
- The OTel chat sample shows end-to-end tracing with Jaeger
- A Grafana dashboard is available for out-of-the-box monitoring

Next up: [Chapter 19: atmosphere.js Client](/docs/tutorial/19-client/) covers the TypeScript client library.
