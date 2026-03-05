---
title: "Chapter 18: Observability"
description: "Micrometer metrics, OpenTelemetry tracing, backpressure, and cache monitoring for Atmosphere applications"
---

# Chapter 18: Observability

A real-time framework that you cannot observe is a real-time framework you cannot trust. In this chapter you will wire Atmosphere into the standard JVM observability stack -- Micrometer for metrics, OpenTelemetry for distributed tracing -- and learn how backpressure and cache configuration act as both safety valves and observability signals.

## What You Will Learn

- Installing `AtmosphereMetrics` and understanding every metric it exposes.
- Adding OpenTelemetry tracing to every Atmosphere request.
- Tracing MCP tool invocations with `McpTracing`.
- Configuring `BackpressureInterceptor` and using it as a health signal.
- Tuning `BroadcasterCache` and monitoring its behavior.
- Letting Spring Boot auto-configure all of the above.
- Running a full example with Jaeger.

## Prerequisites

You should be comfortable with the core Atmosphere API ([Chapter 3](/docs/tutorial/03-managed-service/)) and have a working Spring Boot Atmosphere project ([Chapter 6](/docs/tutorial/06-spring-boot/)).

---

## Micrometer Metrics

### Adding the Dependency

`AtmosphereMetrics` lives in `atmosphere-runtime` and integrates with any Micrometer `MeterRegistry`. You only need the Micrometer dependency itself:

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
</dependency>
```

If you are using the Spring Boot starter, `micrometer-core` is already on the classpath through the actuator starter.

### Installing Metrics

Call `AtmosphereMetrics.install()` after the framework is initialized, passing it the live `AtmosphereFramework` instance and a `MeterRegistry`:

```java
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.metrics.AtmosphereMetrics;

// Standalone setup
MeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
AtmosphereMetrics metrics = AtmosphereMetrics.install(framework, registry);
```

The `install()` method registers listeners on the framework's internal lifecycle hooks. From this point forward, every connection open, close, broadcast, and broadcaster creation is tracked automatically.

### Instrumenting the Room Manager

If your application uses rooms ([Chapter 4](/docs/tutorial/04-rooms/)), you can instrument the `RoomManager` separately:

```java
import org.atmosphere.room.RoomManager;

RoomManager roomManager = RoomManager.of(framework);
metrics.instrumentRoomManager(roomManager);
```

This adds per-room gauges so you can see exactly how many members are in each room at any given moment.

### Metric Reference

The table below lists every metric that `AtmosphereMetrics` registers:

| Metric Name | Type | Description |
|---|---|---|
| `atmosphere.connections.active` | Gauge | Number of currently open connections. Decrements when a client disconnects or times out. |
| `atmosphere.broadcasters.active` | Gauge | Number of active `Broadcaster` instances. Useful for detecting broadcaster leaks. |
| `atmosphere.connections.total` | Counter | Cumulative number of connections opened since the server started. Compare with `active` to understand churn. |
| `atmosphere.messages.broadcast` | Counter | Total number of messages broadcast across all broadcasters. High-velocity metric -- use rate functions in your dashboards. |
| `atmosphere.broadcast.timer` | Timer | Latency distribution of broadcast operations. Records the time from `broadcast()` call to delivery completion. Includes count, sum, max, and histogram buckets. |
| `atmosphere.rooms.active` | Gauge | Number of active rooms. Only populated after `instrumentRoomManager()`. |
| `atmosphere.rooms.members` | Gauge | Number of members in each room. Tagged with `room` so you can filter and alert per room. Only populated after `instrumentRoomManager()`. |

### Querying Metrics with Prometheus

If you expose the Prometheus scrape endpoint (the Spring Boot Actuator does this automatically at `/actuator/prometheus`), you can write PromQL queries such as:

```promql
# Connection churn rate (connections opened per second)
rate(atmosphere_connections_total[5m])

# Average broadcast latency over the last 5 minutes
rate(atmosphere_broadcast_timer_seconds_sum[5m])
  / rate(atmosphere_broadcast_timer_seconds_count[5m])

