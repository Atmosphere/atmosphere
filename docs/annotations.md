# Annotation Reference

Complete reference for all Atmosphere annotations. All annotations are compatible across `@Agent` and `@ManagedService` unless noted.

## Agent Annotations

| Annotation | Target | Module | Description |
|-----------|--------|--------|-------------|
| `@Agent` | Class | `atmosphere-agent` | Declares an AI agent. Wires endpoint, commands, tools, skill file, protocols, and channels automatically. |
| `@Command` | Method | `atmosphere-agent` | Slash command (e.g. `/help`, `/status`). Executes instantly with no LLM cost. |
| `@Prompt` | Method | `atmosphere-ai` | LLM streaming entry point. Receives user messages and streams responses via `StreamingSession`. |
| `@AiEndpoint` | Class | `atmosphere-ai` | Simpler alternative to `@Agent` — AI endpoint without commands or channels. |

### `@Agent` Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `name` | String | required | Agent name. Used in endpoint path `/atmosphere/agent/{name}`. |
| `skillFile` | String | `""` | Classpath path to skill file. If empty, auto-discovers at `META-INF/skills/{name}/SKILL.md`. |
| `description` | String | `""` | Agent description. Used in A2A Agent Card and protocol metadata. |
| `endpoint` | String | `""` | Custom endpoint path. Overrides default `/atmosphere/agent/{name}/a2a`. |
| `version` | String | `"1.0.0"` | Agent version. Used in Agent Card and MCP server info. |
| `headless` | boolean | `false` | When true, no WebSocket UI handler — A2A/MCP only. Auto-detected when class has `@AgentSkill` but no `@Prompt`. |

### `@Command` Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `value` | String | required | Command name (e.g. `"/status"`). |
| `description` | String | `""` | Help text shown in auto-generated `/help`. |
| `confirm` | String | `""` | If set, the user is prompted for confirmation before execution. |

## AI Annotations

| Annotation | Target | Module | Description |
|-----------|--------|--------|-------------|
| `@AiTool` | Method | `atmosphere-ai` | Declares a method callable by the LLM during inference. Portable across all backends. |
| `@Param` | Parameter | `atmosphere-ai` | Names a parameter on an `@AiTool` method. |

### `@AiTool` Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `name` | String | required | Tool name as seen by the LLM. |
| `description` | String | required | Description of what the tool does — the LLM reads this to decide when to call it. |

## Protocol Annotations — MCP

| Annotation | Target | Module | Description |
|-----------|--------|--------|-------------|
| `@McpTool` | Method | `atmosphere-mcp` | Exposes a method as an MCP tool (`tools/call`). |
| `@McpResource` | Method | `atmosphere-mcp` | Exposes a method as an MCP resource (`resources/read`). |
| `@McpPrompt` | Method | `atmosphere-mcp` | Exposes a method as an MCP prompt template (`prompts/get`). |
| `@McpParam` | Parameter | `atmosphere-mcp` | Annotates an MCP method parameter with name, description, and required flag. |

Works on both `@Agent` and `@ManagedService` classes. When placed on a `@ManagedService`, the MCP endpoint is auto-registered at `{path}/mcp`.

## Protocol Annotations — A2A

| Annotation | Target | Module | Description |
|-----------|--------|--------|-------------|
| `@AgentSkill` | Method | `atmosphere-a2a` | Declares an A2A skill exposed via Agent Card. |
| `@AgentSkillHandler` | Method | `atmosphere-a2a` | Marks the skill method as the task handler. Used together with `@AgentSkill`. |
| `@AgentSkillParam` | Parameter | `atmosphere-a2a` | Names and describes a skill method parameter. Supports typed coercion (String, int, long, double, boolean, JsonNode). |

Works on both `@Agent` and `@ManagedService` classes. When placed on a `@ManagedService`, the A2A endpoint is auto-registered at `{path}/a2a`.

### `@AgentSkill` Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `id` | String | required | Skill identifier in the Agent Card. |
| `name` | String | required | Human-readable skill name. |
| `description` | String | required | Skill description — used for A2A discovery. |
| `tags` | String[] | `{}` | Tags for categorization and filtering. |

## Protocol Annotations — AG-UI

| Annotation | Target | Module | Description |
|-----------|--------|--------|-------------|
| `@AgUiEndpoint` | Class | `atmosphere-agui` | Declares an AG-UI SSE endpoint. |
| `@AgUiAction` | Method | `atmosphere-agui` | Handles AG-UI run requests. Emits events via `StreamingSession.emit()`. |

## Lifecycle Annotations (Atmosphere 3.x)

All lifecycle annotations work in both `@Agent` and `@ManagedService`.

| Annotation | Target | Module | Description |
|-----------|--------|--------|-------------|
| `@Ready` | Method | `atmosphere-runtime` | Invoked when a client connects. Signature: `()` or `(AtmosphereResource)`. |
| `@Disconnect` | Method | `atmosphere-runtime` | Invoked when a client disconnects. Signature: `()` or `(AtmosphereResourceEvent)`. |
| `@Heartbeat` | Method | `atmosphere-runtime` | Invoked on heartbeat keep-alive. Signature: `()` or `(AtmosphereResourceEvent)`. |
| `@Message` | Method | `atmosphere-runtime` | Handles raw transport messages with encoder/decoder support. |
| `@ManagedService` | Class | `atmosphere-runtime` | Declares a transport-agnostic endpoint (WebSocket/SSE/Long-Polling). |
| `@PathParam` | Field | `atmosphere-runtime` | Injects URL path parameters (e.g. `/chat/{room}` → `@PathParam String room`). |
| `@DeliverTo` | Method | `atmosphere-runtime` | Controls message delivery scope (all subscribers, single resource, etc.). |
| `@Singleton` | Class | `atmosphere-runtime` | Single instance per path instead of per-connection. |
| `@Get` / `@Post` / `@Put` / `@Delete` | Method | `atmosphere-runtime` | HTTP method handlers within `@ManagedService`. |

### `@Message` Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `encoders` | Class[] | `{}` | Encoder classes for outgoing messages. |
| `decoders` | Class[] | `{}` | Decoder classes for incoming messages. |

## Injection

| What | How | Works In |
|------|-----|----------|
| `Broadcaster` | `@Inject @Named("/path")` | `@Agent`, `@ManagedService` — pub/sub to Kafka, Redis, or any custom channel |
| `AtmosphereResource` | `@Inject` | `@ManagedService` |
| `AtmosphereResourceEvent` | `@Inject` | `@ManagedService` |
| `AtmosphereConfig` | `@Inject` | `@Agent`, `@ManagedService` |
| `StreamingSession` | Method parameter in `@Prompt` | `@Agent`, `@AiEndpoint` |
| `TaskContext` | Method parameter in `@AgentSkillHandler` | `@Agent` |
