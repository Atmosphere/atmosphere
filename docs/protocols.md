# Agent Protocols — MCP, A2A, AG-UI

Atmosphere supports three agentic protocols. All ride Atmosphere's transport layer (WebSocket, SSE, HTTP) and integrate with `@Agent` or can be used standalone.

## When to Use What

| Protocol | Direction | Use Case |
|----------|-----------|----------|
| **MCP** | Agent <-> Tools | Expose tools to AI agents (Claude Desktop, Copilot, Cursor) |
| **A2A** | Agent <-> Agent | Agent discovery and task delegation (Google/Linux Foundation spec) |
| **AG-UI** | Agent <-> Frontend | Stream agent state to UIs (CopilotKit compatible) |

## MCP (Model Context Protocol)

Expose tools, resources, and prompts to external AI agents.

```java
@McpServer(name = "my-tools", path = "/atmosphere/mcp")
public class MyTools {
    @McpTool(name = "ask_ai", description = "Ask AI and stream the answer")
    public String askAi(@McpParam(name = "question") String q, StreamingSession session) {
        session.stream(q);
        return "streaming";
    }
}
```

**Annotations**: `@McpServer`, `@McpTool`, `@McpResource`, `@McpPrompt`, `@McpParam`
**Transports**: Streamable HTTP, WebSocket, SSE
**Features**: BiDirectionalToolBridge (server -> client tool calls), OpenTelemetry tracing

See [modules/mcp/README.md](../modules/mcp/README.md) for the full API and [spring-boot-mcp-server](../samples/spring-boot-mcp-server/) for a working sample.

## A2A (Agent-to-Agent)

Publish an Agent Card for discovery and handle tasks from other agents via JSON-RPC 2.0.

```java
@A2aServer(name = "weather-agent", endpoint = "/atmosphere/a2a")
public class WeatherAgent {
    @A2aSkill(id = "get-weather", name = "Get Weather", description = "Weather for a city")
    @A2aTaskHandler
    public void weather(TaskContext task, @A2aParam(name = "city") String city) {
        task.addArtifact(Artifact.text(weatherService.lookup(city)));
        task.complete("Done");
    }
}
```

**Annotations**: `@A2aServer`, `@A2aSkill`, `@A2aTaskHandler`, `@A2aParam`
**Wire format**: JSON-RPC 2.0 over HTTP
**Features**: Auto-published Agent Card at `/.well-known/agent.json`, skill metadata from `@Agent` skill file

See [spring-boot-a2a-agent](../samples/spring-boot-a2a-agent/) for a working sample.

## AG-UI (Agent-User Interaction)

Stream structured agent events (steps, tool calls, text deltas) to frontends.

```java
@AgUiEndpoint(path = "/atmosphere/agui")
public class Assistant {
    @AgUiAction
    public void onRun(RunContext run, StreamingSession session) {
        session.emit(new AiEvent.AgentStep("analyze", "Thinking...", Map.of()));
        session.emit(new AiEvent.TextDelta("Hello! "));
        session.emit(new AiEvent.TextComplete("Hello!"));
    }
}
```

**Annotations**: `@AgUiEndpoint`, `@AgUiAction`
**Wire format**: SSE with 28 event types (lifecycle, tool calls, text streaming, state)
**Compatible with**: CopilotKit and any AG-UI consumer

See [spring-boot-agui-chat](../samples/spring-boot-agui-chat/) for a working sample.

## Using with `@Agent`

Protocol exposure is **automatic based on classpath detection** — there is no `protocols` attribute on `@Agent`. If `atmosphere-a2a` is on the classpath, the A2A endpoint is registered; if `atmosphere-mcp` is present, MCP is registered; and so on.

```java
@Agent(name = "devops", skillFile = "prompts/devops-skill.md")
public class DevOpsAgent { ... }
// A2A, MCP, AG-UI endpoints are registered automatically
// if the corresponding modules are on the classpath.
```

When used with `@Agent`, skill file sections (`## Skills`, `## Tools`) are automatically exported to the corresponding protocol metadata (A2A Agent Card, MCP tool list).