# Rooms with more than 100 members
atmosphere_rooms_members{} > 100
```

### Grafana Dashboard Sketch

A minimal Grafana dashboard for Atmosphere should include:

1. **Active Connections** -- single stat panel showing `atmosphere_connections_active`.
2. **Connection Rate** -- time series of `rate(atmosphere_connections_total[1m])`.
3. **Broadcast Throughput** -- time series of `rate(atmosphere_messages_broadcast[1m])`.
4. **Broadcast Latency (p99)** -- heatmap or time series of `histogram_quantile(0.99, rate(atmosphere_broadcast_timer_seconds_bucket[5m]))`.
5. **Room Membership** -- table panel grouped by `room` tag showing `atmosphere_rooms_members`.
6. **Active Broadcasters** -- single stat, with an alert if it exceeds an expected ceiling.

---

## OpenTelemetry Tracing

### Adding the Dependency

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

### Registering the Tracing Interceptor

`AtmosphereTracing` is an `AtmosphereInterceptor` that wraps every Atmosphere request in an OpenTelemetry span:

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import org.atmosphere.tracing.AtmosphereTracing;

framework.interceptor(new AtmosphereTracing(GlobalOpenTelemetry.get()));
```

You can also pass a specific `OpenTelemetry` instance if you are not using the global singleton:

```java
OpenTelemetry otel = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build();

framework.interceptor(new AtmosphereTracing(otel));
```

### Span Attributes

Every span created by `AtmosphereTracing` carries the following attributes:

| Attribute | Type | Description | Example |
|---|---|---|---|
| `atmosphere.resource.uuid` | string | The unique identifier of the `AtmosphereResource` | `a1b2c3d4-...` |
| `atmosphere.transport` | string | The transport protocol negotiated by the client | `WEBSOCKET`, `SSE`, `LONG_POLLING` |
| `atmosphere.action` | string | The action result after interceptor processing | `CONTINUE`, `SUSPEND`, `RESUME`, `CANCELLED` |
| `atmosphere.broadcaster` | string | The broadcaster path the resource is subscribed to | `/chat` |
| `atmosphere.room` | string | The room name, if the request is associated with a room | `lobby` |

### What the Trace Looks Like

A typical WebSocket lifecycle produces the following span tree in Jaeger or Zipkin:

```
[atmosphere SUBSCRIBE]  duration: 2ms
  atmosphere.transport = WEBSOCKET
  atmosphere.action    = SUSPEND
  atmosphere.broadcaster = /chat

    [atmosphere MESSAGE]  duration: 5ms
      atmosphere.transport = WEBSOCKET
      atmosphere.action    = CONTINUE
      atmosphere.broadcaster = /chat
      atmosphere.room      = lobby

    [atmosphere MESSAGE]  duration: 3ms
      ...

[atmosphere DISCONNECT]  duration: 1ms
  atmosphere.transport = WEBSOCKET
  atmosphere.action    = CANCELLED
```

Each message event is a child span of the connection span, giving you end-to-end latency visibility.

---

## MCP Tracing

When you are using the `atmosphere-mcp` module to expose your application as an MCP server ([Chapter 13](/docs/tutorial/13-mcp/)), `McpTracing` adds specialized spans for every tool, resource, and prompt invocation.

### Registering McpTracing

```java
import org.atmosphere.mcp.McpTracing;

// McpTracing is automatically registered when atmosphere-mcp and
// an OpenTelemetry instance are both on the classpath.
// Manual registration:
framework.interceptor(new McpTracing(otel));
```

### MCP Span Attributes

| Attribute | Type | Description | Example |
|---|---|---|---|
| `mcp.tool.name` | string | The name of the tool, resource, or prompt | `search_rooms`, `user://profile` |
| `mcp.tool.type` | string | The category of the MCP invocation | `tool`, `resource`, `prompt` |
| `mcp.tool.arg_count` | int | Number of arguments passed to the invocation | `3` |
| `mcp.tool.error` | boolean | `true` if the invocation threw an exception | `true` |

### Example Trace with MCP

