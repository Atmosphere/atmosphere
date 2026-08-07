# Atmosphere Spring Boot Starter

Auto-configuration for running Atmosphere on Spring Boot 4.0+. Registers `AtmosphereServlet`, wires Spring DI into Atmosphere's object factory, and exposes `AtmosphereFramework` and `RoomManager` as Spring beans.

## Maven Coordinates

First, import the Atmosphere BOM in your `<dependencyManagement>` to align all module versions:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.atmosphere</groupId>
            <artifactId>atmosphere-bom</artifactId>
            <version>${project.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Then add the starter — no `<version>` needed:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-boot-starter</artifactId>
</dependency>
```

## Minimal Example

### application.yml

```yaml
atmosphere:
  packages: com.example.chat
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

No additional configuration is needed beyond a standard `@SpringBootApplication` class. The starter auto-registers the servlet, scans for Atmosphere annotations, and integrates with Spring's `ApplicationContext`.

## Configuration Properties

All properties are under the `atmosphere.*` prefix:

| Property | Default | Description |
|----------|---------|-------------|
| `atmosphere.packages` | (none) | Comma-separated packages to scan for Atmosphere annotations |
| `atmosphere.servlet-path` | `/atmosphere/*` | Servlet URL mapping |
| `atmosphere.session-support` | `false` | Enable HTTP session support |
| `atmosphere.websocket-support` | (auto) | Explicitly enable/disable WebSocket |
| `atmosphere.broadcaster-class` | (default) | Custom `Broadcaster` implementation |
| `atmosphere.heartbeat-interval` | (default) | Heartbeat interval (e.g. `60s`) |

## Auto-Configured Beans

- `AtmosphereServlet` -- the servlet instance
- `AtmosphereFramework` -- the framework for programmatic configuration
- `RoomManager` -- the room API for presence and message history
- `AtmosphereHealthIndicator` -- Actuator health check (when `spring-boot-health` is on the classpath)

## WebTransport over HTTP/3

The starter includes auto-configuration for a WebTransport sidecar server using Reactor Netty and the Netty HTTP/3 codec. When enabled, a secondary HTTP/3 server runs alongside the servlet container on a separate UDP port.

### Dependencies

Add `reactor-netty-http` (which transitively brings `netty-codec-http3`):

```xml
<dependency>
    <groupId>io.projectreactor.netty</groupId>
    <artifactId>reactor-netty-http</artifactId>
</dependency>
```

### Configuration

```yaml
atmosphere:
  web-transport:
    enabled: true
    port: 4443           # UDP port for HTTP/3
    host: 0.0.0.0
    add-alt-svc: true    # Advertise HTTP/3 via Alt-Svc header
    ssl:
      certificate: /path/to/cert.pem    # Optional — self-signed generated for dev
      private-key: /path/to/key.pem
```

### Auto-Configured Beans

- `ReactorNettyTransportServer` -- the HTTP/3 + WebTransport sidecar
- `SmartLifecycle` -- starts/stops the sidecar alongside the application
- `AltSvcFilter` -- adds `Alt-Svc: h3=":4443"; ma=86400` to HTTP responses
- `WebTransportInfoController` -- `GET /api/webtransport-info` returns port, enabled flag, and certificate hash

### Client Configuration

```typescript
const info = await fetch('/api/webtransport-info').then(r => r.json());
const request = {
  url: '/atmosphere/chat',
  transport: 'webtransport',
  fallbackTransport: 'websocket',
  webTransportUrl: `https://${location.hostname}:${info.port}/atmosphere/chat`,
  serverCertificateHashes: [info.certificateHash],
};
```

### Auth Note

Chrome strips query parameters from the WebTransport CONNECT `:path`. Auth tokens must use post-connection authentication (e.g., first message after connect), not query parameters.

## Zero-Code AI Chat

Add `atmosphere-ai` to your classpath, set an API key, and get a working AI chat with no Java code and no frontend code.

### Dependencies

With the [BOM](#maven-coordinates) imported, just add both dependencies:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-boot-starter</artifactId>
</dependency>
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai</artifactId>
</dependency>
```

### application.yml

