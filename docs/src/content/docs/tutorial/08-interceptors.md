---
title: "Interceptors"
description: "Built-in and custom interceptors for heartbeats, message tracking, backpressure, and room protocol handling"
sidebar:
  order: 8
---

Interceptors are Atmosphere's middleware layer. They sit between the transport and your handler, providing cross-cutting concerns like heartbeats, message tracking, room protocol handling, and authentication. Every request passes through a chain of interceptors before reaching your `@ManagedService`, `@WebSocketHandlerService`, or `AtmosphereHandler`.

## AtmosphereInterceptor Interface

The core interface has three methods:

```java
public interface AtmosphereInterceptor extends AtmosphereConfigAware {

    Action inspect(AtmosphereResource r);

    void postInspect(AtmosphereResource r);

    void destroy();
}
```

| Method | When It Runs | Purpose |
|--------|-------------|---------|
| `inspect(r)` | Before the handler | Examine/modify the request; decide whether to continue or cancel |
| `postInspect(r)` | After the handler | Clean up, log, or modify the response |
| `destroy()` | At shutdown | Release resources |

The `configure(AtmosphereConfig)` method is inherited from `AtmosphereConfigAware` and is called once when the interceptor is registered.

### The Action Return Value

`inspect()` returns an `Action` that controls what happens next:

| Action | Effect |
|--------|--------|
| `Action.CONTINUE` | Pass through to the next interceptor and eventually the handler |
| `Action.SUSPEND` | Suspend the connection (used internally by transport interceptors) |
| `Action.CANCELLED` | Stop processing; do not call the handler or further interceptors |

## AtmosphereInterceptorAdapter

Most custom interceptors extend `AtmosphereInterceptorAdapter` rather than implementing the interface directly. It provides sensible defaults:

```java
public abstract class AtmosphereInterceptorAdapter
        implements AtmosphereInterceptor, InvokationOrder {

    @Override
    public void configure(AtmosphereConfig config) { }

    @Override
    public Action inspect(AtmosphereResource r) {
        // Sets up an AtmosphereInterceptorWriter if none exists
        return Action.CONTINUE;
    }

    @Override
    public void postInspect(AtmosphereResource r) { }

    @Override
    public void destroy() { }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.AFTER_DEFAULT;
    }
}
```

By extending this adapter, you only need to override `inspect()` and optionally `configure()` and `priority()`.

## Priority Ordering

Interceptors run in a defined order controlled by the `InvokationOrder.PRIORITY` enum:

```java
public enum PRIORITY {
    FIRST_BEFORE_DEFAULT,  // Runs first, before everything
    BEFORE_DEFAULT,        // Runs before the default set
    AFTER_DEFAULT          // Runs after the default set (default)
}
```

Override `priority()` to control where your interceptor sits in the chain:

```java
public class AuthInterceptor extends AtmosphereInterceptorAdapter {

    @Override
    public PRIORITY priority() {
        return InvokationOrder.FIRST_BEFORE_DEFAULT;
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        var token = r.getRequest().getHeader("Authorization");
        if (token == null || !isValid(token)) {
            r.getResponse().setStatus(401);
            return Action.CANCELLED;
        }
        return Action.CONTINUE;
    }
}
```

The full ordering is:

1. `FIRST_BEFORE_DEFAULT` interceptors (one per class)
2. `BEFORE_DEFAULT` interceptors
3. Default/built-in interceptors
4. `AFTER_DEFAULT` interceptors (the default for custom interceptors)

## Built-In Interceptors

Atmosphere ships with several interceptors that are automatically installed or commonly added:

| Interceptor | Purpose |
|-------------|---------|
| `AtmosphereResourceLifecycleInterceptor` | Manages resource lifecycle (suspend, resume, disconnect) |
| `TrackMessageSizeInterceptor` | Prepends message length to each broadcast so the client can delimit frames |
| `HeartbeatInterceptor` | Sends periodic heartbeats to detect dead connections |
| `SuspendTrackerInterceptor` | Tracks suspended resources for proper cleanup |
| `BroadcastOnPostAtmosphereInterceptor` | Automatically broadcasts HTTP POST body to the Broadcaster |
| `RoomProtocolInterceptor` | Bridges atmosphere.js room protocol to the server-side Room API |
| `MDCInterceptor` | Populates SLF4J MDC with Atmosphere context for structured logging |
| `BackpressureInterceptor` | Limits pending messages per client to protect against slow consumers |

### HeartbeatInterceptor

The `HeartbeatInterceptor` sends periodic heartbeat messages to clients. If the client fails to respond, the connection is detected as dead and cleaned up. This is critical for long-lived connections where network intermediaries (proxies, load balancers) may silently drop idle connections.

