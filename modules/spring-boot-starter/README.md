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
            <version>4.0.30</version>
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
| `atmosphere.heartbeat-interval-in-seconds` | (default) | Heartbeat interval |

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

When `atmosphere-mcp` is also on the classpath, an `McpTracing` bean is auto-created for MCP tool/resource/prompt call tracing.

### Micrometer Metrics (Auto-Configured)

When `micrometer-core` and `MeterRegistry` are on the classpath, `AtmosphereMetricsAutoConfiguration` registers `atmosphere.connections`, `atmosphere.messages`, and `atmosphere.broadcasters` gauges.

### Sample

See [Spring Boot OTel Chat](../../samples/spring-boot-otel-chat/) for a complete example with Jaeger.

## GraalVM Native Image

The starter includes `AtmosphereRuntimeHints` for native image support. Build with `mvn clean package -Pnative`.

## Sample

- [Spring Boot Chat](../../samples/spring-boot-chat/) -- rooms, presence, REST API, Micrometer metrics, Actuator health

## Full Documentation

See [docs/spring-boot.md](../../docs/spring-boot.md) for complete documentation.

## Requirements

- Java 21+
- Spring Boot 4.0+
