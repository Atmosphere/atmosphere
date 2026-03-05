---
title: "Chapter 15: Quarkus Integration"
description: "Run Atmosphere as a Quarkus extension with build-time annotation scanning, Arc CDI, dev mode live reload, and GraalVM native image compilation."
---

Quarkus takes a fundamentally different approach from Spring Boot: it shifts as much work as possible to build time. The `atmosphere-quarkus-extension` embraces this by scanning for Atmosphere annotations with Jandex at build time, registering the servlet and WebSocket endpoints during the augmentation phase, and producing reflection metadata for native images automatically.

This chapter walks through configuration, the build-time pipeline, CDI integration, dev mode, and native image compilation.

## Prerequisites

- JDK 21+
- Quarkus **3.21+**
- A Quarkus application (or we will create one)

## Adding the Extension

Add the extension and its required companions to your `pom.xml`:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-bom</artifactId>
            <version>3.21.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- The Atmosphere Quarkus extension (runtime module) -->
    <dependency>
        <groupId>org.atmosphere</groupId>
        <artifactId>atmosphere-quarkus-extension</artifactId>
        <version>4.0.10</version>
    </dependency>

    <!-- Quarkus Undertow (servlet container) -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-undertow</artifactId>
    </dependency>

    <!-- Quarkus WebSockets (provides the JSR-356 container) -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-websockets</artifactId>
    </dependency>

    <!-- Jackson for JSON encoding/decoding (if you use Jackson encoders) -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-jackson</artifactId>
    </dependency>
</dependencies>
```

You also need the Quarkus Maven plugin:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-maven-plugin</artifactId>
            <version>3.21.0</version>
            <extensions>true</extensions>
            <executions>
                <execution>
                    <goals>
                        <goal>build</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

## application.properties Configuration

Quarkus extensions use the `quarkus.` prefix. Atmosphere's configuration lives under `quarkus.atmosphere.*`:

```properties
# Comma-separated packages to scan (user packages are scanned via Jandex at build time)
quarkus.atmosphere.packages=com.example.realtime

