---
title: "MCP Server"
description: "Expose tools, resources, and prompts to AI agents with @McpServer annotations"
sidebar:
  order: 13
---

The Model Context Protocol (MCP) is an open standard that lets AI agents (Claude Desktop, VS Code Copilot, Cursor, etc.) discover and call tools, read resources, and use prompt templates from external servers. Atmosphere's `atmosphere-mcp` module lets you build an MCP server with annotations, backed by the same Broadcaster infrastructure that powers your real-time application.

**Module:** `atmosphere-mcp`
**Package:** `org.atmosphere.mcp`

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-mcp</artifactId>
    <version>LATEST</version> <!-- check Maven Central for latest -->
</dependency>
```

## Annotations

Five annotations define an MCP server:

### @McpServer

Marks a class as an MCP server endpoint. Methods within it can be annotated with `@McpTool`, `@McpResource`, and `@McpPrompt`.

```java
@McpServer(name = "my-server", version = "1.0.0", path = "/atmosphere/mcp")
public class MyMcpServer { ... }
```

| Attribute | Default | Description |
|-----------|---------|-------------|
| `name` | (required) | Server name reported during MCP initialization |
| `version` | `"1.0.0"` | Server version reported during initialization |
| `path` | `"/mcp"` | Atmosphere endpoint path for this MCP server |

### @McpTool

Marks a method as an MCP tool, invocable by MCP clients via `tools/call`:

```java
@McpTool(name = "search", description = "Search the knowledge base")
public List<Result> search(
        @McpParam(name = "query", required = true) String query) {
    return knowledgeBase.search(query);
}
```

| Attribute | Default | Description |
|-----------|---------|-------------|
| `name` | (required) | Tool name as reported to MCP clients |
| `description` | `""` | Human-readable description of what the tool does |

### @McpResource

Marks a method as an MCP resource, providing read-only data accessible via `resources/read`:

```java
@McpResource(uri = "atmosphere://rooms/{roomId}/history",
             name = "Room History",
             description = "Chat message history for a room",
             mimeType = "application/json")
public String roomHistory(@McpParam(name = "roomId") String roomId) {
    return roomService.getHistory(roomId);
}
```

| Attribute | Default | Description |
|-----------|---------|-------------|
| `uri` | (required) | URI or URI template for this resource |
| `name` | `""` | Human-readable name |
| `description` | `""` | Description of the resource |
| `mimeType` | `"text/plain"` | MIME type of the resource content |

### @McpPrompt

Marks a method as an MCP prompt template, accessible via `prompts/get`. The method returns a `List<McpMessage>`:

```java
@McpPrompt(name = "analyze_data", description = "Analyze a dataset")
public List<McpMessage> analyzeData(
        @McpParam(name = "dataset") String dataset) {
    return List.of(
        McpMessage.system("You are a data analyst..."),
        McpMessage.user("Analyze: " + dataset)
    );
}
```

| Attribute | Default | Description |
|-----------|---------|-------------|
| `name` | (required) | Prompt template name |
| `description` | `""` | Description of what this prompt does |

### @McpParam

Annotates method parameters to provide MCP metadata:

| Attribute | Default | Description |
|-----------|---------|-------------|
| `name` | (required) | Parameter name as reported to MCP clients |
| `description` | `""` | Human-readable description |
| `required` | `true` | Whether the parameter is required |

## McpMessage

The `McpMessage` record provides factory methods for building prompt messages:

```java
public record McpMessage(String role, Map<String, String> content) {
    public static McpMessage system(String text) { ... }
    public static McpMessage user(String text) { ... }
}
```

## Complete example: DemoMcpServer

From the `spring-boot-mcp-server` sample -- a real MCP server that exposes chat administration tools, server resources, and prompt templates:

```java
@McpServer(name = "atmosphere-demo", version = "1.0.0", path = "/atmosphere/mcp")
public class DemoMcpServer {

    @Inject
    private AtmosphereConfig config;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Tools ──

    @McpTool(name = "list_users",
             description = "List all users currently connected to the chat")
    public List<Map<String, String>> listUsers() {
        var broadcaster = chatBroadcaster();
        return broadcaster.getAtmosphereResources().stream()
                .map(r -> Map.of(
                        "uuid", r.uuid(),
                        "transport", r.transport().name()))
                .toList();
    }

