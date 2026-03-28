# @Agent Reference

`@Agent` is the primary annotation for defining an Atmosphere AI agent. It desugars to an AI endpoint with command routing, conversation memory, tool registration, and multi-protocol exposure based on classpath detection.

## Annotation Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `name` | `String` | (required) | Agent name. Used in the path (`/atmosphere/agent/{name}`) and protocol metadata. |
| `skillFile` | `String` | `""` | Classpath resource for the skill file. The entire file becomes the system prompt. |
| `description` | `String` | `""` | Human-readable description. Used in A2A Agent Card metadata. |
| `endpoint` | `String` | `""` | Custom A2A endpoint path. Overrides the default `/atmosphere/agent/{name}/a2a`. |
| `version` | `String` | `"1.0.0"` | Agent version. Used in Agent Card and protocol responses. |
| `headless` | `boolean` | `false` | When `true`, no WebSocket UI handler is registered. |
| `responseAs` | `Class<?>` | `Void.class` | Target type for structured output (JSON parsed into this type). |

## Endpoint Paths

The processor registers these paths automatically:

| Path | Condition |
|------|-----------|
| `/atmosphere/agent/{name}` | Always (WebSocket UI, unless headless) |
| `/atmosphere/agent/{name}/a2a` | `atmosphere-a2a` on classpath |
| `/atmosphere/agent/{name}/mcp` | `atmosphere-mcp` on classpath |
| `/atmosphere/agent/{name}/agui` | `atmosphere-agui` on classpath |

Override the A2A path with `endpoint = "/custom/path"`.

## Full-Stack vs Headless Mode

**Full-stack** agents have a WebSocket UI handler and an AI pipeline. This is the default.

**Headless** agents register only protocol endpoints (A2A, MCP) with no WebSocket UI. Headless mode is activated when:

1. `headless = true` is set explicitly, OR
2. The class has `@AgentSkill`/`@McpTool` methods but no `@Prompt` method (auto-detected).

```java
// Headless: auto-detected (has @AgentSkill, no @Prompt)
@Agent(name = "research", description = "Web research agent")
public class ResearchAgent {
    @AgentSkill(id = "search", name = "Search", description = "Search the web")
    @AgentSkillHandler
    public void search(TaskContext task, @AgentSkillParam(name="query") String query) {
        task.addArtifact(Artifact.text("Results for: " + query));
        task.complete("Done");
    }
}
```

If an agent is headless but no protocol modules are on the classpath, a warning is logged.

## @Prompt Method

The `@Prompt`-annotated method handles natural-language messages. It runs on a virtual thread and accepts these parameter types (in any order):

| Parameter Type | Injected Value |
|----------------|---------------|
| `String` | The user's message text |
| `StreamingSession` | Session for streaming tokens back to the client |
| `AtmosphereResource` | The underlying Atmosphere connection |
| `AgentFleet` | Fleet proxy (only in `@Coordinator` classes) |

`@Prompt` is optional. When omitted, the processor generates a synthetic handler equivalent to `session.stream(message)`.

```java
@Agent(name = "assistant", skillFile = "prompts/assistant-skill.md")
public class Assistant {
    @Prompt
    public void onMessage(String msg, StreamingSession session) {
        session.stream(msg);
    }
}
```

## @Command Methods

Slash commands are methods annotated with `@Command`. They execute before the AI pipeline -- messages starting with `/` bypass the LLM.

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `value` | `String` | (required) | Command prefix. Must start with `/`. |
| `description` | `String` | `""` | Shown in `/help` and A2A skill metadata. |
| `confirm` | `String` | `""` | Confirmation prompt for destructive actions. Expires after 60s. |

Rules:
- Must return `String`.
- Accepts zero parameters or one `String` parameter (the arguments after the prefix).
- Duplicate prefixes within one agent throw `IllegalArgumentException`.

A `/help` command is auto-generated listing all registered commands.

```java
@Command(value = "/deploy", description = "Deploy to staging",
         confirm = "Deploy latest build to staging?")
public String deploy(String args) {
    return "Deployed " + args + " to staging.";
}
```

## @AiTool Methods

Methods annotated with `@AiTool` are registered in the `ToolRegistry` and exposed to the LLM as callable functions. They are also bridged to MCP tools when `atmosphere-mcp` is on the classpath.

```java
@AiTool(name = "get_weather", description = "Get current weather for a city")
public WeatherResult getWeather(@Param("city") String city,
                                @Param(value = "unit", required = false) String unit) {
    return weatherService.lookup(city, unit);
}
```

`@Param` attributes: `value` (name), `description`, `required` (default `true`).

The processor cross-references `@AiTool` methods against the `## Tools` section in the skill file. Mismatches produce warnings at startup.

## Skill File Auto-Discovery

When `skillFile` is empty, the processor searches the classpath in order:

1. `META-INF/skills/{name}/SKILL.md`
2. `prompts/{name}.md`
3. `prompts/{name}-skill.md`
4. `prompts/skill.md`

First match wins. If none found, an empty system prompt is used. See [skill-files.md](skill-files.md) for the file format.

## Protocol Exposure

Protocol registration is automatic based on classpath detection. There is no `protocols` attribute on `@Agent`.

| Module on Classpath | What Happens |
|---------------------|-------------|
| `atmosphere-a2a` | A2A endpoint registered. Commands become A2A skills. Agent Card published. |
| `atmosphere-mcp` | MCP endpoint registered. `@AiTool` methods become MCP tools. |
| `atmosphere-agui` | AG-UI endpoint registered. `@Prompt` bridged to SSE event stream. |
| `atmosphere-channels` | Commands auto-routed to Slack, Telegram, Discord, etc. |

See [protocols.md](protocols.md) for details on each protocol.

## Lifecycle Callbacks

Annotate methods with lifecycle annotations from `org.atmosphere.config.service`:

| Annotation | When Invoked |
|------------|-------------|
| `@Ready` | Client connects and is suspended |
| `@Disconnect` | Client disconnects |
| `@Heartbeat` | Heartbeat received from client |

```java
@Ready
public void onReady(AtmosphereResource r) {
    logger.info("Client connected: {}", r.uuid());
}

@Disconnect
public void onDisconnect(AtmosphereResourceEvent event) {
    logger.info("Client disconnected");
}
```

## Message Routing Flow

1. Client sends a message to `/atmosphere/agent/{name}`.
2. If the message starts with `/`, the `CommandRouter` looks up the prefix.
   - Match found: execute the `@Command` method, return the result.
   - `/help`: return the auto-generated help text.
3. If a `@Message`-annotated method exists, try it next (with encoder/decoder support).
4. Otherwise, delegate to the AI pipeline (`AiEndpointHandler`), which runs the `@Prompt` method with the configured `AgentRuntime`, conversation memory, and tools.
