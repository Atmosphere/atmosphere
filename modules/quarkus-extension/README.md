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
| `quarkus.atmosphere.cache-enabled` | `false` | When `true`, the deployment processor wires `BoundedMemoryCache` as the default `BroadcasterCache` and installs `MessageAckInterceptor` for missed-message recovery (Spring Boot parity for `atmosphere.cache.enabled`). Explicit `broadcaster-cache-class` overrides this default. |
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

The Quarkus extension wires Atmosphere into Quarkus via build-time
`@BuildStep`s. Surfaces covered today are listed below — every row
ties to a `@BuildStep`, the consumer it serves, and an integration
test that fails without the build step.

### Wired here in `AtmosphereProcessor` (10 surfaces)

| Surface | Quarkus build step | Consumer |
|---------|---------------------|----------|
| Core servlet + framework init | `AtmosphereProcessor.registerServlet` + `deferredFrameworkInit` + `ignoreAtmosphereScis` | every Quarkus app using `atmosphere-quarkus-extension` |
| `@AiEndpoint` discovery | `AtmosphereProcessor.scanAnnotations` (Jandex `CombinedIndex`; Atmosphere AI annotation processor picks it up at servlet init) | `samples/quarkus-ai-chat` (5 endpoints) |
| Console mode endpoint (`/api/console/info`) | `AtmosphereProcessor.registerConsoleInfoServlet` registers `AtmosphereConsoleInfoServlet` (commit `4be7c7f0ad`) — same handler-class mode-detection heuristic as the Spring Boot starter's `AtmosphereConsoleInfoEndpoint`; new config keys `quarkus.atmosphere.console-subtitle` / `quarkus.atmosphere.console-endpoint` mirror the Spring `atmosphere.console-*` properties | `samples/quarkus-ai-chat` Console UI (Vue frontend reads `mode` to swap empty-state copy and default subtitle) |
| WebSocket endpoints | `AtmosphereProcessor.registerWebSocketEndpoints` (consumes `ServerWebSocketContainerBuildItem`) | every WebSocket-using Quarkus app |
| Native image reflection | `AtmosphereProcessor.registerReflection` + `registerPoolReflection` + `registerEncoderDecoderClasses` | Quarkus `native-image` builds |
| Cache (`AtmosphereCacheAutoConfiguration` parity) | `AtmosphereProcessor.registerCacheReflection` + cache wiring in `registerServlet` — when `quarkus.atmosphere.cache-enabled=true`, threads `broadcasterCacheClass=BoundedMemoryCache` and `AtmosphereInterceptor=MessageAckInterceptor` onto the servlet init params and registers both classes for native-image reflection | `samples/quarkus-ai-chat#PromptCacheDemoChat` exercises the cache via `@AiEndpoint(promptCache = CONSERVATIVE)`; integration test `AtmosphereCacheBuildStepTest` asserts `BROADCASTER_CACHE` is threaded + `MessageAckInterceptor` is in the chain |
| Actuator / health (`AtmosphereActuatorAutoConfiguration` parity) | `AtmosphereProcessor.registerHealthCheck` registers `AtmosphereHealthCheck` as an `AdditionalBeanBuildItem` + `HealthBuildItem`, gated on `Capability.SMALLRYE_HEALTH` so users without `quarkus-smallrye-health` pay no startup cost | `samples/quarkus-ai-chat` surfaces the check at `/q/health` (e.g. `{"name":"atmosphere","status":"UP","data":{"handlers":5,"broadcasters":5,"interceptors":12,...}}`); integration test `AtmosphereHealthBuildStepTest` |
| Micrometer metrics (`AtmosphereMetricsAutoConfiguration` parity) | `AtmosphereProcessor.registerMetricsProducer` registers `AtmosphereMetricsProducer` (`@ApplicationScoped`, `@Observes StartupEvent`) as an `AdditionalBeanBuildItem` when `io.micrometer.core.instrument.MeterRegistry` is on the classpath; the producer calls `AtmosphereMetrics.install(framework, registry)` so the `atmosphere.*` gauges/counters/timers show up in the same Prometheus registry as the rest of Quarkus's meters | `samples/quarkus-ai-chat` exposes `atmosphere_connections_active`, `atmosphere_broadcasters_active`, `atmosphere_messages_broadcast_total`, etc. at `/q/metrics`; integration test `AtmosphereMetricsBuildStepTest` |
| OTel tracing (`AtmosphereTracingAutoConfiguration` parity) | `AtmosphereProcessor.registerTracingProducer` registers `AtmosphereTracingProducer` as an `AdditionalBeanBuildItem`, gated on `Capability.OPENTELEMETRY_TRACER`; the producer instantiates `AtmosphereTracing(OpenTelemetry)` and binds it as a framework interceptor on `@Observes StartupEvent` so every inspect/suspend/broadcast/disconnect gets a span | `samples/quarkus-ai-chat` — every WebSocket / long-poll request through `AiChat`, `PromptCacheDemoChat`, etc. gets traced (export controlled by `OTEL_TRACES_EXPORTER`); integration test `AtmosphereTracingBuildStepTest` |
| Governance metrics (`AtmosphereGovernanceMetricsAutoConfiguration` parity) | `AtmosphereProcessor.registerGovernanceMetricsProducer` stacks on the Micrometer step; when both `MeterRegistry` and `org.atmosphere.ai.governance.GovernanceMetricsHolder` are on the classpath, registers `AtmosphereGovernanceMetricsProducer` whose `@Observes StartupEvent` installs a Quarkus-side `MicrometerGovernanceMetrics` and resets it on `@Observes ShutdownEvent` | `samples/quarkus-ai-chat` — `@AgentScope`-decorated endpoints (all 5 demo endpoints) publish `atmosphere.governance.policy.evaluation` timers + `atmosphere.governance.scope.similarity` histograms to `/q/metrics`; integration test `AtmosphereGovernanceMetricsBuildStepTest` |

