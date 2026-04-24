# Spring Boot MCP Server Sample

A demonstration of Atmosphere's MCP (Model Context Protocol) server module. AI agents like Claude Desktop, VS Code Copilot, or Cursor connect via Streamable HTTP or WebSocket and invoke tools, read resources, and use prompt templates.

## What It Does

The `DemoMcpServer` exposes:

**Tools** (agents can call these):
- `list_users` — list all users currently connected to the chat
- `ban_user` — disconnect and ban a user from the chat by UUID
- `broadcast_message` — send a message to all connected chat users
- `send_message` — send a private message to a specific user by UUID
- `atmosphere_version` — return the Atmosphere framework version and runtime info

**Resources** (agents can read these):
- `atmosphere://server/status` — server status and uptime
- `atmosphere://server/capabilities` — what the server can do

**Prompts** (reusable prompt templates):
- `chat_summary` — summarize current chat status
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
      "args": ["-jar", "path/to/atmosphere-mcp-*.jar",
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
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_users","arguments":{}}}'
```

## Server Code

The entire server is a single annotated class — see [DemoMcpServer.java](src/main/java/org/atmosphere/samples/mcp/DemoMcpServer.java).

There is **no dedicated `@McpServer` annotation**. The class is marked with `@Agent(headless = true)` and the MCP module scans it for `@McpTool`, `@McpResource`, and `@McpPrompt` methods, wiring them onto the endpoint path declared on `@Agent`.

```java
@Agent(name = "atmosphere-demo", version = "1.0.0",
       endpoint = "/atmosphere/mcp", headless = true)
public class DemoMcpServer {

    @McpTool(name = "list_users", description = "List all users connected to the chat")
    public List<Map<String, String>> listUsers() { ... }

    @McpTool(name = "broadcast_message", description = "Send a message to all chat users")
    public Map<String, Object> broadcastMessage(
            @McpParam(name = "message") String message) { ... }

    @McpResource(uri = "atmosphere://server/status", ...)
    public String serverStatus() { ... }

    @McpPrompt(name = "chat_summary", ...)
    public List<McpMessage> chatSummary() { ... }
}
```

## Governance — scope on every MCP tool call

**This sample is unique**: governance on MCP protocol dispatch over the
same streaming transport as the UI. Microsoft Agent Governance Toolkit
has an MCP security gateway but it's HTTP-only; Atmosphere governs the
same dispatch over WebSocket/SSE + streams tool events to admin consumers.

### How it works

`McpGovernanceConfig` publishes four admission policies onto
`GovernancePolicy.POLICIES_PROPERTY`. The MCP module's `McpPolicyGateway`
calls `PolicyAdmissionGate.admitToolCall(framework, toolName, args)` on
every `tools/call` — the policy chain evaluates before the
`@McpTool`-annotated method runs.

| Policy | Shape | What it catches |
|---|---|---|
| `kill-switch` | `KillSwitchPolicy` | Operator break-glass — halts every MCP call at 0.1ms |
| `mcp-tool-rate-limit` | `RateLimitPolicy(60/60s)` | Per-MCP-client rate cap |
| `mcp-tool-allowlist` | `AllowListPolicy` | Default-deny — only `list_users`, `broadcast_message`, `send_message`, `atmosphere_version` admitted. Sensitive `ban_user` is deliberately absent so operators opt in explicitly. |
| `mcp-arg-deny-list` | `DenyListPolicy.fromRegex` | Catches `DROP TABLE`, `rm -rf /`, path traversal in tool arguments |

### Exercise live

```bash
# Run the sample
./mvnw spring-boot:run -pl samples/spring-boot-mcp-server

# Connect an MCP client to ws://localhost:8083/mcp and call an admitted tool
# → list_users succeeds (on the allow-list)

# Try a non-allowlisted tool
# → ban_user denied by mcp-tool-allowlist — PolicyAdmissionGate.admitToolCall
#   blocks dispatch before the @McpTool method runs

# Try an argument injection on an admitted tool
# broadcast_message({body: "'; DROP TABLE users;'"})
# → denied by mcp-arg-deny-list, method never called

# Ops break-glass — halts every MCP call
curl -X POST http://localhost:8083/api/admin/governance/kill-switch/arm \
     -H 'Content-Type: application/json' \
     -d '{"reason":"mcp-incident","operator":"oncall"}'
```

### OWASP evidence

This sample is a production consumer for OWASP A02 (tool misuse) + A08
(supply chain / MCP plugins) evidence rows. The `EvidenceConsumerGrepPinTest`
CI gate asserts that a production caller exists for each claimed coverage.

```bash
curl http://localhost:8083/api/admin/governance/agt-verify | jq '.findings[]
    | select(.controlId == "A02" or .controlId == "A08")'
```

## React Frontend

The sample includes a React frontend (`frontend/`) built with the `useAtmosphere` hook from `atmosphere.js/react`. It provides a live chat UI where human users interact in real-time — while AI agents simultaneously connect via MCP to invoke tools like `list_users`, `broadcast_message`, and `ban_user`.

```tsx
import { useAtmosphere } from 'atmosphere.js/react';

const { data, state, push } = useAtmosphere<ChatMessage>({
  request: {
    url: '/atmosphere/chat',
    transport: 'websocket',
    contentType: 'application/json',
  },
});
```

See the [atmosphere.js client docs](https://atmosphere.github.io/docs/clients/javascript/) for the full hooks API.

## Key Concepts

- **`@Agent(headless = true)`** — marks a class as an MCP-exposed agent and sets the endpoint path. There is no dedicated `@McpServer` annotation; the MCP module reuses `@Agent` and scans for MCP annotations below.
- **`@McpTool`** — exposes a method as a callable tool
- **`@McpResource`** — exposes a method as a read-only resource
- **`@McpPrompt`** — exposes a method as a prompt template
- **`@McpParam`** — annotates method parameters with metadata

MCP is not a transport — it is a protocol that rides on top of Atmosphere transports (WebSocket, SSE, Streamable HTTP). That means agents get automatic reconnection, heartbeats, and transport fallback for free.