```
[mcp tool: search_rooms]  duration: 45ms
  mcp.tool.name      = search_rooms
  mcp.tool.type      = tool
  mcp.tool.arg_count = 2
  mcp.tool.error     = false

    [atmosphere MESSAGE]  duration: 3ms
      atmosphere.broadcaster = /rooms/math
      atmosphere.room        = math
```

This gives you full visibility into how AI agents interact with your Atmosphere server -- which tools they call, how often, and how long each call takes.

---

## Backpressure as an Observability Signal

The `BackpressureInterceptor` is primarily a safety mechanism, but it also serves as a powerful observability signal. When clients cannot consume messages fast enough, backpressure fires -- and you want to know about it.

### Registering the Interceptor

```java
import org.atmosphere.interceptor.BackpressureInterceptor;

framework.interceptor(new BackpressureInterceptor());
```

### Configuration Parameters

Configure via `AtmosphereFramework` init parameters or `application.properties`:

| Parameter | Default | Description |
|---|---|---|
| `org.atmosphere.backpressure.highWaterMark` | `1000` | Maximum number of pending (undelivered) messages per client. When this threshold is breached, the configured policy kicks in. |
| `org.atmosphere.backpressure.policy` | `drop-oldest` | What to do when the high water mark is exceeded. |

### Backpressure Policies

| Policy | Behavior | When to Use |
|---|---|---|
| `drop-oldest` | Discards the oldest pending message to make room for the new one. The client misses stale data but stays connected. | Default choice for most real-time applications where freshness matters more than completeness. |
| `drop-newest` | Discards the incoming message. The client keeps its existing queue intact. | Use when historical ordering is critical and you prefer to skip new data rather than lose old data. |
| `disconnect` | Forcefully disconnects the slow client. The client must reconnect. | Use for zero-tolerance scenarios where a slow consumer threatens server stability. |

### Standalone Configuration

```java
framework.addInitParameter(
    "org.atmosphere.backpressure.highWaterMark", "500");
framework.addInitParameter(
    "org.atmosphere.backpressure.policy", "disconnect");
```

### Spring Boot Configuration

```yaml
atmosphere:
  init-params:
    org.atmosphere.backpressure.highWaterMark: "500"
    org.atmosphere.backpressure.policy: "disconnect"
```

### Monitoring Backpressure

When backpressure fires, the interceptor logs at WARN level. You should set up a log-based alert for these messages. Combined with the `atmosphere.connections.active` gauge and `atmosphere.messages.broadcast` counter, you can correlate spikes in broadcast volume with backpressure events.

A healthy system should rarely trigger backpressure. If you see it frequently, consider:

1. Reducing broadcast frequency (batch messages on the server side).
2. Increasing the `highWaterMark` if clients are momentarily slow but recover.
3. Switching to `disconnect` policy if slow clients are genuinely unhealthy.

---

## BroadcasterCache Monitoring and Configuration

The `UUIDBroadcasterCache` stores messages for clients that temporarily disconnect (e.g., during a transport fallback or network blip). Proper tuning prevents memory exhaustion while ensuring reconnecting clients receive missed messages.

### Configuration Parameters

| Parameter | Default | Description |
|---|---|---|
| `org.atmosphere.cache.UUIDBroadcasterCache.maxPerClient` | `1000` | Maximum cached messages per client UUID. Oldest messages are evicted when this limit is reached. |
| `org.atmosphere.cache.UUIDBroadcasterCache.messageTTL` | `300` | Time-to-live for each cached message, in seconds. Messages older than this are evicted regardless of count. |
| `org.atmosphere.cache.UUIDBroadcasterCache.maxTotal` | `100000` | Global ceiling on the total number of cached messages across all clients. Prevents unbounded memory growth. |

### Standalone Configuration

```java
framework.addInitParameter(
    "org.atmosphere.cache.UUIDBroadcasterCache.maxPerClient", "500");
framework.addInitParameter(
    "org.atmosphere.cache.UUIDBroadcasterCache.messageTTL", "120");
framework.addInitParameter(
    "org.atmosphere.cache.UUIDBroadcasterCache.maxTotal", "50000");
```

### Spring Boot Configuration

