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

The Spring Boot starter ships 14 auto-configurations (admin Console, AI
endpoint discovery, auth, cache, coordinator, favicon, governance metrics,
gRPC, Micrometer metrics, OpenTelemetry tracing, WebTransport, durable
sessions, plus the core servlet wiring). The Quarkus extension covers
the load-bearing core today; non-core surfaces are tracked here as
explicit deferred build steps — **not** "documented gaps with workarounds".
Documenting an auto-config gap is not the same as closing it. Each row
below is either ✓ wired (with the consumer the wiring serves), or ⏳
deferred (with the upstream primitive the closure would need and the
size estimate).

### ✓ Wired here in `AtmosphereProcessor` (5 surfaces)

| Surface | Quarkus build step | Consumer |
|---------|---------------------|----------|
| Core servlet + framework init | `AtmosphereProcessor.registerServlet` + `deferredFrameworkInit` + `ignoreAtmosphereScis` | every Quarkus app using `atmosphere-quarkus-extension` |
| `@AiEndpoint` discovery | `AtmosphereProcessor.scanAnnotations` (Jandex `CombinedIndex`; Atmosphere AI annotation processor picks it up at servlet init) | `samples/quarkus-ai-chat` (5 endpoints) |
| Console mode endpoint (`/api/console/info`) | `AtmosphereProcessor.registerConsoleInfoServlet` registers `AtmosphereConsoleInfoServlet` (commit `4be7c7f0ad`) — same handler-class mode-detection heuristic as the Spring Boot starter's `AtmosphereConsoleInfoEndpoint`; new config keys `quarkus.atmosphere.console-subtitle` / `quarkus.atmosphere.console-endpoint` mirror the Spring `atmosphere.console-*` properties | `samples/quarkus-ai-chat` Console UI (Vue frontend reads `mode` to swap empty-state copy and default subtitle) |
| WebSocket endpoints | `AtmosphereProcessor.registerWebSocketEndpoints` (consumes `ServerWebSocketContainerBuildItem`) | every WebSocket-using Quarkus app |
| Native image reflection | `AtmosphereProcessor.registerReflection` + `registerPoolReflection` + `registerEncoderDecoderClasses` | Quarkus `native-image` builds |

### Parallel route — `modules/quarkus-admin-extension` (Admin trio)

| Surface | Where it ships | Consumer |
|---------|----------------|----------|
| Admin Console SPA | `AdminProcessor.registerConsoleServlet` + `registerConsoleResources` bundles `AtmosphereConsoleServlet` (commit `f8930d62f4`) | `samples/quarkus-ai-chat` Console UI |
| Admin auto-config beans | `AdminProcessor.registerBeans` (Quarkus parity for `AtmosphereAdminAutoConfiguration`) | every Quarkus app on the admin extension |
| Admin REST controller | `AdminResource` (JAX-RS, Quarkus parity for `AtmosphereAdminEndpoint`) | admin Console UI + automation |

