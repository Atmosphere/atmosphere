# Spring Boot MCP Server Sample

A demonstration of Atmosphere's MCP (Model Context Protocol) server module. AI agents like Claude Desktop or GitHub Copilot connect over WebSocket and invoke tools, read resources, and use prompt templates.

## What It Does

The `DemoMcpServer` exposes:

**Tools** (agents can call these):
- `get_time` — returns the current server time in any timezone
- `save_note` / `list_notes` — simple note storage
- `calculate` — basic arithmetic

**Resources** (agents can read these):
- `atmosphere://server/status` — server status and uptime
- `atmosphere://server/capabilities` — what the server can do

**Prompts** (reusable prompt templates):
- `summarize_notes` — summarize all saved notes
- `analyze_topic` — analyze a topic with configurable depth

## Running

```bash
./mvnw spring-boot:run -pl samples/spring-boot-mcp-server
```

The MCP endpoint is available at `ws://localhost:8083/atmosphere/mcp`.

## Connecting Claude Desktop

Add to your Claude Desktop `config.json`:

```json
{
  "mcpServers": {
    "atmosphere-demo": {
      "transport": "websocket",
      "url": "ws://localhost:8083/atmosphere/mcp"
    }
  }
}
```

## Server Code

The entire server is a single annotated class — see [DemoMcpServer.java](src/main/java/org/atmosphere/samples/mcp/DemoMcpServer.java).

```java
@McpServer(name = "atmosphere-demo", path = "/atmosphere/mcp")
public class DemoMcpServer {

    @McpTool(name = "get_time", description = "Get the current server time")
    public String getTime(@McpParam(name = "timezone", ...) String tz) { ... }

    @McpResource(uri = "atmosphere://server/status", ...)
    public String serverStatus() { ... }

    @McpPrompt(name = "summarize_notes", ...)
    public List<McpMessage> summarizeNotes() { ... }
}
```

## Key Concepts

- **`@McpServer`** — marks the class and sets the WebSocket path
- **`@McpTool`** — exposes a method as a callable tool
- **`@McpResource`** — exposes a method as a read-only resource
- **`@McpPrompt`** — exposes a method as a prompt template
- **`@McpParam`** — annotates method parameters with metadata

The MCP module uses Atmosphere's transport layer, so agents get automatic reconnection, heartbeats, and SSE fallback for free.
