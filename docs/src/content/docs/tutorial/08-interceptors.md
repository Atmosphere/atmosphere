---
title: "Interceptors & Middleware"
description: "Built-in and custom interceptors for heartbeats, backpressure, rate limiting, and authentication"
---

Interceptors are Atmosphere's middleware layer. They sit between the transport and your handler, providing cross-cutting concerns like heartbeats, message tracking, backpressure management, and authentication.

## AtmosphereInterceptor Interface

```java
public interface AtmosphereInterceptor {
    void configure(AtmosphereConfig config);
    Action inspect(AtmosphereResource resource);
    void postInspect(AtmosphereResource resource);
    void destroy();
}
```

- **`inspect()`** runs before your handler. Return `Action.CONTINUE` to proceed, `Action.CANCELLED` to stop the chain.
- **`postInspect()`** runs after your handler completes.

## Registering Interceptors

### Per-endpoint (annotation)

```java
@ManagedService(path = "/chat",
    interceptors = {HeartbeatInterceptor.class, BackpressureInterceptor.class})
public class Chat {
    @Message
    public String onMessage(String message) {
        return message;
    }
}
```

### Framework-level (global)

```java
framework.interceptor(new HeartbeatInterceptor());
framework.interceptor(new BackpressureInterceptor());
```

### Spring Boot

```yaml
atmosphere:
  init-params:
    org.atmosphere.cpr.AtmosphereInterceptor: >
      org.atmosphere.interceptor.HeartbeatInterceptor,
      org.atmosphere.interceptor.BackpressureInterceptor
```

## Key Built-in Interceptors

### HeartbeatInterceptor

Sends periodic heartbeats to keep connections alive through proxies:

```properties
org.atmosphere.cpr.heartbeatIntervalInSeconds=30
```

React to heartbeats in your handler:

```java
@Heartbeat
public void onHeartbeat(AtmosphereResourceEvent event) {
    log.debug("Heartbeat from {}", event.getResource().uuid());
}
```

### BackpressureInterceptor

Protects slow clients from unbounded message queuing:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.backpressure.highWaterMark` | `1000` | Max pending messages per client |
| `org.atmosphere.backpressure.policy` | `drop-oldest` | `drop-oldest`, `drop-newest`, or `disconnect` |

### RateLimitingInterceptor

Throttles per-client request rate:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.rateLimit.maxRequests` | `100` | Max requests per window |
| `org.atmosphere.rateLimit.windowMs` | `60000` | Window size in milliseconds |

### TrackMessageSizeInterceptor

Prepends message length for boundary detection in HTTP streaming. Enable on the client:

```typescript
atmosphere.subscribe({
  url: '/chat',
  transport: 'websocket',
  trackMessageLength: true,
});
```

### OnDisconnectInterceptor

Ensures `@Disconnect` fires reliably even without a clean disconnect event.

## Writing Custom Interceptors

### Authentication

```java
public class AuthInterceptor implements AtmosphereInterceptor {

    @Override
    public void configure(AtmosphereConfig config) { }

    @Override
    public Action inspect(AtmosphereResource resource) {
        var token = resource.getRequest().getHeader("Authorization");
        if (token == null || !isValidToken(token)) {
            resource.getResponse().setStatus(401);
            return Action.CANCELLED;
        }
        resource.getRequest().setAttribute("user", extractUser(token));
        return Action.CONTINUE;
    }

    @Override
    public void postInspect(AtmosphereResource resource) { }

    @Override
    public void destroy() { }
}
```

### Request Logging

```java
public class LoggingInterceptor implements AtmosphereInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public void configure(AtmosphereConfig config) { }

    @Override
    public Action inspect(AtmosphereResource resource) {
        log.info("[{}] {} {} via {}",
            resource.uuid(),
            resource.getRequest().getMethod(),
            resource.getRequest().getRequestURI(),
            resource.transport());
        return Action.CONTINUE;
    }

    @Override
    public void postInspect(AtmosphereResource resource) { }

    @Override
    public void destroy() { }
}
```

## Interceptor Ordering

Interceptors run in registration order. Place authentication first:

```java
framework.interceptor(new AuthInterceptor());          // 1st
framework.interceptor(new RateLimitingInterceptor());   // 2nd
framework.interceptor(new HeartbeatInterceptor());      // 3rd
framework.interceptor(new BackpressureInterceptor());   // 4th
```

If any interceptor returns `Action.CANCELLED`, the chain stops.

## Framework vs Endpoint Scope

| Scope | Registration | Applies to |
|-------|-------------|-----------|
| Framework-level | `framework.interceptor(...)` | All endpoints |
| Endpoint-level | `@ManagedService(interceptors={...})` | That endpoint only |

Endpoint-level interceptors run **after** framework-level ones.

## AI Interceptors

The AI module adds `AiInterceptor` for pre/post-processing AI requests:

```java
@AiEndpoint(path = "/ai/chat",
    interceptors = {RagInterceptor.class})
public class AiChat { ... }
```

See [Chapter 12: AI Filters & Routing](/docs/tutorial/12-ai-filters/) for details.

## Next Steps

- [Chapter 9: @AiEndpoint & Streaming](/docs/tutorial/09-ai-endpoint/) — AI-specific interceptors
- [Chapter 18: Observability](/docs/tutorial/18-observability/) — tracing and metrics interceptors