### Parallel route — `modules/quarkus-admin-extension` (Admin trio)

| Surface | Where it ships | Consumer |
|---------|----------------|----------|
| Admin Console SPA | `AdminProcessor.registerConsoleServlet` + `registerConsoleResources` bundles `AtmosphereConsoleServlet` (commit `f8930d62f4`) | `samples/quarkus-ai-chat` Console UI |
| Admin auto-config beans | `AdminProcessor.registerBeans` (Quarkus parity for `AtmosphereAdminAutoConfiguration`) | every Quarkus app on the admin extension |
| Admin REST controller | `AdminResource` (JAX-RS, Quarkus parity for `AtmosphereAdminEndpoint`) | admin Console UI + automation |

### Parallel route — `modules/quarkus-grpc` (gRPC transport)

| Surface | Where it ships | Consumer |
|---------|----------------|----------|
| gRPC server lifecycle (`AtmosphereGrpcAutoConfiguration` parity) | `AtmosphereQuarkusGrpcProcessor.registerLifecycleBean` registers `AtmosphereQuarkusGrpcLifecycle` (CDI `@Observes StartupEvent` / `ShutdownEvent` owning a standalone Netty `io.grpc.Server`) | `samples/quarkus-ai-chat` (`quarkus.atmosphere.grpc.enabled=true` on port 19090); proto-compatible with Spring Boot starter's gRPC server, see [`modules/quarkus-grpc/README.md`](../quarkus-grpc/README.md) |

Plus one **non-gap reclassified**: a `Favicon` auto-config does not
belong in Quarkus. Quarkus serves `META-INF/resources/favicon.ico`
natively without any extension; there is nothing for Atmosphere to wire
on this surface.
(Spring Boot needs an auto-config because its static-resource handling
is bean-driven; Quarkus's is build-time.)

### Surfaces handled via `atmosphere-spring-boot-starter`

A few Atmosphere capabilities are wired only on the Spring Boot side
today: auth (`AuthFilter` / `TokenValidator`), `@Coordinator` /
`@Fleet` autowiring, durable sessions, and WebTransport HTTP/3.
Quarkus apps that need any of these should depend on
`atmosphere-spring-boot-starter` for that piece — the `AgentRuntime`
SPI and `@Agent` code are framework-agnostic, so the agent itself
moves cleanly across.

## Full Documentation

See <https://atmosphere.github.io/docs/integrations/quarkus/> for complete documentation.

## Requirements

- Java 21+
- Quarkus 3.21+
