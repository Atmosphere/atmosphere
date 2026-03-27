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
@Agent(name = "my-tools", headless = true)
public class MyTools {
    @McpTool(name = "ask_ai", description = "Ask AI and stream the answer")
    public String askAi(@McpParam(name = "question") String q) {
        return myService.answer(q);
    }

    @McpResource(uri = "config://settings", name = "Settings",
                 description = "Application settings")
    public String settings() {
        return settingsJson;
    }
}
```

**Annotations**: `@McpTool`, `@McpResource`, `@McpPrompt`, `@McpParam` (used on `@Agent` classes — MCP endpoint auto-registers when `atmosphere-mcp` is on the classpath)
**Transports**: Streamable HTTP, WebSocket, SSE
**Features**: BiDirectionalToolBridge (server -> client tool calls), OpenTelemetry tracing

See [modules/mcp/README.md](../modules/mcp/README.md) for the full API and [spring-boot-mcp-server](../samples/spring-boot-mcp-server/) for a working sample.

## A2A (Agent-to-Agent)

Publish an Agent Card for discovery and handle tasks from other agents via JSON-RPC 2.0. Use `@Agent` with `@AgentSkill` methods — headless mode is auto-detected when there is no `@Prompt` method.

```java
@Agent(name = "weather-agent", endpoint = "/atmosphere/a2a",
       description = "Weather and time agent")
public class WeatherAgent {
    @AgentSkill(id = "get-weather", name = "Get Weather", description = "Weather for a city")
    @AgentSkillHandler
    public void weather(TaskContext task, @AgentSkillParam(name = "city") String city) {
        task.addArtifact(Artifact.text(weatherService.lookup(city)));
        task.complete("Done");
    }
}
```

**Annotations**: `@Agent`, `@AgentSkill`, `@AgentSkillHandler`, `@AgentSkillParam` (in `org.atmosphere.a2a.annotation`)
**Wire format**: JSON-RPC 2.0 over HTTP
**Features**: Auto-published Agent Card at `/.well-known/agent.json`, skill metadata from `@Agent` skill file, headless auto-detection

See [spring-boot-a2a-agent](../samples/spring-boot-a2a-agent/) for a standalone A2A agent and [spring-boot-a2a-startup-team](../samples/spring-boot-a2a-startup-team/) for a multi-agent team with headless agents.

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
// Full-stack agent: WebSocket UI + all protocols
@Agent(name = "devops", skillFile = "prompts/devops-skill.md")
public class DevOpsAgent {
    @Prompt
    public void onMessage(String msg, StreamingSession s) { s.stream(msg); }
}

// Headless agent: A2A/MCP only, no WebSocket UI
@Agent(name = "research", endpoint = "/atmosphere/a2a/research",
       description = "Web research agent")
public class ResearchAgent {
    @AgentSkill(id = "search", name = "Search", description = "Search the web")
    @AgentSkillHandler
    public void search(TaskContext task, @AgentSkillParam(name="query") String query) {
        task.addArtifact(Artifact.text("Results for: " + query));
        task.complete("Done");
    }
}
```

Headless mode is auto-detected when a class has `@AgentSkill` methods but no `@Prompt` method, or can be forced with `headless = true`. Skill file sections (`## Skills`, `## Tools`) are automatically exported to protocol metadata (A2A Agent Card, MCP tool list).