    @McpTool(name = "broadcast_message",
             description = "Send a message to all connected chat users")
    public Map<String, Object> broadcastMessage(
            @McpParam(name = "message",
                      description = "The message text to broadcast") String message,
            @McpParam(name = "author",
                      description = "Author name",
                      required = false) String author) {
        var broadcaster = chatBroadcaster();
        var msg = new Message(author != null ? author : "MCP Admin", message);
        broadcaster.broadcast(mapper.writeValueAsString(msg));
        return Map.of("status", "sent",
                       "recipients", broadcaster.getAtmosphereResources().size());
    }

    // ── Resources ──

    @McpResource(uri = "atmosphere://server/status",
                 name = "Server Status",
                 description = "Current server status",
                 mimeType = "application/json")
    public String serverStatus() {
        var status = new LinkedHashMap<String, Object>();
        status.put("status", "running");
        status.put("framework", "Atmosphere " + Version.getRawVersion());
        status.put("connectedUsers", config.resourcesFactory().findAll().size());
        status.put("timestamp", Instant.now().toString());
        return status.toString();
    }

    // ── Prompts ──

    @McpPrompt(name = "chat_summary",
               description = "Summarize current chat status")
    public List<McpMessage> chatSummary() {
        var broadcaster = chatBroadcaster();
        var userCount = broadcaster != null
                ? broadcaster.getAtmosphereResources().size() : 0;
        return List.of(
                McpMessage.system("You are a chat moderator..."),
                McpMessage.user("There are currently " + userCount
                        + " users connected. Summarize the status.")
        );
    }

    private Broadcaster chatBroadcaster() {
        return config.getBroadcasterFactory().lookup("/atmosphere/chat", false);
    }
}
```

Key points from this example:

- `@Inject private AtmosphereConfig config` gives access to `BroadcasterFactory` and `AtmosphereResourceFactory`, so MCP tools can interact with live connections.
- Tool methods can return any serializable type -- `List`, `Map`, `String`, records, etc.
- Optional parameters use `required = false` on `@McpParam`.
- The `path` attribute on `@McpServer` determines both the HTTP and WebSocket endpoint.

## Programmatic Registration

For dynamic tools or when you prefer lambdas over annotations, register tools, resources, and prompts programmatically:

```java
McpRegistry registry = McpRegistry.get();

registry.registerTool("greet", "Greet a user by name",
    List.of(new McpRegistry.ParamEntry("name", "User name", true)),
    args -> "Hello, " + args.get("name") + "!"
);

registry.registerResource("atmosphere://app/version",
    "App Version", "Current application version", "text/plain",
    () -> "4.0.0"
);

registry.registerPrompt("welcome", "Welcome message for new users",
    List.of(),
    args -> List.of(
        McpMessage.system("You are a friendly assistant."),
        McpMessage.user("Welcome the user warmly.")
    )
);
```

Programmatic and annotation-based registrations coexist -- agents see all of them via `tools/list`.

## Runtime components

The MCP module includes several runtime classes:

| Class | Role |
|-------|------|
| `McpHandler` | Handles the MCP protocol over HTTP (Streamable HTTP transport) |
| `McpWebSocketHandler` | WebSocket transport for MCP |
| `McpProtocolHandler` | Core JSON-RPC processing for both transports |
| `McpSession` | Per-client MCP session state |
| `McpRegistry` | Discovers and registers `@McpTool`, `@McpResource`, and `@McpPrompt` methods |
| `McpServerProcessor` | Atmosphere annotation processor that wires `@McpServer` classes |
| `McpTracing` | Tracing integration for MCP operations |
| `McpMessage` | Prompt message factory (`system()`, `user()`) |
| `McpMethod` | MCP method name constants |
| `JsonRpc` | JSON-RPC protocol helpers |

## Transports

Atmosphere's MCP module supports three transports:

| Transport | URL | Use Case |
|-----------|-----|----------|
| WebSocket | `ws://host/atmosphere/mcp` | Full-duplex, lowest latency |
| Streamable HTTP | `POST http://host/atmosphere/mcp` | MCP spec 2025-03-26, SSE responses |
| stdio (via bridge) | `java -jar atmosphere-mcp-stdio-bridge.jar <url>` | Claude Desktop, VS Code |

### WebSocket

Clients connect via WebSocket to the server path. The `McpWebSocketHandler` processes JSON-RPC messages over the WebSocket connection. This is the lowest-latency option and supports full-duplex communication.