```yaml
atmosphere:
  ai:
    api-key: ${GEMINI_API_KEY}
```

Start the app and open `http://localhost:8080/atmosphere/console/` — a built-in Vue chat UI connects to the auto-configured AI endpoint.

### AI Configuration Properties

All properties are under the `atmosphere.ai.*` prefix:

| Property | Default | Description |
|----------|---------|-------------|
| `atmosphere.ai.enabled` | `true` | Enable/disable AI auto-config |
| `atmosphere.ai.mode` | `remote` | `remote` (cloud API) or `local` (Ollama) |
| `atmosphere.ai.model` | `gemini-2.5-flash` | LLM model name |
| `atmosphere.ai.api-key` | — | API key (falls back to `LLM_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY` env vars) |
| `atmosphere.ai.base-url` | (auto) | Override API endpoint |
| `atmosphere.ai.path` | `/atmosphere/ai-chat` | Endpoint path |
| `atmosphere.ai.system-prompt` | `You are a helpful assistant.` | System prompt |
| `atmosphere.ai.system-prompt-resource` | — | Classpath resource for the prompt |
| `atmosphere.ai.conversation-memory` | `true` | Enable multi-turn conversation memory |
| `atmosphere.ai.max-history-messages` | `20` | Max messages retained per client |
| `atmosphere.ai.timeout` | `120000` | Suspend timeout (ms) |

### How It Works

1. `AtmosphereAiAutoConfiguration` activates when `atmosphere-ai` is on the classpath
2. LLM settings are configured from Spring properties with environment variable fallback
3. A startup hook checks for user-defined `@AiEndpoint` classes — if none exist, a default endpoint is registered at the configured path
4. The built-in Vue console at `/atmosphere/console/` connects via WebSocket and streams AI responses

### Customizing

Define your own `@AiEndpoint` to take full control — the default endpoint is automatically skipped:

```java
@AiEndpoint(path = "/atmosphere/ai-chat",
            systemPrompt = "You are a Java expert.",
            conversationMemory = true,
            tools = {MyTools.class})
public class MyAiChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

You can also provide your own `AiConfig.LlmSettings` bean to override all settings programmatically.

## Observability

### OpenTelemetry Tracing (Auto-Configured)

Add `opentelemetry-api` to your classpath and provide an `OpenTelemetry` bean — the starter automatically registers `AtmosphereTracing`:

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

Every Atmosphere request generates a trace span with transport, resource UUID, broadcaster, and action attributes. Disable with `atmosphere.tracing.enabled=false`.

When the corresponding protocol modules are on the classpath, `McpTracing`, `A2aTracing`, and `AgUiTracing` beans are exposed as Spring beans **and auto-attached** to their protocol handlers at framework startup (via an Atmosphere `startupHook`). MCP tool/resource/prompt calls, A2A skill calls, and AG-UI action calls therefore emit protocol-level spans out of the box whenever an `OpenTelemetry` bean is present — no manual `setTracing(...)` wiring is required. The attachment runs after handlers are registered, so any `McpHandler`, `A2aHandler`, or `AgUiHandler` mapped during startup is instrumented automatically.

### Micrometer Metrics (Auto-Configured)

When `micrometer-core` and `MeterRegistry` are on the classpath, `AtmosphereMetricsAutoConfiguration` registers `atmosphere.connections`, `atmosphere.messages`, and `atmosphere.broadcasters` gauges.

### Sample

See [Spring Boot OTel Chat](../../samples/spring-boot-otel-chat/) for a complete example with Jaeger.

## GraalVM Native Image

Atmosphere's annotation discovery runs ahead of time on this starter, and the
framework's reflectively-loaded types are registered from a service-loader SPI.
Build with `mvn clean package -Pnative`.

### What the Native CI Lane Asserts

The `spring-boot-native` job in `.github/workflows/native-image-ci.yml` builds
[Spring Boot Chat](../../samples/spring-boot-chat/) into a native binary, starts it,
opens a long-polling connection to its `@ManagedService(path = "/atmosphere/chat")`
endpoint, and fails unless the annotated `@Ready` method actually ran. Registration
is silent, so behaviour is the only honest thing to assert — and that one line can
only appear if the class was discovered, the handler registered, `@Inject` resolved
and the lifecycle fired. The Quarkus extension is covered by an equivalent lane.

**That assertion is the whole of what is verified.** The following are exercised by
no native lane. The mechanisms below register them, but nothing proves they work in
a native image — do not assume they do:

- WebSocket and SSE transports, and transport negotiation or fallback
- `@Message` encode/decode round-trips
- Rooms, `@RoomService`, presence, and broadcast fan-out
- The AI stack (`@AiEndpoint`, `@Agent`) — there is no native CI job for the AI sample
- Injection beyond what `@Ready` requires

This section is deliberately narrow. A flat "native compatible" claim once stood here
on the strength of a smoke test that only curled `/actuator/health`; it held for six
months while every annotated endpoint was silently skipped.

### How Discovery Works

A runtime classpath scan finds nothing in a native image — there are no `.class`
files left to read. Two build-time indexes replace it, both writing the same file,
`META-INF/atmosphere/annotated-classes.txt` (one fully-qualified class name per line;
blank lines and `#` comments are ignored):

