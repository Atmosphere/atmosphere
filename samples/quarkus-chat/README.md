# Atmosphere Chat — Quarkus

A real-time chat application on Quarkus. Uses `@ManagedService` for the server-side handler, served over WebSocket with long-polling fallback in JVM mode. The sample also builds as a GraalVM native image — see [Native Image](#native-image) for exactly what that lane verifies.

## What It Demonstrates

- **`@ManagedService`** annotation-driven handler with Jackson encoding/decoding
- **Quarkus extension** — build-time annotation scanning via Jandex, Arc CDI integration
- **GraalVM Native Image** — the `native` profile builds with no Atmosphere reflection configuration; [Native Image](#native-image) states the verified scope
- **WebSocket** with transparent long-polling fallback — JVM mode; the native lane exercises long-polling only
- **Zero configuration** — the extension auto-registers the servlet
- **Admin Control Plane** — live dashboard at `/admin/` with event stream, agent inspection, and operational controls

## Server Side

### Chat.java

Identical to the Spring Boot sample — the same handler works on both platforms:

```java
@ManagedService(path = "/atmosphere/chat", atmosphereConfig = MAX_INACTIVE + "=120000")
public class Chat {

    @Inject @Named("/atmosphere/chat") private Broadcaster broadcaster;
    @Inject private AtmosphereResource r;
    @Inject private AtmosphereResourceEvent event;

    @Heartbeat
    public void onHeartbeat(final AtmosphereResourceEvent event) { /* keep-alive observed */ }

    @Ready
    public void onReady() {
        logger.info("Browser {} connected (broadcaster: {})", r.uuid(), broadcaster.getID());
    }

    @Disconnect
    public void onDisconnect() { /* event.isCancelled() vs event.isClosedByClient() */ }

    @Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) throws IOException {
        return message; // returning broadcasts to all subscribers
    }
}
```

## Client Side

This sample ships no bespoke client. `src/main/resources/META-INF/resources/index.html` (Quarkus's static resources directory) is a 292-byte redirect to `/atmosphere/console/` — the Atmosphere Console, served by the admin extension and pointed at this sample's endpoint via `quarkus.atmosphere.console-endpoint`. Subscribe from the Console to exchange `{ author, message }` payloads over `/atmosphere/chat`.

## Configuration

### application.properties

```properties
quarkus.atmosphere.packages=org.atmosphere.samples.quarkus.chat
quarkus.atmosphere.console-subtitle=Multi-client broadcast chat on Quarkus
quarkus.atmosphere.console-endpoint=/atmosphere/chat
```

The extension also supports `quarkus.atmosphere.servlet-path`, `quarkus.atmosphere.session-support`, `quarkus.atmosphere.broadcaster-class`, and other properties — see the [Quarkus integration docs](https://atmosphere.github.io/docs/integrations/quarkus/) for details.

## Build & Run

```bash
# JVM mode
mvn clean package
java -jar target/quarkus-app/quarkus-run.jar

# Dev mode (live reload)
mvn quarkus:dev

# Native image (requires GraalVM JDK 21+ or Mandrel)
mvn clean package -Pnative
./target/atmosphere-quarkus-chat-*-runner

# Native via container build (no local GraalVM needed)
mvn clean package -Pnative -Dquarkus.native.container-build=true
```

Open http://localhost:8080/ in multiple browser tabs to chat.

Open http://localhost:8080/admin/ for the admin dashboard with live event stream and operational controls.

## Native Image

The `native` profile produces a GraalVM/Mandrel binary. Atmosphere needs no reflection configuration to get there:

- `atmosphere-runtime` ships `META-INF/native-image/org.atmosphere/atmosphere-runtime/reachability-metadata.json`, which GraalVM reads automatically.
- The Quarkus deployment processor additionally collects every `NativeImageMetadataProvider` on the classpath and feeds it to `ReflectiveClassBuildItem` / `NativeImageResourceBuildItem`, so modules such as `atmosphere-ai` and `atmosphere-agent` contribute their own reflective types.
- Annotated handlers are discovered from Quarkus's build-time Jandex index over `quarkus.atmosphere.packages` — a native image has no `.class` files to scan at runtime.

The one native-specific setting in `application.properties` is a `quarkus.native.additional-build-args` entry moving Netty's JNI-backed tcnative classes to run-time initialization. That is a Netty/TLS concern, not an Atmosphere one.

### What CI verifies

The `Quarkus Native Image` job in `.github/workflows/native-image-ci.yml` builds the binary with the Mandrel `jdk-21` builder image, waits for `/` to answer, then issues a long-polling `GET` to `/atmosphere/chat` and asserts this sample's `@Ready` log line (`connected (broadcaster:`) appears. Under native image that proves:

- the `@ManagedService` handler was discovered and registered,
- the long-polling transport served a real connection,
- `@Ready` ran, with its injected `AtmosphereResource` and `Broadcaster` resolved.

The job then drives `scripts/native/NativeTransportProbe.java` — a
zero-dependency JDK client — against the binary: two WebSocket clients prove
the upgrade path across the Vert.x handshake bridge, broadcaster fan-out to a
second subscriber, and the `JacksonEncoder`/`JacksonDecoder` `@Message`
round-trip; an SSE subscriber receives a message sent over a WebSocket.

### What CI does not verify

Nothing below is covered by any native lane. Do not assume it works natively until one exists:

- **Transport negotiation and fallback.** The probe pins each transport explicitly; negotiation is an atmosphere.js client behaviour.
- **`@Disconnect` and `@Heartbeat`.**
- **The `/admin/` dashboard and the Atmosphere Console.**

Those paths are covered in **JVM mode** by the Playwright suite (`modules/integration-tests/e2e/quarkus-chat.spec.ts` drives the Console through a real browser and asserts the server echo; `admin-quarkus.spec.ts` covers the dashboard).

### Declaring your own reflective types

If your application — or a library it depends on — loads classes by name, declare them through the `NativeImageMetadataProvider` SPI instead of hand-writing `reflect-config.json`. The Quarkus build step calls `NativeImageMetadata.collect(...)`, which merges every provider it finds, so a registered provider needs no further wiring:

```java
package org.atmosphere.samples.quarkus.chat;

import java.util.Collection;
import java.util.List;

import org.atmosphere.nativeimage.NativeImageMetadataProvider;

public class ChatNativeImageMetadataProvider implements NativeImageMetadataProvider {

    @Override
    public String name() {
        return "quarkus-chat";
    }

    @Override
    public Collection<String> reflectiveTypes() {
        return List.of("com.example.MyBroadcasterCache", "com.example.MyInterceptor");
    }

    @Override
    public Collection<String> resourcePatterns() {
        return List.of("META-INF/services/com.example.MyService");
    }
}
```

Register it in `src/main/resources/META-INF/services/org.atmosphere.nativeimage.NativeImageMetadataProvider`:

```
org.atmosphere.samples.quarkus.chat.ChatNativeImageMetadataProvider
```

Registration is a union across providers — no provider can suppress another's types. Override `isAvailable()` to return `false` when an optional dependency is absent, and `priority()` only to influence emission order.

## Project Structure

```
quarkus-chat/
├── pom.xml                                  # Quarkus 3.36.3 BOM
└── src/main/
    ├── java/org/atmosphere/samples/quarkus/chat/
    │   ├── Chat.java                        # @ManagedService handler
    │   ├── Message.java                     # Message POJO
    │   ├── JacksonEncoder.java              # Message → JSON
    │   └── JacksonDecoder.java              # JSON → Message
    └── resources/
        ├── application.properties           # Quarkus + Atmosphere config
        └── META-INF/resources/
            └── index.html                   # Redirect to /atmosphere/console/
```

> **Portability**: The `Chat.java` handler is identical across the [WAR](../chat/), [Spring Boot](../spring-boot-chat/), and Quarkus samples — only the packaging and configuration differ.
