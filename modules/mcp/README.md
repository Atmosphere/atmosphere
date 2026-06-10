# Atmosphere MCP

MCP (Model Context Protocol) server module for Atmosphere. Exposes annotation-driven tools, resources, and prompt templates to AI agents over Streamable HTTP, WebSocket, or SSE transport. MCP is auto-registered when `atmosphere-mcp` is on the classpath — no `@McpServer` annotation needed on your `@Agent` class.

It is a **self-contained server implementation** (no external MCP SDK) that speaks two protocol generations side by side: the session-based wire protocol (`2024-11-05` through `2025-11-25`, negotiated via the `initialize` handshake) **and** the stateless **`2026-07-28` release candidate** — selected per request, so existing clients keep working unchanged. See [MCP 2026-07-28](#mcp-2026-07-28-release-candidate) below.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-mcp</artifactId>
    <version>${project.version}</version>
</dependency>
```

## Minimal Example

```java
@Agent(name = "my-tools", skillFile = "tools.md",
       description = "Tools for AI agents")
public class MyTools {

    @McpTool(name = "greet", description = "Say hello")
    public String greet(@McpParam(name = "name", required = true) String name) {
        return "Hello, " + name + "!";
    }

    @McpResource(uri = "atmosphere://server/status",
                 name = "Server Status",
                 description = "Current server status")
    public String serverStatus() {
        return "OK";
    }

    @McpPrompt(name = "summarize", description = "Summarize a topic")
    public List<McpMessage> summarize(@McpParam(name = "topic") String topic) {
        return List.of(
            McpMessage.system("You are a summarization expert."),
            McpMessage.user("Summarize: " + topic)
        );
    }
}
```

## Annotations

| Annotation | Target | Description |
|-----------|--------|-------------|
| `@McpTool` | Method | Exposes a method as a callable tool (`tools/call`) |
| `@McpResource` | Method | Exposes a method as a read-only resource (`resources/read`) |
| `@McpPrompt` | Method | Exposes a method as a prompt template (`prompts/get`) |
| `@McpParam` | Parameter | Annotates method parameters with name, description, and required flag |

## Supported Transports

| Transport | How to connect |
|-----------|---------------|
| Streamable HTTP (recommended) | `POST http://host:port/atmosphere/mcp` |
| WebSocket | `ws://host:port/atmosphere/mcp` |
| SSE | `GET http://host:port/atmosphere/mcp` |

Agents get automatic reconnection, heartbeats, and transport fallback from Atmosphere's transport layer.

## MCP 2026-07-28 (Release Candidate)

Atmosphere implements the **`2026-07-28`** MCP revision — the largest since launch —
as a **stateless dialect that coexists** with the session model. A request is served
by the stateless dialect when it carries the protocol version in `params._meta` or
calls `server/discover`; everything else (the `initialize` handshake) stays on the
session dialect. No flag day: every `2024-11-05 … 2025-11-25` client is unaffected.

| Capability | What it gives you |
|-----------|-------------------|
| **Stateless core** | No `Mcp-Session-Id`, no handshake — client info/capabilities/version ride `_meta` on every request, so the server runs behind a plain round-robin load balancer with no sticky sessions |
| **Operability headers** | `Mcp-Method` / `Mcp-Name` routing headers, validated against the body |
| **Cacheable list/read** | `ttlMs` + `cacheScope` (always `public` — catalogs/reads are not principal-specific) on `tools/list`, `resources/list`, `resources/read` results |
| **W3C trace context** | `traceparent` / `tracestate` / `baggage` read from `_meta` and bridged into the OpenTelemetry span (with `atmosphere-tracing`) |
| **Tasks extension** | `io.modelcontextprotocol/tasks` — a `@McpTool(longRunning = true)` call returns a task handle the client polls via `tasks/get` |
| **Multi-round-trip** | `InputRequiredResult` + base64 `requestState`: the server can ask the client for more input mid-call and resume statelessly |
| **JSON Schema 2020-12** | tool input schemas advertise the `2020-12` dialect (`$schema`) |
| **Extensions framework** | reverse-DNS capability map; Tasks and Apps register as official extensions |
| **MCP Apps** | tools advertise a `ui://` UI resource — see [MCP Apps](#mcp-apps-sep-1865) |
| **Authorization** | OAuth 2.0 Resource Server — RFC 9728 metadata + bearer-token validation (via a `TokenValidator`) or a framework-set principal; see [Authorization](#authorization-oauth-resource-server) |

The relevant `@McpTool` attributes:

| Attribute | Default | Purpose |
|-----------|---------|---------|
| `longRunning` | `false` | Run the call as a Tasks-extension task; the client gets a handle and polls `tasks/get` |
| `uiResource` | `""` | A `ui://` resource URI advertised in the tool's `_meta.ui.resourceUri` (MCP Apps) |
| `title` / `iconUrl` | `""` | Display title / icon (MCP `2025-06-18` / `2025-11-25`) |

> **Client scope (honest caveat):** this is the **server** track. The outbound
> `atmosphere-mcp-client` wraps the official MCP Java SDK and cannot yet negotiate the
> `2026-07-28` stateless model — that waits on upstream SDK support.

### MCP Apps (SEP-1865)

A `@McpTool(uiResource = "ui://…")` paired with an `@McpResource` whose `mimeType` is
`text/html;profile=mcp-app` becomes an **MCP App**: a host renders its HTML in a
sandboxed iframe. The bundled **Atmosphere console** is a working host — its **MCP Apps**
tab lists app tools, reads the `ui://` HTML over the stateless protocol, and renders it.
The **App Bridge** (JSON-RPC over `postMessage`) is **bidirectional**:

- **App → Host → Server** — the app calls server tools through the host; the host still
  runs them through the policy gateway, so an app inherits governance.
- **Host → App** — the app declares `appCapabilities.tools` and the host lists and calls
  the app's own registered tools.

For isolation the console renders apps through a **separate-origin sandbox proxy** when a
distinct origin is available (`atmosphere.mcp-sandbox-origin`, or the `localhost`↔
`127.0.0.1` sibling in dev), falling back to an opaque-origin sandboxed iframe otherwise.

### Authorization (OAuth Resource Server)

When enabled, the MCP server acts as an **OAuth 2.0 Resource Server**: it serves RFC 9728
protected-resource metadata at `/.well-known/oauth-protected-resource` and answers
unauthenticated requests with `401` + a `WWW-Authenticate` challenge pointing at it
(default-deny). A request is authenticated when **either**:

- a servlet resource-server filter has set the request principal — e.g. Spring Security
  `oauth2ResourceServer` validating a JWT against your issuer; **or**
- a configured `TokenValidator` accepts the `Authorization: Bearer` token. MCP loads the
  validator from the `org.atmosphere.auth.tokenValidator` init-param and validates the
  bearer token itself, so this works on any container — servlet, Spring Boot, and Quarkus
  (on the JVM) — with no framework-specific wiring.

Opt in via init params:

```properties
org.atmosphere.mcp.auth.resource=https://api.example.com/atmosphere/mcp
org.atmosphere.mcp.auth.authorizationServers=https://auth.example.com
org.atmosphere.mcp.auth.scopes=mcp:tools mcp:resources
# Validate bearer tokens with your own TokenValidator — or omit this and let a servlet
# security filter (e.g. Spring Security) set the request principal instead:
org.atmosphere.auth.tokenValidator=com.example.MyJwtTokenValidator
```

The flow is end-to-end tested on the embedded server, Spring Boot, and Quarkus (JVM).

## Connecting Clients

Works with Claude Desktop, VS Code Copilot, Cursor, and any MCP-compatible agent:

```json
{
  "mcpServers": {
    "my-server": { "url": "http://localhost:8080/atmosphere/mcp" }
  }
}
```

For clients that only support stdio, build the bridge JAR with `mvn package -Pstdio-bridge -DskipTests` and point the client at the resulting `atmosphere-mcp-*-stdio-bridge.jar`.

## Sample

- [Spring Boot MCP Server](../../samples/spring-boot-mcp-server/) -- tools, resources, and prompts with a React frontend

## Injectable Parameters

`@McpTool` methods can declare framework types as parameters. These are auto-injected at invocation time and excluded from the tool's JSON Schema (MCP clients never see them):

| Type | What's injected | Requires |
|------|----------------|----------|
| `Broadcaster` | `BroadcasterFactory.lookup(topic, true)` | A `@McpParam(name="topic")` argument in the call |
| `StreamingSession` | `BroadcasterStreamingSession` wrapping the topic's Broadcaster | A `@McpParam(name="topic")` argument + `atmosphere-ai` on classpath |
| `AtmosphereConfig` | The framework's `AtmosphereConfig` | Nothing |
| `BroadcasterFactory` | The framework's `BroadcasterFactory` | Nothing |
| `AtmosphereFramework` | The framework instance | Nothing |
| `McpInputContext` | Accumulated input from prior elicitation rounds (empty on the first call) | Nothing |

### Example: Push Messages to Browser Clients

```java
@Agent(name = "mcp-tools", description = "MCP tools with broadcaster injection")
public class MyMcpTools {

    @McpTool(name = "broadcast", description = "Send a message to a chat topic")
    public String broadcast(
            @McpParam(name = "message") String message,
            @McpParam(name = "topic") String topic,
            Broadcaster broadcaster) {
        broadcaster.broadcast(message);
        return "sent to " + topic;
    }
}
```

When an AI agent calls this tool with `{"message": "hello", "topic": "/chat"}`, the message is broadcast to all WebSocket/SSE/gRPC clients subscribed to `/chat`.

### Example: Stream LLM Texts to Browsers

With `atmosphere-ai` on the classpath, inject a `StreamingSession` that wraps the topic's Broadcaster:

```java
@McpTool(name = "ask_ai", description = "Ask AI and stream answer to a topic")
public String askAi(
        @McpParam(name = "question") String question,
        @McpParam(name = "topic") String topic,
        StreamingSession session) {
    // session.send() broadcasts to all clients on the topic
    Thread.startVirtualThread(() -> {
        var request = ChatCompletionRequest.builder(model).user(question).build();
        client.streamChatCompletion(request, session);
    });
    return "streaming to " + topic;
}
```

See [atmosphere-ai README](../ai/README.md) for more on `StreamingSession` and wire protocol.

## Bidirectional Tool Invocation

`BiDirectionalToolBridge` is an **opt-in primitive** (not auto-registered) for the **server to call tools on connected clients** (e.g., browser-side JavaScript functions) and receive results asynchronously. It complements the standard MCP flow (client calls server tools) with a reverse channel. You wire it yourself with `framework.addAtmosphereHandler(...)` as shown below; the framework does not register it for you. (The MCP Apps console host ships a separate, fully-wired bidirectional bridge over `postMessage` — see the Spring Boot starter's `McpApps.vue`.)

```java
var bridge = new BiDirectionalToolBridge();

// Call a client-side tool — returns a CompletableFuture
CompletableFuture<String> result = bridge.callClientTool(
        resource, "getLocation", Map.of("highAccuracy", true));

result.thenAccept(location -> log.info("Client location: {}", location));
```

### How It Works

1. `callClientTool()` generates a UUID, writes a JSON request to the client via `AtmosphereResource`, and returns a `CompletableFuture`
2. The client executes the tool and sends back a JSON response with the same ID
3. `ToolResponseHandler` (registered at `/_mcp/tool-response`) receives the response and completes the matching future

### Wire Protocol

**Server → Client** (tool call request):
```json
{"type":"tool_call","id":"uuid","name":"getLocation","args":{"highAccuracy":true}}
```

**Client → Server** (tool call response):
```json
{"id":"uuid","result":"40.7128,-74.0060"}
```

Or on error:
```json
{"id":"uuid","error":"Permission denied"}
```

### Configuration

| Constructor | Timeout |
|-------------|---------|
| `new BiDirectionalToolBridge()` | 30 seconds (default) |
| `new BiDirectionalToolBridge(Duration.ofSeconds(10))` | Custom timeout |

Timed-out calls complete exceptionally with `TimeoutException`. Error responses complete with `ToolCallException`.

### Registering the Handler

```java
framework.addAtmosphereHandler("/_mcp/tool-response",
    new ToolResponseHandler(bridge));
```

### Key Classes

| Class | Description |
|-------|-------------|
| `BiDirectionalToolBridge` | Core bridge — sends requests, tracks pending futures, completes on response |
| `ToolCallRequest` | Record: `id`, `name`, `args` with `toJson()` serialization |
| `ToolCallResponse` | Record: `id`, `result`, `error` with `fromJson()` parsing |
| `ToolResponseHandler` | `AtmosphereHandler` that routes client responses to the bridge |

## Observability

### OpenTelemetry Tracing

`McpTracing` wraps every `tools/call`, `resources/read`, and `prompts/get` invocation in an OTel trace span. Add `opentelemetry-api` to your classpath:

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <optional>true</optional>
</dependency>
```

Wire it programmatically:

```java
var tracing = new McpTracing(openTelemetry);
protocolHandler.setTracing(tracing);
```

With the Spring Boot starter, `McpTracing` is auto-configured when an `OpenTelemetry` bean is present.

**Span attributes:**

| Attribute | Description |
|---|---|
| `mcp.tool.name` | Tool/resource/prompt name |
| `mcp.tool.type` | `"tool"`, `"resource"`, or `"prompt"` |
| `mcp.tool.arg_count` | Number of arguments provided |
| `mcp.tool.error` | `true` if invocation failed |

## Tool-Call Governance

MCP `tools/call` admission requires `atmosphere-ai` on the classpath. When it is present, every MCP tool call flows through `PolicyAdmissionGate` (the same seam `@AiTool` dispatch uses), so YAML rules over `tool_name` apply to MCP invocations identically to first-party tools. Without `atmosphere-ai`, the gateway runs in open mode — it admits all calls and logs a startup `WARN` at class-load. `McpPolicyGateway.isActive()` reports the live state.

## Running on Quarkus

MCP servers also run on the Quarkus extension. Add `atmosphere-agent` and
`atmosphere-mcp` alongside `atmosphere-quarkus-extension`, and point the build
scan at your `@Agent` package:

```properties
quarkus.atmosphere.packages=com.example.mcp
```

The Quarkus build step recognizes `@Agent` and indexes the agent/mcp jars so the
endpoint, tools, and OAuth authorization register exactly as on Spring Boot
(proven by the extension's MCP-on-Quarkus authorization test).

> **JVM only.** Native image is not yet supported for `@Agent`-based MCP — the
> agent processor links optional sibling modules (e.g. AG-UI) at build time,
> which native-image analysis rejects when they are absent. No Quarkus MCP sample
> ships today; the capability is covered by the extension test suite.

## Full Documentation

See <https://atmosphere.github.io/docs/reference/mcp/> for complete documentation.

## Requirements

- Java 21+
- `atmosphere-runtime` (transitive)