### TrackMessageSizeInterceptor

When you enable `TrackMessageSizeInterceptor`, each broadcast message is prefixed with its byte length. This allows the client-side library to properly delimit messages when multiple broadcasts arrive in a single TCP frame (which happens often over HTTP streaming and SSE).

### BroadcastOnPostAtmosphereInterceptor

This interceptor reads the body of HTTP POST requests and broadcasts it to the Broadcaster associated with the endpoint. It enables a pattern where clients send messages via standard HTTP POST and receive them via a suspended GET (long-polling or SSE).

### MDCInterceptor

The `MDCInterceptor` populates SLF4J MDC keys on every request so log lines automatically include Atmosphere context. This is essential for structured logging and debugging in production.

```java
framework.interceptor(new MDCInterceptor());
```

The interceptor sets the following MDC keys:

| Key | Value |
|-----|-------|
| `atmosphere.uuid` | Unique resource identifier |
| `atmosphere.transport` | Transport type (`websocket`, `long-polling`, `sse`, etc.) |
| `atmosphere.broadcaster` | Broadcaster ID the resource is attached to |

Logback pattern example:

```
%d{HH:mm:ss.SSS} [%thread] %-5level [uuid=%X{atmosphere.uuid} transport=%X{atmosphere.transport}] %logger{36} - %msg%n
```

MDC keys are automatically included as top-level fields in JSON layouts (logstash-logback-encoder, logback-contrib).

### BackpressureInterceptor

The `BackpressureInterceptor` protects against slow consumers by limiting the number of pending messages per client. When a client cannot keep up with the broadcast rate, this interceptor applies the configured policy.

```java
framework.interceptor(new BackpressureInterceptor());
```

Configuration via init parameters:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.backpressure.highWaterMark` | `1000` | Max pending messages per client |
| `org.atmosphere.backpressure.policy` | `drop-oldest` | `drop-oldest`, `drop-newest`, or `disconnect` |

The three policies:

| Policy | Behavior |
|--------|----------|
| `drop-oldest` | Drops the oldest pending message to make room (default) |
| `drop-newest` | Drops the incoming message |
| `disconnect` | Disconnects the slow client |

Drop and disconnect counts are exposed via Micrometer as `atmosphere.backpressure.drops` and `atmosphere.backpressure.disconnects` when metrics are enabled.

## Registering Interceptors

There are three ways to register interceptors.

### 1. Per-Endpoint via Annotations

Add interceptors to a specific endpoint using the `interceptors` attribute:

```java
@ManagedService(path = "/chat",
    interceptors = {TrackMessageSizeInterceptor.class, HeartbeatInterceptor.class})
public class ChatService {
    // ...
}
```

This also works with `@WebSocketHandlerService`:

```java
@WebSocketHandlerService(path = "/ws",
    interceptors = {TrackMessageSizeInterceptor.class})
public class MyHandler extends WebSocketStreamingHandlerAdapter {
    // ...
}
```

### 2. Auto-Scanning with @AtmosphereInterceptorService

Annotate your interceptor class with `@AtmosphereInterceptorService` and Atmosphere will discover it at startup:

```java
@AtmosphereInterceptorService
public class GlobalLoggingInterceptor extends AtmosphereInterceptorAdapter {

    @Override
    public Action inspect(AtmosphereResource r) {
        logger.info("Request from {} to {}",
            r.getRequest().getRemoteAddr(),
            r.getRequest().getRequestURI());
        return Action.CONTINUE;
    }
}
```

This registers the interceptor globally -- it runs for all endpoints.

### 3. Programmatic Registration

Register interceptors directly on the `AtmosphereFramework`:

```java
var interceptor = new RoomProtocolInterceptor();
interceptor.configure(framework.getAtmosphereConfig());
framework.interceptor(interceptor);
```

This is the approach used in the Spring Boot `RoomsConfig` sample for the `RoomProtocolInterceptor`.

## Writing a Custom Interceptor

Here is a complete custom interceptor that adds rate limiting:

```java
public class RateLimitInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private int maxRequestsPerMinute = 60;

    @Override
    public void configure(AtmosphereConfig config) {
        var limit = config.getInitParameter("rateLimit.maxPerMinute");
        if (limit != null) {
            maxRequestsPerMinute = Integer.parseInt(limit);
        }
    }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.FIRST_BEFORE_DEFAULT;
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        var clientId = r.uuid();
        var counter = counters.computeIfAbsent(clientId, k -> new AtomicInteger(0));

        if (counter.incrementAndGet() > maxRequestsPerMinute) {
            logger.warn("Rate limit exceeded for {}", clientId);
            r.getResponse().setStatus(429);
            return Action.CANCELLED;
        }

        return Action.CONTINUE;
    }
}
```

Key points:

- **Extend `AtmosphereInterceptorAdapter`** for no-op defaults.
- **Override `priority()`** to `FIRST_BEFORE_DEFAULT` so rate limiting happens before other processing.
- **Return `Action.CANCELLED`** to reject the request without reaching the handler.
- **Use `configure()`** to read init-params for configurable behavior.

## Real Example: RoomProtocolInterceptor

The `RoomProtocolInterceptor` from [Chapter 6](/docs/tutorial/06-rooms/) is a good example of a production interceptor. It:

1. **Extends `AtmosphereInterceptorAdapter`** -- inherits sensible defaults
2. **Overrides `configure()`** -- gets the `RoomManager` and scans for `@RoomAuth` annotated handlers
3. **Overrides `inspect()`** -- reads the request body, decodes JSON, and dispatches room operations using pattern matching
4. **Returns `Action.CANCELLED`** -- after handling a room protocol message, preventing downstream interceptors from re-broadcasting
5. **Overrides `priority()`** -- returns `BEFORE_DEFAULT` so it runs before `BroadcastOnPostAtmosphereInterceptor`

```java
public class RoomProtocolInterceptor extends AtmosphereInterceptorAdapter {

