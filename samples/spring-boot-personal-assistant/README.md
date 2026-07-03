# spring-boot-personal-assistant

A long-lived, memory-bearing personal assistant with a three-member
crew (scheduler, research, drafter) dispatched through
`InMemoryProtocolBridge`. Exercises the foundation primitives —
`AgentState`, `AgentWorkspace`, `AgentIdentity`, `ToolExtensibilityPoint`,
`AiGateway`, `ProtocolBridge`, and `LongTermMemory` — end-to-end.

"Memory-bearing" here means two distinct, real mechanisms:

1. **Workspace memory** (`AgentState` / `AgentWorkspace`) — durable rules
   and notes seeded from the `agent-workspace/` markdown files.
2. **Cross-session fact recall** (`LongTermMemory`) — the assistant
   automatically remembers facts about a user (their name, pets,
   preferences) and recalls them in later sessions. This is wired on the
   `UpstreamMcpAgent` endpoint via the real `InMemoryLongTermMemory` +
   `LongTermMemoryInterceptor` primitives — see
   [Long-term memory](#long-term-memory--cross-session-fact-recall) below.

## What this sample proves

- **`AgentState`** — conversation history + user-scoped facts + workspace
  rules flow through one SPI. The bundled `src/main/resources/agent-workspace/`
  directory seeds the shape; at runtime each user × agent combination writes
  to its own isolated subtree under `users/<userId>/agents/primary-assistant/`.
- **`AgentWorkspace`** — the sample ships an OpenClaw-compatible workspace
  (`AGENTS.md` / `SOUL.md` / `USER.md` / `IDENTITY.md` / `MEMORY.md`) plus
  Atmosphere extension files (`CHANNELS.md` / `MCP.md` / `PERMISSIONS.md`)
  that OpenClaw ignores.
- **`ProtocolBridge`** — the three crew members are dispatched through
  `InMemoryProtocolBridge`. Move any member to A2A and the coordinator
  code does not change.
- **`AgentIdentity`** — permission modes govern crew dispatch; audit events
  record who delegated what.
- **`ToolExtensibilityPoint`** — per-user MCP servers listed in `MCP.md`
  surface to the drafter / research agents with credentials resolved
  through the trust provider backed by the user's `CredentialStore`.
- **`AiGateway`** — every LLM call enters the gateway for rate limiting
  and trace emission.
- **`LongTermMemory`** — the `UpstreamMcpAgent` endpoint recalls stored
  user facts into the system prompt before each turn (`preProcess`) and
  extracts new facts when the session closes (`onDisconnect`), so the
  assistant remembers a user across reconnects. Backed by the in-tree
  `InMemoryLongTermMemory` + `LongTermMemoryInterceptor`; see
  [Long-term memory](#long-term-memory--cross-session-fact-recall).

## Running

```bash
# From the repo root
./mvnw compile -pl samples/spring-boot-personal-assistant

# Run with an OpenAI key (Built-in OpenAI-compatible runtime is the default)
export OPENAI_API_KEY=sk-your-key
./mvnw spring-boot:run -pl samples/spring-boot-personal-assistant

# Open http://localhost:8080 in a browser and chat with the assistant.
```

The sample keyword-routes user input to one of the crew members without
needing an LLM (scheduler / research / drafter trigger on the obvious
verbs). When an LLM key is present, the primary assistant streams its own
response through the active `AgentRuntime` for anything that does not
match a crew member.

## Switching runtimes

The sample defaults to the Built-in OpenAI-compatible client (no extra runtime
dependency — it speaks the OpenAI wire format using `OPENAI_API_KEY`). Atmosphere
discovers runtimes via `ServiceLoader`; add one runtime artifact to the POM and set
`atmosphere.ai.runtime` in `application.yml` to switch:

- _(default)_ — Built-in OpenAI-compatible client
- `atmosphere-spring-ai` — Spring AI
- `atmosphere-adk` — Google ADK
- `atmosphere-langchain4j` — LangChain4j
- `atmosphere-koog` — Koog (Kotlin)
- `atmosphere-semantic-kernel` — Semantic Kernel
- `atmosphere-agentscope` — Alibaba AgentScope
- `atmosphere-embabel` — Embabel
- `atmosphere-spring-ai-alibaba` — Spring AI Alibaba

Embabel and Spring AI Alibaba currently require the Spring Boot 3.5 profile.

The assistant's behavior does not change with the runtime — swap the
Maven dependency and every primitive still wires the same way.

## Demo flow

1. Start the sample. Open `http://localhost:8080`.
2. Ask: `"Research the state of Java virtual threads."` The primary routes
   to the research crew member; the artifact renders inline.
3. Ask: `"Schedule a meeting with the team tomorrow."` The scheduler
   proposes three slots.
4. Ask: `"Draft a note to the team about the new release."` The drafter
   produces a short draft matching the tone from `SOUL.md`.
5. Inspect what the assistant remembers via the admin control plane at
   `http://localhost:8080/atmosphere/admin/` (memory tab).

## Outbound MCP — `UpstreamMcpAgent`

In addition to the local `@AiTool` crew dispatch demonstrated by
`PrimaryAssistant`, this sample includes an `UpstreamMcpAgent` endpoint
that consumes a **remote MCP server's tools** through `atmosphere-mcp-client`.
This closes the parity gap with [Anthropic's Claude Managed Agents](https://platform.claude.com/docs/en/managed-agents/overview),
which lists MCP servers as a first-class field on the Agent definition and
wires remote MCP tools in by default.

```bash
# Terminal 1: the upstream MCP server (port 8083)
./mvnw spring-boot:run -pl samples/spring-boot-mcp-server

# Terminal 2: this sample (port 8080)
LLM_API_KEY=$GEMINI_API_KEY ./mvnw spring-boot:run -pl samples/spring-boot-personal-assistant

# Connect to the outbound-MCP endpoint:
#   ws://localhost:8080/atmosphere/personal-assistant/upstream-tools
# The agent advertises the upstream's tools (atmosphere_version, list_users,
# etc.) and dispatches them as if they were local @AiTool methods.
```

### Cross-runtime overlay

The endpoint code is runtime-agnostic. Activate the `runtime-langchain4j`
Maven profile to swap the active `AgentRuntime` from Built-in (priority 0)
to LangChain4j (priority 100) without changing a line of code:

```bash
./mvnw spring-boot:run -pl samples/spring-boot-personal-assistant -Pruntime-langchain4j
```

### Operator visibility

`McpClientAdminController` exposes connection state, tool inventory, and
per-tool dispatch metrics:

```bash
curl http://localhost:8080/api/mcp-client/sources | jq
```

Validated end-to-end by `modules/integration-tests/e2e/mcp-client.spec.ts`
across both runtimes.

## Long-term memory — cross-session fact recall

The `UpstreamMcpAgent` endpoint is also a real consumer of the
`LongTermMemory` primitive, so the assistant remembers facts about a user
across separate WebSocket connections — exactly what a "long-lived,
memory-bearing assistant" should do.

The sample contains **zero memory wiring**. One switch in
`application.yml` enables the deep-agent preset:

```yaml
atmosphere:
  ai:
    deep-agent:
      enabled: true
```

and the framework attaches a `LongTermMemoryInterceptor`
(`InMemoryLongTermMemory` store, `onSessionClose` extraction via the
resolved `AgentRuntime`, 20-fact recall budget) to every AI endpoint,
alongside conversation memory, a conservative prompt-cache default, the
built-in `delegate_task` fleet tool on the `@Coordinator`, and durable
runs. Per-primitive runtime state is published at
`/api/console/info` under `deepAgent` — the console shows what actually
activated, not what was configured.

> **History note:** before the preset existed this sample wired long-term
> memory by hand — a `@Configuration`, a static holder, and a no-arg
> delegating interceptor (213 lines across three classes), forced by the
> `@AiEndpoint(interceptors=...)` scanner's no-arg instantiation. The
> preset deleted all of it; an app that needs a custom store now bridges a
> `LongTermMemory` bean instead (the container stashes it under the
> `org.atmosphere.ai.memory.store` framework property), or declares its
> own `LongTermMemoryInterceptor`, which suppresses the preset's.

How it runs on the user-message path:

- **Recall (`preProcess`)** — before each turn, stored facts for the
  request's `userId` are injected into the system prompt under a
  "Known facts about this user:" block.
- **Store (`onDisconnect`)** — when the session ends, the conversation is
  summarized into concise facts by the extraction runtime and saved for
  that user. With `onSessionClose` this is one extraction call per
  session, not per message.

`InMemoryLongTermMemory` keeps facts for the JVM's lifetime (lost on
restart) with zero external dependencies. For persistence across restarts,
register a `LongTermMemoryProvider` (ServiceLoader) or bridge a
`SqliteLongTermMemory` (`atmosphere-durable-sessions-sqlite`) /
`RedisLongTermMemory` (`atmosphere-durable-sessions-redis`) bean — the
preset picks it up through the same resolution chain.

> Facts are keyed by `userId`. The recall block only appears once an
> authenticated `userId` is present on the request and facts have been
> stored for that user; an anonymous request gets the original prompt
> unchanged.

Proven by `LongTermMemoryConsumerTest` (in the sample's `src/test`): a fact
extracted when one session closes is recalled into a later session's system
prompt for the same user, and never leaks to a different user.

## Notes

- The crew members return canned artifacts so the sample runs without
  internet access. A production deployment plugs each crew member into
  an MCP server via `ToolExtensibilityPoint`; credentials flow through
  `AgentIdentity`. The `UpstreamMcpAgent` above demonstrates the wire
  protocol of that production pattern against a local upstream.
- Defaults favor safety: permission mode is `DEFAULT`, network-capable
  tools (`send_message`) require explicit approval.
