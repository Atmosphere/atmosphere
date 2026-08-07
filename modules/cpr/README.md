# Atmosphere Runtime

The core framework for building real-time web applications in Java. Provides a portable, annotation-driven programming model that runs on any Servlet 6.0+ container with automatic transport negotiation.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-runtime</artifactId>
    <version>${project.version}</version>
</dependency>
```

## Key Features

- **Transports** — WebSocket, SSE, Long-Polling, and gRPC with automatic fallback negotiation; WebTransport/HTTP-3 is optional (requires the optional `jetty-http3-server` dependency)
- **`@ManagedService`** annotation-driven endpoints with `@Ready`, `@Disconnect`, `@Message`
- **Rooms** -- `RoomManager`, `@RoomService`, presence tracking, message history
- **Virtual threads** enabled by default (JDK 21+)
- **Broadcasting** -- pub/sub via `Broadcaster` and `BroadcasterFactory`
- **Micrometer and OpenTelemetry** observability (optional)
- **GraalVM Native Image** — the jar ships its own reachability metadata, and CI
  drives long-polling, WebSocket, SSE, the `@Message` codec round-trip,
  room-protocol fan-out and AI dispatch against real native binaries on
  Spring Boot 4 and Quarkus — see [GraalVM Native Image](#graalvm-native-image).

## Minimal Example

```java
@ManagedService(path = "/chat")
public class Chat {

    @Inject
    private BroadcasterFactory factory;

    @Inject
    private AtmosphereResource r;

    @Ready
    public void onReady() {
        // client connected
    }

    @Disconnect
    public void onDisconnect() {
        // client left
    }

    @Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) {
        return message; // broadcasts to all subscribers
    }
}
```

## Configuration

Register `AtmosphereServlet` in `web.xml` or programmatically:

```xml
<servlet>
    <servlet-class>org.atmosphere.cpr.AtmosphereServlet</servlet-class>
    <init-param>
        <param-name>org.atmosphere.cpr.packages</param-name>
        <param-value>com.example.chat</param-value>
    </init-param>
    <load-on-startup>0</load-on-startup>
    <async-supported>true</async-supported>
</servlet>
```

## GraalVM Native Image

Native support is not one switch: reflective registration and annotation discovery
are separate problems with separate answers, and only some paths are covered by CI.

### What CI verifies

`.github/workflows/native-image-ci.yml` builds three real native binaries — the
Spring Boot 4 sample (`samples/spring-boot-chat`), the Spring Boot AI sample
(`samples/spring-boot-ai-chat`) and the Quarkus sample (`samples/quarkus-chat`).
The two chat lanes start their binary, open a long-polling connection to the
`@ManagedService` at `/atmosphere/chat`, and fail the build unless the log shows
the annotated `@Ready` method ran — an assertion that passes only if the
annotated class was discovered, the handler registered, `@Inject` resolved and
the lifecycle fired. Each then drives `scripts/native/NativeTransportProbe.java`
against the running binary: two WebSocket clients prove fan-out and the
`@Message` encoder/decoder round-trip, an SSE subscriber receives a message sent
over a WebSocket, and (on the Spring Boot lane) the room protocol runs
join/join_ack, presence fan-out and a room broadcast. The AI lane dispatches a
real agent turn through `/atmosphere/v1/chat/completions` keylessly and asserts
assistant content came back.

### What is not verified

No CI lane asserts any of the following under Native Image, so do not assume it works:

- Transport negotiation and fallback — the probe pins each transport explicitly;
  negotiation is an atmosphere.js client behaviour
- `@RoomService`-annotated endpoints — the probe drives the room *protocol*
  against a programmatic `RoomManager`; no native sample declares `@RoomService`
- History replay on join, `@Disconnect` and `@Heartbeat`
- gRPC — registered for reflection, but no lane builds it natively
- Injection beyond what the samples use

### Reflection metadata

`atmosphere-runtime` ships
`META-INF/native-image/org.atmosphere/atmosphere-runtime/reachability-metadata.json`
(generated from the SPI below). GraalVM reads
it automatically, so a plain-servlet or embedded-Jetty native build needs no
integration module and no configuration to get the framework's reflective
registrations — including the broadcaster caches, which are loaded by name and whose
absence makes every `@ManagedService` endpoint fail to register silently.

That covers reflection only. Annotation discovery is a separate matter.

### Annotation discovery

A native image has no `.class` files to scan, so how annotated handlers are found
depends on the deployment:

| Deployment | How handlers are found under native | Verified in CI |
|---|---|---|
| Spring Boot 4 starter | Build-time AOT processor writes an index; the starter merges every `classpath*:META-INF/atmosphere/annotated-classes.txt` with the classpath scan | Yes |
| Quarkus extension | Build step supplies the annotation map from the Jandex index | Yes |
| Spring Boot 3 starter | Reflection metadata only — no index reader, so discovery is still the classpath scan | No |
| Plain servlet / embedded Jetty | Reflection metadata only — `DefaultAnnotationProcessor` uses the container-supplied `ServletContainerInitializer` map or a bytecode scan, and neither survives a native image | No |

On the last two, register handlers programmatically when targeting native:

```java
framework.addAtmosphereHandler("/chat", handler);
```

The Spring Boot 4 merge is a union, deliberately not "index wins": indexes are
per-artifact, so letting one short-circuit the scan would let a single jar's partial
index hide every class in jars that have none.

### Declaring metadata from another module

Classes loaded by name cannot be inferred by static analysis. A module declares its
own next to the code that does the lookup, through the `NativeImageMetadataProvider`
ServiceLoader SPI:

```java
package com.example.nativeimage;

