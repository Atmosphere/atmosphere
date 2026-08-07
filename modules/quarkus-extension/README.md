# Atmosphere Quarkus Extension

A Quarkus extension that integrates Atmosphere with Quarkus 3.21+. Provides build-time annotation scanning via Jandex, Arc CDI integration, and build-time registration of the classes Atmosphere loads by name so they survive ahead-of-time compilation -- see [Native Image](#native-image) for exactly what CI proves there, and what it does not.

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

## Native Image

Atmosphere resolves much of its own machinery by name -- broadcaster caches,
interceptors, annotation processors, encoders -- so ahead-of-time compilation
drops classes that nothing statically references. The extension registers those
classes at build time from three sources: the Jandex index (annotated classes,
plus any class named inside `@Message(encoders/decoders)` or `@Ready(encoders)`),
a short list of Quarkus-specific runtime classes, and the
`NativeImageMetadataProvider` SPI described below.

Failures in this area are quiet rather than loud. A missing broadcaster-cache
hint makes `createBroadcaster` throw `ClassNotFoundException`, which
`ManagedServiceProcessor` catches and logs before carrying on -- the process
starts, serves static content and answers a health probe, and the annotated
endpoint simply never exists. Native coverage claims therefore have to name the
request that was driven and the assertion that was made.

### What CI proves

Job `quarkus-native` in
[`.github/workflows/native-image-ci.yml`](../../.github/workflows/native-image-ci.yml)
builds `samples/quarkus-chat` into a native binary (Mandrel, container build),
starts it, and drives a single request:

```bash
curl "http://localhost:8080/atmosphere/chat?X-Atmosphere-tracking-id=0&X-Atmosphere-Framework=2.3&X-Atmosphere-Transport=long-polling&X-Cache-Date=0"
```

The job fails unless the application log then contains `connected (broadcaster:`,
a line only `Chat.onReady()` writes. Passing establishes, under a real native
image: `@ManagedService` discovery from the Jandex index, handler registration,
`Broadcaster` construction (and therefore its cache class), `@Inject` of
`AtmosphereResource` and a `@Named` `Broadcaster`, and `@Ready` firing -- over
**long-polling**.

### What CI does not prove

No native lane covers anything below. None of it should be described as
native-verified until one does:

- **WebSocket** -- the smoke test pins the transport to long-polling by hand.
- **SSE, transport negotiation and fallback.**
- **`@Message` round-trip.** `registerEncoderDecoderClasses` does register the
  sample's `JacksonEncoder` / `JacksonDecoder`, but the lane never POSTs a
  message, so the encode/decode path is compiled and never exercised.
- **Rooms / `@RoomService`, presence, broadcast fan-out, `@Disconnect`,
  `@Heartbeat`.**
- **The AI stack.** `samples/quarkus-ai-chat` has no native job and is not in
  the workflow's path filters; every `@AiEndpoint` row in the parity table
  below is JVM-mode evidence only.
- **Injection beyond what `@Ready` needs.**

### Contributing your own reflective types

`registerReflection` merges every `NativeImageMetadataProvider` found on the
build classpath, so a module -- including an application module -- declares its
reflective types next to the code that loads them instead of needing an entry
in a central list:

```java
package com.example.chat;

import java.util.Collection;
import java.util.List;
import org.atmosphere.nativeimage.NativeImageMetadataProvider;

public final class ExampleMetadataProvider implements NativeImageMetadataProvider {

    @Override
    public String name() {
        return "example-app";
    }

    @Override
    public Collection<String> reflectiveTypes() {
        return List.of("com.example.chat.CustomBroadcasterCache");
    }

    @Override
    public Collection<String> resourcePatterns() {
        return List.of("META-INF/services/com.example.chat.Plugin");
    }
}
```

Registered in
`src/main/resources/META-INF/services/org.atmosphere.nativeimage.NativeImageMetadataProvider`:

```
com.example.chat.ExampleMetadataProvider
```

`isAvailable()` (default `true`) lets a provider covering an optional
dependency exclude itself when that dependency is absent; `priority()` only
orders the emitted output. Collection is a union, so no provider can suppress
another's types. In this repository `atmosphere-runtime` is currently the only
artifact shipping providers (`CoreNativeImageMetadataProvider` and
`PoolNativeImageMetadataProvider`); the SPI exists so that other modules and
applications can contribute without a change to this build step.

Those same providers generate
`META-INF/native-image/org.atmosphere/atmosphere-runtime/reachability-metadata.json`
inside `atmosphere-runtime`, which GraalVM reads on its own. That file is what a
plain-servlet or embedded deployment relies on with no integration module and no
configuration; the Quarkus build step exists because Quarkus consumes build
items rather than that file.

Quarkus does not consume the `META-INF/atmosphere/annotated-classes.txt` index
that `atmosphere-runtime`'s annotation processor writes. That index serves
runtimes which would otherwise scan the classpath at runtime -- something a
native image makes impossible -- and Jandex already indexes annotations at
build time here.

## Sample

- [Quarkus Chat](../../samples/quarkus-chat/) -- real-time chat with WebSocket and long-polling fallback. Also the sample the native lane builds, where only the long-polling path is driven ([Native Image](#native-image))
- [Quarkus AI Chat](../../samples/quarkus-ai-chat/) -- 5 `@AiEndpoint` paths (basic chat / prompt-cache / retry / multi-modal / structured-output) backed by `atmosphere-quarkus-langchain4j`

## Spring Boot ↔ Quarkus Auto-Config Parity

The Quarkus extension wires Atmosphere into Quarkus via build-time
`@BuildStep`s. Surfaces covered today are listed below — every row
ties to a `@BuildStep`, the consumer it serves, and an integration
test that fails without the build step.

### Wired here in `AtmosphereProcessor` (12 surfaces)

| Surface | Quarkus build step | Consumer |
|---------|---------------------|----------|
| Core servlet + framework init | `AtmosphereProcessor.registerServlet` + `deferredFrameworkInit` + `ignoreAtmosphereScis` | every Quarkus app using `atmosphere-quarkus-extension` |
| `@AiEndpoint` discovery | `AtmosphereProcessor.scanAnnotations` (Jandex `CombinedIndex`; Atmosphere AI annotation processor picks it up at servlet init) | `samples/quarkus-ai-chat` (5 endpoints) |
| Console mode endpoint (`/api/console/info`) | `AtmosphereProcessor.registerConsoleInfoServlet` registers `AtmosphereConsoleInfoServlet` (commit `4be7c7f0ad`) — same handler-class mode-detection heuristic as the Spring Boot starter's `AtmosphereConsoleInfoEndpoint`; new config keys `quarkus.atmosphere.console-subtitle` / `quarkus.atmosphere.console-endpoint` mirror the Spring `atmosphere.console-*` properties | `samples/quarkus-ai-chat` Console UI (Vue frontend reads `mode` to swap empty-state copy and default subtitle) |
| WebSocket endpoints | `AtmosphereProcessor.registerWebSocketEndpoints` (consumes `ServerWebSocketContainerBuildItem`) | every WebSocket-using Quarkus app |
| Native image reflection | `AtmosphereProcessor.registerReflection` registers the Jandex-discovered annotated classes, four Quarkus-specific runtime classes, and — via `NativeImageMetadata.collect()` — the reflective types and resource patterns every `NativeImageMetadataProvider` on the classpath declares, logging the counts and provider names at INFO (this replaced a direct read of `AtmosphereReflectiveTypes.coreTypes()`, which covered only `atmosphere-runtime` and was transcribed again in both Spring starters); `registerPoolReflection` still reads `AtmosphereReflectiveTypes.poolTypes()` directly, behind a `commons-pool2` presence check; `registerEncoderDecoderClasses` walks the Jandex index for `@Message(encoders/decoders)` and `@Ready(encoders)` so classes named only inside an annotation survive the image | CI job `quarkus-native` in [`native-image-ci.yml`](../../.github/workflows/native-image-ci.yml) builds `samples/quarkus-chat` with Mandrel, starts the binary, drives one long-polling GET to `/atmosphere/chat` and fails unless the log contains `connected (broadcaster:` — the line `Chat.onReady()` emits. This is the one row with no integration test; see [Native Image](#native-image) for what that assertion does and does not establish |
| Cache (`AtmosphereCacheAutoConfiguration` parity) | `AtmosphereProcessor.registerCacheReflection` + cache wiring in `registerServlet` — when `quarkus.atmosphere.cache-enabled=true`, threads `broadcasterCacheClass=BoundedMemoryCache` and `AtmosphereInterceptor=MessageAckInterceptor` onto the servlet init params and registers both classes for native-image reflection | `samples/quarkus-ai-chat#PromptCacheDemoChat` exercises the cache via `@AiEndpoint(promptCache = CONSERVATIVE)`; integration test `AtmosphereCacheBuildStepTest` asserts `BROADCASTER_CACHE` is threaded + `MessageAckInterceptor` is in the chain |
| Actuator / health (`AtmosphereActuatorAutoConfiguration` parity) | `AtmosphereProcessor.registerHealthCheck` registers `AtmosphereHealthCheck` as an `AdditionalBeanBuildItem` + `HealthBuildItem`, gated on `Capability.SMALLRYE_HEALTH` so users without `quarkus-smallrye-health` pay no startup cost | `samples/quarkus-ai-chat` surfaces the check at `/q/health` (e.g. `{"name":"atmosphere","status":"UP","data":{"handlers":5,"broadcasters":5,"interceptors":12,...}}`); integration test `AtmosphereHealthBuildStepTest` |
| Micrometer metrics (`AtmosphereMetricsAutoConfiguration` parity) | `AtmosphereProcessor.registerMetricsProducer` registers `AtmosphereMetricsProducer` (`@ApplicationScoped`, `@Observes StartupEvent`) as an `AdditionalBeanBuildItem` when `io.micrometer.core.instrument.MeterRegistry` is on the classpath; the producer calls `AtmosphereMetrics.install(framework, registry)` so the `atmosphere.*` gauges/counters/timers show up in the same Prometheus registry as the rest of Quarkus's meters | `samples/quarkus-ai-chat` exposes `atmosphere_connections_active`, `atmosphere_broadcasters_active`, `atmosphere_messages_broadcast_total`, etc. at `/q/metrics`; integration test `AtmosphereMetricsBuildStepTest` |
| OTel tracing (`AtmosphereTracingAutoConfiguration` parity) | `AtmosphereProcessor.registerTracingProducer` registers `AtmosphereTracingProducer` as an `AdditionalBeanBuildItem`, gated on `Capability.OPENTELEMETRY_TRACER`; the producer instantiates `AtmosphereTracing(OpenTelemetry)` and binds it as a framework interceptor on `@Observes StartupEvent` so every inspect/suspend/broadcast/disconnect gets a span | `samples/quarkus-ai-chat` — every WebSocket / long-poll request through `AiChat`, `PromptCacheDemoChat`, etc. gets traced (export controlled by `OTEL_TRACES_EXPORTER`); integration test `AtmosphereTracingBuildStepTest` |
| Governance metrics (`AtmosphereGovernanceMetricsAutoConfiguration` parity) | `AtmosphereProcessor.registerGovernanceMetricsProducer` stacks on the Micrometer step; when both `MeterRegistry` and `org.atmosphere.ai.governance.GovernanceMetricsHolder` are on the classpath, registers `AtmosphereGovernanceMetricsProducer` whose `@Observes StartupEvent` installs a Quarkus-side `MicrometerGovernanceMetrics` and resets it on `@Observes ShutdownEvent` | `samples/quarkus-ai-chat` — `@AgentScope`-decorated endpoints (all 5 demo endpoints) publish `atmosphere.governance.policy.evaluation` timers + `atmosphere.governance.scope.similarity` histograms to `/q/metrics`; integration test `AtmosphereGovernanceMetricsBuildStepTest` |
| Durable-run checkpoints (`AtmosphereCheckpointAutoConfiguration` + `AtmosphereCheckpointEndpoint` parity) | `AtmosphereProcessor.registerCheckpointStore`, gated on `org.atmosphere.checkpoint.CheckpointStore`, registers `AtmosphereCheckpointProducer` (produces a `@DefaultBean CheckpointStore` — bounded in-memory by default with a NOT-crash-durable startup WARN, crash-durable `SqliteCheckpointStore` when `quarkus.atmosphere.ai.checkpoint.store=sqlite`, or a user-supplied bean that wins) and maps the read-only `AtmosphereCheckpointServlet` at `/api/admin/checkpoints`; the same gate threads `hasCheckpoints=true` into `/api/console/info` so the console's Checkpoints tab appears only when the read plane exists (Runtime Truth). Config keys `quarkus.atmosphere.ai.checkpoint.{store,path,max-snapshots}` | any Quarkus app that adds `atmosphere-checkpoint` (Console Checkpoints tab + `GET /api/admin/checkpoints`); integration test `CheckpointStoreBuildStepTest` |
| Per-tenant cost ceiling (`CostAccountantInstaller` parity) | `AtmosphereProcessor.registerCostAccountantProducer`, gated on `org.atmosphere.ai.cost.CostAccountantHolder`, registers `AtmosphereCostAccountantProducer`: `QuarkusAtmosphereServlet` bridges the effective `CostCeilingGuardrail` (user CDI bean, else built from `quarkus.atmosphere.ai.guardrails.cost.{enabled,budget-usd}` — the mirror of Spring's `atmosphere.ai.guardrails.cost.*`) into the framework guardrail chain before annotation processing, and on `@Observes StartupEvent` installs a user `CostAccountant` bean or the built-in `CostCeilingAccountant` (guardrail + `TokenPricing` CDI bean) into `CostAccountantHolder` / `TokenPricingHolder`, resetting what it installed on `@Observes ShutdownEvent` | any Quarkus app that sets a cost budget (per-turn `TokenUsage` accrues dollar spend, the next request past the ceiling Blocks); integration tests `CostCeilingAccountantBuildStepTest` / `CostAccountantNoConfigTest` + `GovernancePolicyQuarkusParityTest` |

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