- **Spring AOT** -- `AtmosphereAnnotationScanAotProcessor`, registered in
  `META-INF/spring/aot.factories`, runs the scan during AOT processing while a real
  classpath still exists. It honours `atmosphere.packages`, registers each annotated
  class for reflection, and additionally registers every type named by a class literal
  inside an Atmosphere annotation — `@Message(encoders = ..., decoders = ...)` most
  visibly. Without that last step the handler registers and then fails to load its
  encoder the moment a message arrives.
- **javac** -- `AtmosphereAnnotationIndexProcessor` ships inside `atmosphere-runtime`
  and is auto-discovered from the compile classpath, so any build tool or framework
  produces an index for its own artifact. Depending on `atmosphere-runtime` is the
  entire setup.

At startup the runtime reads **every** index on the classpath (`classpath*:`) and takes
the union of those results and the ordinary classpath scan. This is deliberately not
"prefer the index": a per-artifact index that short-circuited the scan would let one jar
hide every annotated class contributed by every other jar.

### Contributing Reflective Types

`AtmosphereRuntimeHints` carries no hardcoded list. It calls
`NativeImageMetadata.collect(...)`, which merges every `NativeImageMetadataProvider`
on the classpath — the same SPI the Quarkus build step consumes, so the integrations
cannot drift apart. A module that loads a class by name declares it next to the code
doing the lookup:

```java
package com.example.nativeimage;

import org.atmosphere.nativeimage.NativeImageMetadataProvider;

import java.util.Collection;
import java.util.List;

public class ExampleNativeImageMetadataProvider implements NativeImageMetadataProvider {

    @Override
    public String name() {
        return "example-module";
    }

    @Override
    public Collection<String> reflectiveTypes() {
        return List.of("com.example.ExampleBroadcasterCache");
    }

    @Override
    public Collection<String> resourcePatterns() {
        return List.of("META-INF/services/com.example.ExampleService");
    }
}
```

Register it in `META-INF/services/org.atmosphere.nativeimage.NativeImageMetadataProvider`:

```
com.example.nativeimage.ExampleNativeImageMetadataProvider
```

Types are named as strings rather than `Class` literals so a provider can declare a
type from an optional dependency without forcing that dependency to be present.
Override `isAvailable()` to return `false` when the dependency is absent, and those
types are left out of an image that could not contain them. Collection is a union:
`priority()` only orders the emitted output, and no provider can suppress another's
entries.

The same providers generate
`META-INF/native-image/org.atmosphere/atmosphere-runtime/reachability-metadata.json`,
which ships inside `atmosphere-runtime` and is read by GraalVM automatically — a
plain-servlet or embedded deployment needs no integration module and no configuration.

## Sample

- [Spring Boot Chat](../../samples/spring-boot-chat/) -- rooms, presence, REST API, Micrometer metrics, Actuator health

## Full Documentation

See <https://atmosphere.github.io/docs/integrations/spring-boot/> for complete documentation.

## Requirements

- Java 21+
- Spring Boot 4.0+
