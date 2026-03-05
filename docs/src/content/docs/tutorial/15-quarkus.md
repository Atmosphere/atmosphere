---
title: "Quarkus Integration"
description: "Run Atmosphere as a Quarkus extension with build-time annotation scanning and CDI injection"
sidebar:
  order: 15
---

The `atmosphere-quarkus-extension` module integrates Atmosphere with Quarkus 3.21+. It uses Quarkus's build-time processing to scan annotations via Jandex (no runtime classpath scanning), registers the servlet via `ServletBuildItem`, and bridges Quarkus's Arc CDI container to Atmosphere's object factory.

## Dependencies

The extension is split into two artifacts following Quarkus conventions:

```xml
<!-- Runtime module (what your application depends on) -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-quarkus-extension</artifactId>
    <version>LATEST</version> <!-- check Maven Central for latest -->
</dependency>
```

The deployment module (`atmosphere-quarkus-extension-deployment`) is pulled in automatically at build time by Quarkus's extension mechanism.

## Configuration

All configuration uses the `quarkus.atmosphere` prefix, defined via `@ConfigMapping`:

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.atmosphere.servlet-path` | `/atmosphere/*` | URL pattern for the Atmosphere servlet |
| `quarkus.atmosphere.packages` | (none) | Comma-separated packages to scan for Atmosphere annotations |
| `quarkus.atmosphere.load-on-startup` | `1` | Servlet load-on-startup order. **Must be > 0.** |
| `quarkus.atmosphere.session-support` | `false` | Enable HTTP session support |
| `quarkus.atmosphere.broadcaster-class` | (none) | Custom `Broadcaster` implementation class name |
| `quarkus.atmosphere.broadcaster-cache-class` | (none) | Custom `BroadcasterCache` implementation class name |
| `quarkus.atmosphere.websocket-support` | (auto) | Explicitly enable/disable WebSocket support |
| `quarkus.atmosphere.heartbeat-interval-in-seconds` | (auto) | Heartbeat interval |
| `quarkus.atmosphere.init-params.*` | (none) | Additional Atmosphere init parameters |

The configuration is `BUILD_AND_RUN_TIME_FIXED`, meaning values are read at build time and baked into the application. This is required because the `@BuildStep` methods that produce `ServletBuildItem` run during the Quarkus build phase.

### Example application.properties

From the `quarkus-chat` sample:

```properties
quarkus.atmosphere.packages=org.atmosphere.samples.quarkus.chat
quarkus.log.category."org.atmosphere".level=DEBUG
```

## @ManagedService

The same `@ManagedService` annotation works identically in Quarkus:

```java
@ManagedService(path = "/atmosphere/chat",
                atmosphereConfig = MAX_INACTIVE + "=120000")
public class Chat {

    @Inject
    @Named("/atmosphere/chat")
    private Broadcaster broadcaster;

    @Inject
    private AtmosphereResource r;

    @Inject
    private AtmosphereResourceEvent event;

    @Heartbeat
    public void onHeartbeat(final AtmosphereResourceEvent event) {
        logger.trace("Heartbeat from {}", event.getResource());
    }

    @Ready
    public void onReady() {
        logger.info("Browser {} connected", r.uuid());
    }

    @Disconnect
    public void onDisconnect() {
        if (event.isCancelled()) {
            logger.info("Browser {} unexpectedly disconnected",
                event.getResource().uuid());
        } else if (event.isClosedByClient()) {
            logger.info("Browser {} closed the connection",
                event.getResource().uuid());
        }
    }

    @Message(encoders = {JacksonEncoder.class},
             decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) {
        logger.info("{} just sent {}", message.getAuthor(), message.getMessage());
        return message;
    }
}
```

The `@Inject` annotations resolve through `QuarkusAtmosphereObjectFactory`, which delegates to Quarkus's Arc CDI container for application beans and to Atmosphere's own injection for framework objects (`AtmosphereResource`, `Broadcaster`, etc.).

## How the extension works

### Build-time processing (AtmosphereProcessor)

The `AtmosphereProcessor` (in the deployment module) runs `@BuildStep` methods during the Quarkus build:

1. **Feature registration**: Registers the `"atmosphere"` feature so `quarkus:info` lists it.

2. **Jandex indexing**: Adds `atmosphere-runtime` to the Jandex index via `IndexDependencyBuildItem`, so all Atmosphere annotations are discoverable at build time.

3. **Annotation scanning**: Scans the combined Jandex index for all Atmosphere annotations (`@ManagedService`, `@AtmosphereHandlerService`, `@BroadcasterFilterService`, `@RoomService`, etc.) and collects them into an `AtmosphereAnnotationsBuildItem`. This replaces the runtime classpath scanning that Atmosphere normally does.

4. **SCI suppression**: Suppresses both `AnnotationScanningServletContainerInitializer` and `ContainerInitializer` via `IgnoredServletContainerInitializerBuildItem`. The extension manages annotation scanning at build time via Jandex, and framework initialization via `QuarkusAtmosphereServlet`.

5. **Servlet registration**: Produces a `ServletBuildItem` for `QuarkusAtmosphereServlet` with the configured path, load-on-startup order, and init parameters.

6. **WebSocket endpoint registration**: Registers JSR-356 WebSocket endpoints with the Quarkus-managed `ServerWebSocketContainer` at `STATIC_INIT` time. This must happen before deployment is marked complete, since Quarkus's Undertow fork rejects `addEndpoint()` after deployment.

7. **Reflection registration**: Registers all discovered annotated classes plus Atmosphere core classes for reflection (required for GraalVM native image support).

8. **Encoder/Decoder registration**: Scans `@Message` and `@Ready` annotations for `encoders()` and `decoders()` arrays and registers those classes for reflection.

### Runtime components

| Class | Role |
|-------|------|
| `QuarkusAtmosphereServlet` | Extends `AtmosphereServlet` to inject the pre-scanned annotation map |
| `AtmosphereServletInstanceFactory` | Quarkus servlet instance factory |
| `QuarkusAtmosphereObjectFactory` | Bridges Arc CDI to Atmosphere's object factory |
| `QuarkusJSR356AsyncSupport` | Custom async support extending `Servlet30CometSupport` with `supportWebSocket() == true` |
| `LazyAtmosphereConfigurator` | Defers JSR-356 `ServerEndpointConfig.Configurator` setup until the framework is initialized |
| `AtmosphereRecorder` | Quarkus `@Recorder` for runtime-init operations |

### Why QuarkusJSR356AsyncSupport?

The standard `JSR356AsyncSupport` calls `container.addEndpoint()` in its constructor, which fails in Quarkus with error UT003017 ("Cannot add endpoint after deployment"). The Quarkus extension registers endpoints at `STATIC_INIT` time instead, and uses `QuarkusJSR356AsyncSupport` (which extends `Servlet30CometSupport` and returns `true` from `supportWebSocket()`) as a drop-in replacement.

## Critical: loadOnStartup must be > 0

Quarkus's `UndertowDeploymentRecorder` skips `setLoadOnStartup()` when the value is `<= 0`. Unlike the Servlet spec where `>= 0` means "load on startup", Quarkus requires a value **strictly greater than 0** for the servlet to initialize at startup. The default is `1`.

If you set `quarkus.atmosphere.load-on-startup=0` or a negative value, the Atmosphere servlet will **not** be initialized at startup, and no connections will be accepted.

## Dev mode

The extension supports Quarkus dev mode (`quarkus:dev`) with live reload. The `AtmosphereRecorder` registers a shutdown hook via `ShutdownContextBuildItem` that resets the `LazyAtmosphereConfigurator` before each reload cycle, ensuring a fresh `CountDownLatch` and framework reference.

## Differences from Spring Boot

| Aspect | Spring Boot | Quarkus |
|--------|------------|---------|
| Annotation scanning | Runtime (Spring `ClassPathScanner`) | Build time (Jandex) |
| Config prefix | `atmosphere.*` | `quarkus.atmosphere.*` |
| Config binding | `@ConfigurationProperties` | `@ConfigMapping` + `@ConfigRoot` |
| Object factory | `SpringAtmosphereObjectFactory` | `QuarkusAtmosphereObjectFactory` |
| WebSocket registration | Handled by container | Explicit at `STATIC_INIT` |
| loadOnStartup | `0` works (Servlet spec) | Must be `> 0` (Quarkus quirk) |
| Config phase | Runtime | `BUILD_AND_RUN_TIME_FIXED` |
| SCI handling | Overridden via servlet context attribute | Suppressed via `IgnoredServletContainerInitializerBuildItem` |

## Running the sample

The `quarkus-chat` sample demonstrates a complete chat application:

```bash
cd samples/quarkus-chat
../../mvnw quarkus:dev
```

The chat UI is served from `src/main/resources/META-INF/resources/` and connects to the `@ManagedService` at `/atmosphere/chat`.