### Streamable HTTP

The Streamable HTTP transport follows the MCP 2025-03-26 specification:

- **POST** -- send JSON-RPC requests. Set `Accept: text/event-stream` to receive SSE responses
- **GET** -- open an SSE stream for server-initiated notifications
- **DELETE** -- terminate the session
- Sessions tracked via the `Mcp-Session-Id` header

## McpStdioBridge

The `McpStdioBridge` (in `org.atmosphere.mcp.bridge`) bridges stdio to HTTP, enabling CLI-based MCP clients like Claude Desktop and VS Code to connect to your Atmosphere MCP server.

It reads JSON-RPC messages from stdin (one per line), POSTs them to the Streamable HTTP endpoint, and writes responses to stdout:

```
java -jar atmosphere-mcp-stdio-bridge.jar http://localhost:8083/atmosphere/mcp
```

## Connecting AI clients

### Claude Desktop

Claude Desktop supports three transport configurations. Add to `claude_desktop_config.json`:

**Via WebSocket:**

```json
{
  "mcpServers": {
    "atmosphere": {
      "transport": "websocket",
      "url": "ws://localhost:8080/atmosphere/mcp"
    }
  }
}
```

**Via Streamable HTTP:**

```json
{
  "mcpServers": {
    "atmosphere": {
      "transport": "streamable-http",
      "url": "http://localhost:8080/atmosphere/mcp"
    }
  }
}
```

**Via stdio bridge:**

```json
{
  "mcpServers": {
    "atmosphere": {
      "command": "java",
      "args": [
        "-jar",
        "atmosphere-mcp-stdio-bridge.jar",
        "http://localhost:8083/atmosphere/mcp"
      ]
    }
  }
}
```

### VS Code (Copilot / Continue)

For VS Code extensions that support MCP, use the stdio bridge configuration:

```json
{
  "mcpServers": {
    "atmosphere": {
      "command": "java",
      "args": [
        "-jar",
        "atmosphere-mcp-stdio-bridge.jar",
        "http://localhost:8083/atmosphere/mcp"
      ]
    }
  }
}
```

### Cursor

Cursor supports MCP natively. Add to your project's `.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "atmosphere": {
      "command": "java",
      "args": [
        "-jar",
        "atmosphere-mcp-stdio-bridge.jar",
        "http://localhost:8083/atmosphere/mcp"
      ]
    }
  }
}
```

## How it works

When Atmosphere starts, the `McpServerProcessor` scans for classes annotated with `@McpServer`. For each one, it:

1. Creates an `McpRegistry` that discovers all `@McpTool`, `@McpResource`, and `@McpPrompt` methods via reflection.
2. Registers an `McpHandler` (for Streamable HTTP) at the configured path.
3. Registers an `McpWebSocketHandler` (for WebSocket) at the same path.

When an MCP client connects and sends `initialize`, the server responds with its name, version, and a list of capabilities (tools, resources, prompts). The client can then:

- Call `tools/list` to discover available tools.
- Call `tools/call` with arguments to invoke a tool.
- Call `resources/list` and `resources/read` to access resources.
- Call `prompts/list` and `prompts/get` to retrieve prompt templates.

The `McpProtocolHandler` handles the JSON-RPC dispatch, parameter binding (via `@McpParam`), and response serialization.

## MCP + Atmosphere: the key insight

The real power of Atmosphere's MCP module is that your MCP tools have full access to the Atmosphere runtime. An AI agent can:

- **Query live connections**: list connected users, inspect transport types, check UUIDs.
- **Push messages**: broadcast to all clients or send to a specific user by UUID.
- **Manage rooms**: join/leave rooms, query room membership, read message history.
- **Inspect server state**: check framework version, async support type, broadcaster count.

This means an AI agent can act as a **real-time chat moderator**, **notification system**, or **admin console** -- all through the standard MCP protocol that works with Claude Desktop, Cursor, and VS Code out of the box.

## Sample

The `samples/spring-boot-mcp-server/` sample contains the complete `DemoMcpServer` shown above, including a chat application that the MCP tools can interact with. Run it with:

```bash
./mvnw spring-boot:run -pl samples/spring-boot-mcp-server
```

Then connect from Claude Desktop, VS Code, or Cursor using the configuration examples in [Connecting AI clients](#connecting-ai-clients).