# URL pattern for the Atmosphere servlet
quarkus.atmosphere.servlet-path=/atmosphere/*

# Servlet load-on-startup order -- MUST be > 0 (see gotcha below)
quarkus.atmosphere.load-on-startup=1

# Enable HTTP session tracking
quarkus.atmosphere.session-support=false

# Override the broadcaster class
quarkus.atmosphere.broadcaster-class=org.atmosphere.cpr.DefaultBroadcaster

# Override the broadcaster cache class
# quarkus.atmosphere.broadcaster-cache-class=

# WebSocket support (auto-detected if not set)
# quarkus.atmosphere.websocket-support=true

# Heartbeat interval in seconds
# quarkus.atmosphere.heartbeat-interval-in-seconds=60

# Additional init parameters
quarkus.atmosphere.init-params.org.atmosphere.cpr.maxInactiveActivity=120000
```

### The load-on-startup Gotcha

This is the single most common misconfiguration. In the Servlet specification, `loadOnStartup >= 0` means "initialize this servlet at startup." But Quarkus's Undertow integration uses `ifle` (less-than-or-equal) to skip initialization when the value is `<= 0`. This means:

- `load-on-startup=0` -- Quarkus **skips** servlet initialization (broken)
- `load-on-startup=1` -- Quarkus initializes the servlet at startup (correct)
- `load-on-startup=-1` -- Quarkus skips initialization (broken)

The extension defaults to `1`. If you change it, keep it greater than zero.

## Build-Time Annotation Scanning with Jandex

Unlike Spring Boot where annotation scanning happens at runtime, Quarkus scans at build time using Jandex (a compact annotation index). The `AtmosphereProcessor` (a Quarkus deployment-time class) performs these steps:

1. **Index the atmosphere-runtime JAR** via `IndexDependencyBuildItem`, so Jandex can see Atmosphere's own annotation processors
2. **Scan the combined Jandex index** for all Atmosphere annotations (`@ManagedService`, `@AtmosphereHandlerService`, `@WebSocketHandlerService`, etc.)
3. **Build an annotation map** (`Map<String, List<String>>`) mapping annotation names to class names
4. **Pass the map to the `AtmosphereRecorder`** which injects it into the servlet's `InstanceFactory` at static init time
5. **Register reflection hints** for all discovered classes (so they work in native images)
6. **Register encoder/decoder classes** found in `@Message(encoders=...)` and `@Ready(encoders=...)` annotations

The result: your `@ManagedService` classes are discovered without any runtime classpath scanning. The annotation map is baked into the application at build time.

### What Gets Suppressed

The processor suppresses both of Atmosphere's standard `ServletContainerInitializer` implementations:

- `AnnotationScanningServletContainerInitializer` -- replaced by Jandex scanning
- `ContainerInitializer` -- would create a duplicate `AtmosphereFramework`

This is done via `IgnoredServletContainerInitializerBuildItem` to prevent duplicate initialization.

## Arc CDI Integration

The `QuarkusAtmosphereObjectFactory` integrates with Quarkus's CDI container (Arc). When Atmosphere creates an instance of your class:

1. It first checks Arc for a CDI bean of that type
2. If found, returns the CDI-managed instance (with full injection, interceptors, etc.)
3. If not found, falls back to Atmosphere's `InjectableObjectFactory`

This means your `@ManagedService` classes can inject CDI beans using `@Inject`:

```java
@ManagedService(path = "/atmosphere/chat")
public class Chat {

    // Atmosphere-managed objects
    @Inject
    private BroadcasterFactory factory;

    @Inject
    private AtmosphereResource r;

    @Inject
    private AtmosphereResourceEvent event;

    // CDI beans from your application
    @Inject
    private ChatRepository chatRepository;

    @Ready
    public void onReady() {
        logger.info("Browser {} connected", r.uuid());
    }

    @Disconnect
    public void onDisconnect() {
        if (event.isCancelled()) {
            logger.info("Browser {} unexpectedly disconnected", event.getResource().uuid());
        } else if (event.isClosedByClient()) {
            logger.info("Browser {} closed the connection", event.getResource().uuid());
        }
    }

    @Message(encoders = JacksonEncoder.class, decoders = JacksonDecoder.class)
    public Message onMessage(Message message) {
        chatRepository.save(message);
        return message;
    }
}
```

Note that `@ManagedService` classes are not CDI beans themselves (they are created by Atmosphere's object factory). The CDI lookup is a bridge: the factory asks Arc "do you have a bean of this type?" If yes, it uses Arc's instance. If not, it creates one via reflection and lets Atmosphere's injector handle `@Inject` fields for framework objects.

## WebSocket Endpoint Registration

Quarkus's Undertow fork does not support adding WebSocket endpoints after deployment (unlike standard Servlet containers). Atmosphere's `JSR356AsyncSupport` would normally call `container.addEndpoint()` during servlet init, which fails with error `UT003017: Cannot add endpoint after deployment`.

The extension works around this in two ways:

1. **At STATIC_INIT** (build time): The `AtmosphereRecorder.registerWebSocketEndpoints()` registers JSR-356 `ServerEndpointConfig` entries with the `ServerWebSocketContainer` before the deployment is marked complete. These endpoints use `LazyAtmosphereConfigurator` which waits for the framework to initialize.

2. **At runtime**: A custom `QuarkusJSR356AsyncSupport` replaces the standard `JSR356AsyncSupport`. It extends `Servlet30CometSupport` with `supportWebSocket()` returning `true`, but does not attempt to register endpoints (they are already registered).

This is configured automatically via init parameters -- you do not need to do anything.

## A Complete Chat Application

Here is a minimal Quarkus chat application.

`application.properties`:

```properties
quarkus.atmosphere.packages=com.example.chat
quarkus.log.category."org.atmosphere".level=DEBUG
```

`Chat.java`:

```java
package com.example.chat;

import java.io.IOException;

import jakarta.inject.Inject;

import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Heartbeat;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.BroadcasterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

@ManagedService(path = "/atmosphere/chat",
                atmosphereConfig = MAX_INACTIVE + "=120000")
public class Chat {

    private final Logger logger = LoggerFactory.getLogger(Chat.class);

    @Inject
    private BroadcasterFactory factory;

    @Inject
    private AtmosphereResource r;

    @Inject
    private AtmosphereResourceEvent event;

    @Heartbeat
    public void onHeartbeat(AtmosphereResourceEvent event) {
        logger.trace("Heartbeat from {}", event.getResource());
    }

    @Ready
    public void onReady() {
        logger.info("Browser {} connected", r.uuid());
        logger.info("BroadcasterFactory used {}", factory.getClass().getName());
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

    @Message(encoders = JacksonEncoder.class,
             decoders = JacksonDecoder.class)
    public Message onMessage(Message message) throws IOException {
        logger.info("{} just sent {}", message.getAuthor(), message.getMessage());
        return message;
    }
}
```

`Message.java`:

```java
package com.example.chat;

public class Message {
    private String message;
    private String author;
    private long time;

    public Message() {}

    public Message(String author, String message) {
        this.author = author;
        this.message = message;
        this.time = System.currentTimeMillis();
    }

    // getters and setters
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public long getTime() { return time; }
    public void setTime(long time) { this.time = time; }
}
```

Run in dev mode:

```bash
./mvnw quarkus:dev
```

Connect to `ws://localhost:8080/atmosphere/chat`.

## Dev Mode with Live Reload

Quarkus dev mode (`mvn quarkus:dev`) supports live reload. When you modify a Java source file, Quarkus recompiles and redeploys the application.

The Atmosphere extension registers a shutdown hook via `AtmosphereRecorder.registerShutdownHook()` that resets the `LazyAtmosphereConfigurator` between reloads. This ensures:

- The `CountDownLatch` from the previous deployment is replaced with a fresh one
- The framework reference from the previous deployment is cleared
- WebSocket endpoints get a clean configurator for the new deployment

Live reload works transparently. Save a file, and the next request triggers a reload.

## GraalVM Native Image

The Atmosphere extension registers all necessary reflection and resource hints at build time:

- **All annotated classes** found via Jandex are registered for reflection
- **Encoder/decoder classes** from `@Message` and `@Ready` annotations
- **Core framework classes** (60+ classes including interceptors, factories, and processors)
- **ServiceLoader resources** (`META-INF/services/org.atmosphere.inject.Injectable`, etc.)
- **Runtime-initialized classes** (`ExecutorsFactory` is marked for runtime init to avoid capturing threads in the image)

The deferred init pattern ensures the framework initializes at runtime, not at build time:

1. During `STATIC_INIT`: The servlet is created and configured, but `framework.init()` is deferred
2. During `RUNTIME_INIT`: `AtmosphereRecorder.performDeferredInit()` triggers the actual initialization

To build a native image, activate the `native` profile:

```bash
./mvnw package -Pnative
```

Or set the properties directly:

```bash
./mvnw package -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false
```

Run the native executable:

```bash
./target/my-quarkus-app-1.0-runner
```

Add the native profile to your `pom.xml`:

```xml
<profiles>
    <profile>
        <id>native</id>
        <activation>
            <property>
                <name>native</name>
            </property>
        </activation>
        <properties>
            <quarkus.native.enabled>true</quarkus.native.enabled>
            <quarkus.package.jar.enabled>false</quarkus.package.jar.enabled>
        </properties>
    </profile>
</profiles>
```

## Configuration Reference

All configuration properties with their defaults:

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.atmosphere.servlet-path` | `/atmosphere/*` | Servlet URL mapping |
| `quarkus.atmosphere.packages` | (none) | Packages to scan for annotations |
| `quarkus.atmosphere.load-on-startup` | `1` | Servlet load order (must be > 0) |
| `quarkus.atmosphere.session-support` | `false` | Enable HTTP session tracking |
| `quarkus.atmosphere.broadcaster-class` | (none) | Custom broadcaster FQCN |
| `quarkus.atmosphere.broadcaster-cache-class` | (none) | Custom broadcaster cache FQCN |
| `quarkus.atmosphere.websocket-support` | (auto) | Force WebSocket on/off |
| `quarkus.atmosphere.heartbeat-interval-in-seconds` | (default) | Heartbeat interval |
| `quarkus.atmosphere.init-params.*` | (none) | Arbitrary init parameters |

All properties use `BUILD_AND_RUN_TIME_FIXED` phase, meaning they are set at build time and cannot be changed at runtime. This is required because the `@BuildStep` methods that produce `ServletBuildItem` need access to these values during augmentation.

## Differences from Spring Boot

| Aspect | Spring Boot | Quarkus |
|--------|------------|---------|
| Annotation scanning | Runtime (Spring classpath scanner) | Build time (Jandex) |
| DI integration | `SpringAtmosphereObjectFactory` | `QuarkusAtmosphereObjectFactory` (Arc) |
| Config prefix | `atmosphere.*` | `quarkus.atmosphere.*` |
| Config phase | Runtime | Build-and-run-time fixed |
| Health indicator | Auto-configured | Not built-in (use SmallRye Health) |
| Metrics | Micrometer auto-configured | Manual (or use SmallRye Metrics) |
| Tracing | OTel auto-configured | Manual (or use SmallRye OpenTelemetry) |
| Native image | `RuntimeHintsRegistrar` | `ReflectiveClassBuildItem` + `NativeImageResourceBuildItem` |
| Servlet container | Embedded Tomcat/Jetty | Undertow (Quarkus fork) |
| WebSocket registration | Standard `container.addEndpoint()` | Pre-registered at STATIC_INIT |
| load-on-startup=0 | Works | Broken (Quarkus bug -- use > 0) |

## Troubleshooting

### "Cannot add endpoint after deployment" (UT003017)

This happens if the extension's WebSocket registration step did not run. Verify that:
- `atmosphere-quarkus-extension` is in your dependencies (not just `atmosphere-runtime`)
- The deployment module is being processed (check for `atmosphere` in the Quarkus build log)

### No handlers registered

Check that `quarkus.atmosphere.packages` is set correctly and that your `@ManagedService` classes are in the indexed packages. Run with `quarkus.log.category."org.atmosphere".level=DEBUG` to see the annotation scan results.

### Servlet not initialized

Verify `quarkus.atmosphere.load-on-startup` is greater than 0. The default is 1, but if you override it to 0 or -1, the servlet will never initialize.

## Sample Application

The `samples/quarkus-chat` directory contains a complete working chat application:

```bash
cd samples/quarkus-chat
../../mvnw quarkus:dev
```

## Summary

The Quarkus extension gives you:

- **Build-time annotation scanning** -- Jandex finds your classes during augmentation, not at runtime
- **Arc CDI integration** -- `@Inject` works for both CDI beans and Atmosphere-managed objects
- **Native image support** -- reflection hints generated automatically from the Jandex scan
- **Dev mode live reload** -- save and refresh, the framework re-initializes cleanly
- **Zero runtime classpath scanning** -- faster startup, smaller memory footprint

Next up: [Chapter 16: Clustering with Redis and Kafka](/docs/tutorial/16-clustering/) shows how to scale Atmosphere across multiple nodes.