```yaml
atmosphere:
  init-params:
    org.atmosphere.cache.UUIDBroadcasterCache.maxPerClient: "500"
    org.atmosphere.cache.UUIDBroadcasterCache.messageTTL: "120"
    org.atmosphere.cache.UUIDBroadcasterCache.maxTotal: "50000"
```

### Tuning Guidelines

- **Chat applications**: `maxPerClient=200`, `messageTTL=60`. Clients that miss more than a minute of chat history can request a backfill from your database instead.
- **Dashboard / ticker applications**: `maxPerClient=1`, `messageTTL=5`. Only the latest value matters. Old prices or metrics are useless.
- **AI streaming**: `maxPerClient=100`, `messageTTL=30`. Token streams are typically consumed in real time, but a brief reconnect should not lose the partial response.

---

## Spring Boot Auto-Configuration

When you use the `atmosphere-spring-boot-starter`, both metrics and tracing are auto-configured with zero boilerplate.

### Metrics Auto-Configuration

If a `MeterRegistry` bean is present (which it is whenever `spring-boot-starter-actuator` is on the classpath), the starter calls `AtmosphereMetrics.install(framework, registry)` automatically.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

That is all you need. Metrics appear at `/actuator/prometheus` (if the Prometheus registry is configured) or through any other Micrometer registry you have set up.

### Tracing Auto-Configuration

If an `OpenTelemetry` bean is present, the starter registers `AtmosphereTracing` as an interceptor. You can disable it with:

```yaml
atmosphere:
  tracing:
    enabled: false
```

The typical way to get an `OpenTelemetry` bean in Spring Boot is through the OpenTelemetry Spring Boot starter:

```xml
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-spring-boot-starter</artifactId>
</dependency>
```

### MCP Tracing Auto-Configuration

When both `atmosphere-mcp` and an `OpenTelemetry` bean are present, `McpTracing` is registered automatically alongside `AtmosphereTracing`. No additional configuration is needed.

---

## Putting It All Together: spring-boot-otel-chat

Let us build a complete sample that sends Atmosphere traces to Jaeger and metrics to Prometheus.

### Project Setup

```xml
<dependencies>
    <!-- Atmosphere -->
    <dependency>
        <groupId>org.atmosphere</groupId>
        <artifactId>atmosphere-spring-boot-starter</artifactId>
        <version>4.0.10</version>
    </dependency>

    <!-- Spring Boot -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <!-- Metrics: Prometheus -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>

    <!-- Tracing: OpenTelemetry -->
    <dependency>
        <groupId>io.opentelemetry.instrumentation</groupId>
        <artifactId>opentelemetry-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-exporter-otlp</artifactId>
    </dependency>
</dependencies>
```

### Application Configuration

```yaml
# application.yml
spring:
  application:
    name: atmosphere-otel-chat

# OpenTelemetry
otel:
  exporter:
    otlp:
      endpoint: http://localhost:4317
  resource:
    attributes:
      service.name: atmosphere-otel-chat

# Atmosphere
atmosphere:
  init-params:
    org.atmosphere.backpressure.highWaterMark: "500"
    org.atmosphere.backpressure.policy: "drop-oldest"
    org.atmosphere.cache.UUIDBroadcasterCache.maxPerClient: "200"
    org.atmosphere.cache.UUIDBroadcasterCache.messageTTL: "60"

# Actuator
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
```

### The Chat Handler

```java
import org.atmosphere.config.managed.ManagedAtmosphereHandler;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;

@ManagedService(path = "/chat")
public class ChatHandler extends ManagedAtmosphereHandler {

    @Override
    public void onOpen(AtmosphereResource resource) {
        resource.getBroadcaster().broadcast(
            "{\"type\":\"join\",\"user\":\"" + resource.uuid() + "\"}");
    }

    @Override
    public void onMessage(AtmosphereResource resource, String message) {
        resource.getBroadcaster().broadcast(message);
    }

    @Override
    public void onDisconnect(AtmosphereResourceEvent event) {
        if (event.resource() != null) {
            event.resource().getBroadcaster().broadcast(
                "{\"type\":\"leave\",\"user\":\"" + event.resource().uuid() + "\"}");
        }
    }
}
```

### Running with Jaeger