    private RoomManager roomManager;

    @Override
    public void configure(AtmosphereConfig config) {
        this.roomManager = RoomManager.getOrCreate(config.framework());
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        // Read and parse the request body
        // If it's a room protocol message, handle it and return CANCELLED
        // Otherwise, return CONTINUE
    }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.BEFORE_DEFAULT;
    }
}
```

## Interceptor Chain Flow

The interceptor chain for a typical request looks like this:

```
Client Request
  |
  v
[FIRST_BEFORE_DEFAULT interceptors]  -- e.g., AuthInterceptor, RateLimitInterceptor
  |
  v
[BEFORE_DEFAULT interceptors]        -- e.g., RoomProtocolInterceptor
  |
  v
[Default interceptors]               -- e.g., AtmosphereResourceLifecycleInterceptor
  |
  v
[AFTER_DEFAULT interceptors]         -- e.g., TrackMessageSizeInterceptor, custom logging
  |
  v
[AtmosphereHandler / @ManagedService / @WebSocketHandlerService]
  |
  v
[postInspect() in reverse order]
```

Any interceptor returning `Action.CANCELLED` stops the chain. The handler is never invoked, and `postInspect()` is called for the interceptors that already ran.

## CorsInterceptor

When your Atmosphere endpoint is served from a different origin than your client application, you need to enable Cross-Origin Resource Sharing (CORS). Atmosphere ships with `CorsInterceptor`, which handles the `Access-Control-*` headers automatically.

### Using CorsInterceptor (Recommended)

Add `CorsInterceptor` to your endpoint:

```java
@ManagedService(path = "/chat",
    interceptors = {CorsInterceptor.class})
public class Chat { /* ... */ }
```

Or register it globally:

```java
framework.interceptor(new CorsInterceptor());
```

The interceptor:
- Sets `Access-Control-Allow-Origin` to the request's `Origin` header (not `*`)
- Sets `Access-Control-Allow-Credentials: true`
- Exposes `X-Atmosphere-tracking-id` and heartbeat headers
- Handles `OPTIONS` preflight requests automatically
- Runs at `FIRST_BEFORE_DEFAULT` priority so CORS headers are set before any other processing

### Disabling CORS Headers

If your reverse proxy already handles CORS, disable the interceptor's header injection with:

```xml
<init-param>
    <param-name>org.atmosphere.cpr.dropAccessControlAllowOriginHeader</param-name>
    <param-value>true</param-value>
</init-param>
```

### Custom CORS Filter (Alternative)

If you need more control (e.g., restricting allowed origins), you can write a standard servlet filter instead:

```java
public class CorsFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var req = (HttpServletRequest) request;
        var res = (HttpServletResponse) response;

        if (req.getHeader("Origin") != null) {
            res.addHeader("Access-Control-Allow-Origin", "https://my-app.example.com");
            res.addHeader("Access-Control-Expose-Headers",
                "X-Cache-Date, X-Atmosphere-tracking-id");
        }

        if ("OPTIONS".equals(req.getMethod())) {
            res.addHeader("Access-Control-Allow-Methods", "OPTIONS, GET, POST");
            res.addHeader("Access-Control-Allow-Headers",
                "Origin, Content-Type, X-Atmosphere-Framework, "
                + "X-Cache-Date, X-Atmosphere-tracking-id, X-Atmosphere-Transport");
            res.addHeader("Access-Control-Max-Age", "-1");
        }
        chain.doFilter(req, res);
    }

    @Override
    public void init(FilterConfig filterConfig) { }

    @Override
    public void destroy() { }
}
```

Map it to the `AtmosphereServlet` in `web.xml`:

```xml
<filter>
    <filter-name>CORS Filter</filter-name>
    <filter-class>com.example.CorsFilter</filter-class>