import java.util.Collection;
import java.util.List;

import org.atmosphere.nativeimage.NativeImageMetadataProvider;

public class ExampleMetadataProvider implements NativeImageMetadataProvider {

    @Override
    public String name() {
        return "example-module";
    }

    @Override
    public Collection<String> reflectiveTypes() {
        return List.of("com.example.ExampleInterceptor");
    }

    @Override
    public Collection<String> resourcePatterns() {
        return List.of("META-INF/services/com.example.ExamplePlugin");
    }

    @Override
    public boolean isAvailable() {
        // Skip these registrations when the optional dependency is absent.
        try {
            Class.forName("com.example.OptionalDependency", false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
```

Register it in
`src/main/resources/META-INF/services/org.atmosphere.nativeimage.NativeImageMetadataProvider`:

```
com.example.nativeimage.ExampleMetadataProvider
```

Only `name()` is required — `reflectiveTypes()`, `resourcePatterns()`, `isAvailable()`
and `priority()` all have defaults. `NativeImageMetadata.collect()` merges every
available provider: registration is a union, so no provider can suppress another's
types, and a provider that throws is logged at WARN and skipped rather than failing
the build. `atmosphere-runtime` registers two providers of its own, and the merged
result is consumed by the Spring Boot 4 starter, the Spring Boot 3 starter and the
Quarkus deployment step.

### Recording annotated classes at compile time

`AtmosphereAnnotationIndexProcessor` is a javac annotation processor that javac picks
up automatically from `atmosphere-runtime` on the compile classpath — depending on the
runtime is the whole setup, in any build tool. It records every class carrying one of
the framework's core annotations into `META-INF/atmosphere/annotated-classes.txt` in
that artifact's output, and does not claim the annotations, so other processors still
see them.

Writing the index is universal; reading it is not. The Spring Boot 4 starter is
currently the only consumer — the core runtime's `DefaultAnnotationProcessor` has no
index path, and still chooses between the `ServletContainerInitializer` map and a
bytecode scan.

`atmosphere-runtime` itself compiles with `<proc>none</proc>`, since javac cannot run a
processor while compiling the module that defines it; it ships a committed index of its
22 annotation processors instead, kept in sync by a gate test.

## Observability

### OpenTelemetry Tracing

`AtmosphereTracing` is an interceptor that creates OTel trace spans for every Atmosphere request:

```java
OpenTelemetry otel = GlobalOpenTelemetry.get();
framework.interceptor(new AtmosphereTracing(otel));
```

**Span attributes:**

| Attribute | Description |
|---|---|
| `atmosphere.resource.uuid` | Resource UUID |
| `atmosphere.transport` | Transport type (WEBSOCKET, SSE, LONG_POLLING) |
| `atmosphere.action` | Action result (CONTINUE, SUSPEND, RESUME) |
| `atmosphere.broadcaster` | Broadcaster ID |
| `atmosphere.disconnect.reason` | Disconnect reason (if applicable) |

With Spring Boot, this is auto-configured — see the [spring-boot-starter](../spring-boot-starter/) module.

### Micrometer Metrics

```java
AtmosphereMetrics.install(framework, meterRegistry);
```

Registers the `atmosphere.connections.*` (active/total/disconnects), `atmosphere.messages.*` (broadcast/delivered), and `atmosphere.broadcasters.active` gauges/counters.

## Samples

- [WAR Chat](../../samples/chat/) -- standard WAR deployment with `@ManagedService`
- [Embedded Jetty WebSocket Chat](../../samples/embedded-jetty-websocket-chat/) -- programmatic Jetty with `@WebSocketHandlerService`

## Requirements

- Java 21+
- Servlet 6.0+ container (Jetty 12, Tomcat 11, Undertow, etc.)

## Full Documentation

See <https://atmosphere.github.io/docs/reference/core/> for complete documentation.

## Building

```bash
./mvnw install -pl modules/cpr
```