Start Jaeger using the all-in-one Docker image:

```bash
docker run -d --name jaeger \
  -p 16686:16686 \
  -p 4317:4317 \
  jaegertracing/all-in-one:latest
```

Start the application:

```bash
./mvnw spring-boot:run -pl samples/spring-boot-otel-chat
```

Open `http://localhost:16686` (Jaeger UI) and select the `atmosphere-otel-chat` service. You will see spans for every WebSocket connection, message, and disconnect -- each annotated with transport, broadcaster, and room attributes.

Open `http://localhost:8080/actuator/prometheus` to see raw Prometheus metrics including `atmosphere_connections_active`, `atmosphere_messages_broadcast_total`, and `atmosphere_broadcast_timer_seconds_*`.

### Testing the Trace Pipeline

Connect a client and send a few messages:

```typescript
import { atmosphere } from 'atmosphere.js';

const sub = await atmosphere.subscribe({
  url: 'http://localhost:8080/chat',
  transport: 'websocket',
}, {
  message: (r) => console.log(r.responseBody),
  open: () => {
    sub.push('Hello from the observability chapter!');
    sub.push('Another message for the trace.');
  },
});
```

In Jaeger, search for traces from `atmosphere-otel-chat`. You should see:

1. A root span for the WebSocket SUBSCRIBE with `atmosphere.action = SUSPEND`.
2. Child spans for each MESSAGE with `atmosphere.action = CONTINUE`.
3. A final DISCONNECT span when the client closes the connection.

---

## Custom Metrics

You can register your own application-level metrics alongside the Atmosphere metrics. The `MeterRegistry` is shared:

```java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@ManagedService(path = "/chat")
public class InstrumentedChat extends ManagedAtmosphereHandler {

    private final Counter profanityCounter;

    public InstrumentedChat(MeterRegistry registry) {
        this.profanityCounter = Counter.builder("chat.profanity.filtered")
                .description("Messages filtered by profanity check")
                .register(registry);
    }

    @Override
    public void onMessage(AtmosphereResource resource, String message) {
        if (containsProfanity(message)) {
            profanityCounter.increment();
            return;
        }
        resource.getBroadcaster().broadcast(message);
    }
}
```

---

## Alerting Recommendations

Based on the metrics Atmosphere exposes, here are recommended alerts for production:

| Alert | Condition | Severity |
|---|---|---|
| High connection churn | `rate(atmosphere_connections_total[5m]) > 100` and `atmosphere_connections_active < 10` | Warning |
| Broadcaster leak | `atmosphere_broadcasters_active > <expected_max>` | Critical |
| Broadcast latency spike | `histogram_quantile(0.99, rate(atmosphere_broadcast_timer_seconds_bucket[5m])) > 0.5` | Warning |
| Backpressure firing | Log pattern match on `BackpressureInterceptor` WARN messages | Warning |
| Room imbalance | `max(atmosphere_rooms_members) / avg(atmosphere_rooms_members) > 10` | Info |
| Cache saturation | Application-level monitoring of cache eviction logs | Warning |

---

## Summary

In this chapter you learned:

- **AtmosphereMetrics** gives you six core metrics (connections, broadcasters, messages, broadcast latency) plus per-room gauges.
- **AtmosphereTracing** creates OpenTelemetry spans for every Atmosphere request, with transport, action, broadcaster, and room attributes.
- **McpTracing** extends tracing to MCP tool, resource, and prompt invocations.
- **BackpressureInterceptor** protects your server from slow consumers and acts as a health signal you should monitor.
- **BroadcasterCache** configuration prevents memory exhaustion while keeping reconnecting clients fed.
- **Spring Boot auto-configures** all of this when the right dependencies are on the classpath.

In the [next chapter](/docs/tutorial/19-client/), we switch from the server to the client and explore `atmosphere.js` -- the TypeScript client library with first-class React, Vue, and Svelte hooks.

## See Also

- [Observability Reference](/docs/reference/observability/) -- compact API reference
- [MCP Server](/docs/reference/mcp/) -- MCP tracing details
- [Spring Boot Integration](/docs/integrations/spring-boot/) -- auto-configuration reference