Plus one **non-gap reclassified**: a `Favicon` auto-config does not
belong in Quarkus. Quarkus serves `META-INF/resources/favicon.ico`
natively without any extension; there is nothing for Atmosphere to wire
on this surface, so it does not appear in the deferred table either.
(Spring Boot needs an auto-config because its static-resource handling
is bean-driven; Quarkus's is build-time.)

### ⏳ Deferred (10 surfaces, each with a defined closure target)

Each closure ships in its own focused PR with: (1) the listed `@BuildStep`,
(2) a feature port in `samples/quarkus-ai-chat` that exercises it, (3) an
E2E test that fails without the build step. **No partial closures, no
"use the framework directly" hand-waves.** Until a build step ships,
Quarkus apps that need the surface route around Atmosphere — the README
does not pretend that constitutes adoption.

| Surface | Closure target | Size | Tracking |
|---------|----------------|------|----------|
| Cache (`AtmosphereCacheAutoConfiguration`) | New `@BuildStep` registers `BroadcasterCache` via `quarkus.atmosphere.broadcaster-cache-class` config + servlet init listener | small | @Beta |
| Auth (`AtmosphereAuthAutoConfiguration`) | New `@BuildStep` produces a `FilterBuildItem` for `AuthFilter`; needs `quarkus-security` integration to map `@RolesAllowed` onto `AtmosphereSecurityFilter` | medium | @Beta |
| Actuator / health (`AtmosphereActuatorAutoConfiguration`) | New `@BuildStep` registers an `org.eclipse.microprofile.health.HealthCheck` bean wrapping `AtmosphereHealth` when `quarkus-smallrye-health` is on the classpath | small | @Beta |
| Micrometer metrics (`AtmosphereMetricsAutoConfiguration`) | New `@BuildStep` adds an `AdditionalBeanBuildItem` for an `@ApplicationScoped` producer that injects `MeterRegistry` and the running `AtmosphereFramework`, calls `AtmosphereMetrics.install(...)` from `@Observes StartupEvent` | small | @Beta |
| OTel tracing (`AtmosphereTracingAutoConfiguration`) | Same shape as Micrometer; injects `OpenTelemetry` from `quarkus-opentelemetry` and binds `AiTracing` | small | @Beta |
| Governance metrics (`AtmosphereGovernanceMetricsAutoConfiguration`) | Stacks on Micrometer; per-policy counter binding | small | depends on Micrometer closure |
| Coordinator (`AtmosphereCoordinatorAutoConfiguration`) | `@Coordinator` discovery via Jandex; `@Fleet` autowiring needs CDI bean producers generated at build time | medium | @Beta |
| Durable sessions (`DurableSessionAutoConfiguration`) | `DurableSessionStore` SPI bean producer + JAX-RS resource for the control plane | medium | @Beta |
| WebTransport (`AtmosphereWebTransportAutoConfiguration`) | Reactor Netty sidecar startup; needs `LifecycleEventBuildItem` consumer hooking `@Observes StartupEvent` / `ShutdownEvent` | medium | @Beta |
| gRPC (`AtmosphereGrpcAutoConfiguration`) | Vert.x gRPC ≠ Netty gRPC; closure requires a separate `atmosphere-quarkus-grpc` extension parallel to `quarkus-grpc` | large | @Beta |

**Honesty contract.** The Spring Boot starter ships 17
`@AutoConfiguration`-annotated classes (14 named `*AutoConfiguration.java`
plus three `@AutoConfiguration`-annotated REST controllers:
`AtmosphereAdminEndpoint`, `AtmosphereConsoleInfoEndpoint`,
`webtransport/WebTransportInfoController`). The Quarkus side covers
those 17 surfaces as follows: 3 are wired here as `@BuildStep`s in
`AtmosphereProcessor` (core servlet for `AtmosphereAutoConfiguration`,
`@AiEndpoint` discovery for `AtmosphereAiAutoConfiguration`, Console mode
endpoint for `AtmosphereConsoleInfoEndpoint` — the latter ships as the
new `AtmosphereConsoleInfoServlet` registered alongside the core
servlet); 3 ship via the parallel `modules/quarkus-admin-extension`
route (Admin Console SPA + `AtmosphereAdminAutoConfiguration` bean
wiring + the `AtmosphereAdminEndpoint` REST controller — they live
together in that extension because the JAX-RS resource and the Console
static-resource servlet share its build steps); 1 is reclassified as a
non-gap (favicon — Quarkus serves static resources natively); and 10
are tracked as deferred rows in the table above (one row per closure
target — the WebTransport row covers two Spring classes,
`AtmosphereWebTransportAutoConfiguration` plus the
`webtransport/WebTransportInfoController` info endpoint, because a
single `@BuildStep` will close both). 3 + 3 + 1 + 10 (rows, covering 11
Spring classes via the WT double-up) = 17. Two additional Quarkus-only
surfaces — WebSocket endpoint registration and native-image reflection —
also ship today; they have no Spring-side counterpart because Spring
Boot's runtime handles those intrinsically. Until each closure ships, this README does not pretend
the surface is "available with a workaround" — Quarkus apps that need
durable sessions or coordinator fleet wiring should expect to roll
their own until the corresponding PR lands. Each closure PR removes
one row from the deferred table and adds one row to the wired table,
with a sample E2E proving it.

## Full Documentation

See <https://atmosphere.github.io/docs/integrations/quarkus/> for complete documentation.

## Requirements

- Java 21+
- Quarkus 3.21+
