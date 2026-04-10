# Atmosphere MCP

MCP (Model Context Protocol) server module for Atmosphere. Exposes annotation-driven tools, resources, and prompt templates to AI agents over Streamable HTTP, WebSocket, or SSE transport. MCP is auto-registered when `atmosphere-mcp` is on the classpath — no `@McpServer` annotation needed on your `@Agent` class.

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

`BiDirectionalToolBridge` enables the **server to call tools on connected clients** (e.g., browser-side JavaScript functions) and receive results asynchronously. This complements the standard MCP flow (client calls server tools) with a reverse channel.

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

## Full Documentation

See <https://atmosphere.github.io/docs/reference/mcp/> for complete documentation.

## Requirements

- Java 21+
- `atmosphere-runtime` (transitive)
