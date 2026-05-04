# Atmosphere Quarkus Extension

A Quarkus extension that integrates Atmosphere with Quarkus 3.21+. Provides build-time annotation scanning via Jandex, Arc CDI integration, and GraalVM native image support.

## Maven Coordinates

Add the runtime artifact to your application:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-quarkus-extension</artifactId>
    <version>${project.version}</version>
</dependency>
```

The deployment artifact (`atmosphere-quarkus-extension-deployment`) is resolved automatically by Quarkus.

## Minimal Example

### application.properties

```properties
quarkus.atmosphere.packages=com.example.chat
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

The extension auto-registers the Atmosphere servlet -- no `web.xml` or manual servlet registration needed.

## Configuration Properties

All properties are under the `quarkus.atmosphere.*` prefix:

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.atmosphere.packages` | (none) | Comma-separated packages to scan |
| `quarkus.atmosphere.servlet-path` | `/atmosphere/*` | Servlet URL mapping |
| `quarkus.atmosphere.session-support` | `false` | Enable HTTP session support |
| `quarkus.atmosphere.broadcaster-class` | (default) | Custom `Broadcaster` implementation |
| `quarkus.atmosphere.broadcaster-cache-class` | (default) | Custom `BroadcasterCache` implementation |
| `quarkus.atmosphere.load-on-startup` | `1` | Servlet load-on-startup order — **must be > 0** or the servlet will not initialize |
| `quarkus.atmosphere.heartbeat-interval` | (default) | Heartbeat interval (e.g. `30s`, `5m`). Converted to seconds internally |
| `quarkus.atmosphere.init-params` | (none) | Map of raw `ApplicationConfig` init params passed directly to the servlet |

## Running

```bash
mvn quarkus:dev                          # dev mode with live reload
mvn clean package && java -jar target/quarkus-app/quarkus-run.jar  # JVM
mvn clean package -Pnative               # native image
```

The same `@ManagedService` handler works across WAR, Spring Boot, and Quarkus -- only packaging and configuration differ.

## Sample

- [Quarkus Chat](../../samples/quarkus-chat/) -- real-time chat with WebSocket and long-polling fallback
- [Quarkus AI Chat](../../samples/quarkus-ai-chat/) -- 5 `@AiEndpoint` paths (basic chat / prompt-cache / retry / multi-modal / structured-output) backed by `atmosphere-quarkus-langchain4j`

## Spring Boot ↔ Quarkus Auto-Config Parity

The Spring Boot starter ships **14 auto-configurations** (admin Console, AI
endpoint discovery, auth, cache, coordinator, favicon, governance metrics,
gRPC, Micrometer metrics, OpenTelemetry tracing, WebTransport, durable
sessions, plus the core servlet wiring). The Quarkus extension currently
covers a strict subset:

| Surface | Spring Boot autoconfig | Quarkus build step | Status |
|---------|------------------------|---------------------|--------|
| Core servlet + framework init | `AtmosphereAutoConfiguration` | `AtmosphereProcessor.registerServlet` + `deferredFrameworkInit` + `ignoreAtmosphereScis` | ✓ wired |
| `@AiEndpoint` discovery | `AtmosphereAiEndpointRegistrar` (in `AtmosphereAiAutoConfiguration`) | `AtmosphereProcessor.scanAnnotations` (annotation goes through Jandex; Atmosphere AI annotation processor picks it up at servlet init) | ✓ wired |
| Admin Console SPA | `AtmosphereAdminAutoConfiguration` | `AtmosphereConsoleServlet` bundled in `quarkus-admin` (commit 28703ea064) | ✓ wired |
| WebSocket endpoints | (Spring Boot uses Tomcat's WSCI) | `AtmosphereProcessor.registerWebSocketEndpoints` (`ServerWebSocketContainerBuildItem`) | ✓ wired |
| Native image reflection | (Spring Boot doesn't need it) | `AtmosphereProcessor.registerReflection` + `registerPoolReflection` + `registerEncoderDecoderClasses` | ✓ wired |
| Auth (`AtmosphereAuthAutoConfiguration`) | `AuthFilter` chain + `AtmosphereSecurityFilter` | — | **gap** — use Quarkus Security directly (`@RolesAllowed` on `@AiEndpoint`-annotated classes) |
| Cache (`AtmosphereCacheAutoConfiguration`) | `BroadcasterCache` autowiring | — | **gap** — wire manually via `BroadcasterFactory` listener |
| Coordinator (`AtmosphereCoordinatorAutoConfiguration`) | `@Coordinator` + `@Fleet` discovery | — | **gap** — `@Coordinator` classes work but `@Fleet` autowiring needs CDI bean producers |
| Favicon (`AtmosphereFaviconAutoConfiguration`) | `/favicon.ico` handler | — | **gap** — Quarkus serves `META-INF/resources/favicon.ico` natively, so use that |
| Governance metrics (`AtmosphereGovernanceMetricsAutoConfiguration`) | Per-policy Micrometer counters | — | **gap** — depends on Micrometer auto-config |
| gRPC (`AtmosphereGrpcAutoConfiguration`) | `GrpcAtmosphereService` registration | — | **gap** — Quarkus gRPC differs (uses Vert.x gRPC, not Netty); needs separate extension |
| Micrometer metrics (`AtmosphereMetricsAutoConfiguration`) | `MeterRegistry` autowiring + `AiMetrics` binding | — | **gap** — wire manually via `quarkus-micrometer` |
| OTel tracing (`AtmosphereTracingAutoConfiguration`) | `Tracer` autowiring + `AiTracing` binding | — | **gap** — wire manually via `quarkus-opentelemetry` |
| WebTransport (`AtmosphereWebTransportAutoConfiguration`) | Reactor Netty sidecar startup | — | **gap** — sidecar architecture works but lifecycle wiring needs a `LifecycleEventBuildItem` consumer |
| Durable sessions (`DurableSessionAutoConfiguration`) | `DurableSessionStore` SPI + REST control plane | — | **gap** — needs CDI scoping + `@Path` mapping equivalent |

**Honest summary:** the four most-load-bearing Quarkus surfaces (servlet,
AI endpoint discovery, admin Console, WebSocket) are wired. The eight
non-core auto-configs are gaps tracked against this README — a Quarkus app
can still use them but needs to wire the underlying primitives manually
(via Quarkus Security / Micrometer / OTel / etc.) instead of getting them
for free. Closure of these gaps is staged work, one `@BuildStep` at a
time, with the matching Quarkus sample feature porting in lockstep so a
demo proves the wiring on every PR.

## Full Documentation

See <https://atmosphere.github.io/docs/integrations/quarkus/> for complete documentation.

## Requirements

- Java 21+
- Quarkus 3.21+
