# Spring Boot MCP Server Sample

A demonstration of Atmosphere's MCP (Model Context Protocol) server module. AI agents like Claude Desktop, VS Code Copilot, or Cursor connect via Streamable HTTP or WebSocket and invoke tools, read resources, and use prompt templates.

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

The MCP endpoint is available at `http://localhost:8083/atmosphere/mcp`.

## Supported Transports

| Transport | URL |
|-----------|-----|
| **Streamable HTTP** (recommended) | `POST http://localhost:8083/atmosphere/mcp` |
| **WebSocket** | `ws://localhost:8083/atmosphere/mcp` |
| **SSE** | `GET http://localhost:8083/atmosphere/mcp` |

## Connecting MCP Clients

### Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "atmosphere-demo": {
      "url": "http://localhost:8083/atmosphere/mcp"
    }
  }
}
```

### VS Code (GitHub Copilot)

Add to `.vscode/mcp.json`:

```json
{
  "servers": {
    "atmosphere-demo": {
      "url": "http://localhost:8083/atmosphere/mcp"
    }
  }
}
```

### Cursor

Add to Cursor Settings → MCP Servers:

```json
{
  "mcpServers": {
    "atmosphere-demo": {
      "url": "http://localhost:8083/atmosphere/mcp"
    }
  }
}
```

### stdio Bridge (for clients that only support stdio)

```bash
# Build the bridge JAR
cd modules/mcp && mvn package -Pstdio-bridge -DskipTests

# Configure your client:
{
  "mcpServers": {
    "atmosphere-demo": {
      "command": "java",
      "args": ["-jar", "path/to/atmosphere-mcp-4.0.0-SNAPSHOT-stdio-bridge.jar",
               "http://localhost:8083/atmosphere/mcp"]
    }
  }
}
```

## Testing with curl

```bash
# Initialize session
curl -s -X POST http://localhost:8083/atmosphere/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","clientInfo":{"name":"curl","version":"1.0"}}}'

# List tools (include Mcp-Session-Id from initialize response header)
curl -s -X POST http://localhost:8083/atmosphere/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# Call a tool
curl -s -X POST http://localhost:8083/atmosphere/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_time","arguments":{"timezone":"UTC"}}}'
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

- **`@McpServer`** — marks the class and sets the endpoint path
- **`@McpTool`** — exposes a method as a callable tool
- **`@McpResource`** — exposes a method as a read-only resource
- **`@McpPrompt`** — exposes a method as a prompt template
- **`@McpParam`** — annotates method parameters with metadata

The MCP module uses Atmosphere's transport layer, so agents get automatic reconnection, heartbeats, and transport fallback for free.