</filter>
<filter-mapping>
    <filter-name>CORS Filter</filter-name>
    <servlet-name>AtmosphereServlet</servlet-name>
</filter-mapping>
```

## TrackMessageSizeInterceptor

`TrackMessageSizeInterceptor` prepends the byte length of each message to every write operation. This is essential for HTTP streaming and SSE transports where multiple messages can arrive in a single TCP frame. Without it, the client has no reliable way to split concatenated messages.

### How It Works

Given a broadcast message `{"author":"Alice"}`, the interceptor writes `18{"author":"Alice"}` on the wire. The `atmosphere.js` client reads the length prefix, extracts exactly that many bytes, and delivers a clean message to your callback.

### Configuration

Enable it via annotation:

```java
@ManagedService(path = "/chat",
    interceptors = {TrackMessageSizeInterceptor.class})
```

Or via `web.xml`:

```xml
<init-param>
    <param-name>org.atmosphere.cpr.AtmosphereInterceptor</param-name>
    <param-value>org.atmosphere.client.TrackMessageSizeInterceptor</param-value>
</init-param>
```

On the client side, set `trackMessageLength: true` in the `atmosphere.js` subscription options.

### Excluding Content Types

Static resources (HTML, images) served through the same servlet should not have their length prepended. Exclude them with:

```xml
<init-param>
    <param-name>org.atmosphere.client.TrackMessageSizeInterceptor.excludedContentType</param-name>
    <param-value>html, xml, jpeg</param-value>
</init-param>
```

See [Chapter 4: Transports -- Atmosphere Protocol](/docs/tutorial/04-transports/#message-framing-with-trackmessagesizeinterceptor) for how this fits into the overall protocol.

## Startup and Shutdown Hooks

Atmosphere provides hooks for executing code when the framework starts up or shuts down. These are useful for initializing external connections, starting background tasks, or cleaning up resources.

### Startup Hook

Register a `StartupHook` via `AtmosphereConfig.startupHook()`. The hook receives the `AtmosphereFramework` instance once initialization is complete:

```java
framework.getAtmosphereConfig().startupHook(f -> {
    logger.info("Atmosphere started -- initializing background services");
    // Start scheduled tasks, open connections, etc.
});
```

If the framework is already initialized when you register the hook, it executes immediately.

### Shutdown Hook

Register a `ShutdownHook` via `AtmosphereConfig.shutdownHook()`. It runs when the framework is destroyed (e.g., during application undeploy):

```java
framework.getAtmosphereConfig().shutdownHook(() -> {
    logger.info("Atmosphere shutting down -- releasing resources");
    // Close connections, stop schedulers, flush caches, etc.
});
```

Multiple hooks can be registered; they execute in registration order.

### Hook Interfaces

Both hooks are defined as inner interfaces of `AtmosphereConfig`:

```java
// AtmosphereConfig.StartupHook
public interface StartupHook {
    void started(AtmosphereFramework framework);
}

// AtmosphereConfig.ShutdownHook
public interface ShutdownHook {
    void shutdown();
}
```

Since both are functional interfaces, lambda expressions work as shown above.

## Summary

| Concept | Purpose |
|---------|---------|
| `AtmosphereInterceptor` | Core interface: `inspect()`, `postInspect()`, `destroy()` |
| `AtmosphereInterceptorAdapter` | Base class with no-op defaults and `AFTER_DEFAULT` priority |
| `Action.CONTINUE` / `Action.CANCELLED` | Control whether the request reaches the handler |
| `InvokationOrder.PRIORITY` | `FIRST_BEFORE_DEFAULT`, `BEFORE_DEFAULT`, `AFTER_DEFAULT` |
| `MDCInterceptor` | Populates SLF4J MDC with uuid, transport, and broadcaster for structured logging |
| `BackpressureInterceptor` | Limits pending messages per client; configurable drop or disconnect policies |
| `@AtmosphereInterceptorService` | Class-level annotation for auto-scanning at startup |
| `@ManagedService(interceptors={...})` | Per-endpoint interceptor registration |
| `framework.interceptor(...)` | Programmatic global registration |

**See also:** [@AiEndpoint & Streaming](/docs/tutorial/09-ai-endpoint/) for building AI chat endpoints with streaming text delivery, or [Spring Boot](/docs/tutorial/14-spring-boot/) and [Quarkus](/docs/tutorial/15-quarkus/) for deployment guides.
