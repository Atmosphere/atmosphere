# spring-boot-personal-assistant

A long-lived, memory-bearing personal assistant with a three-member
crew (scheduler, research, drafter) dispatched through
`InMemoryProtocolBridge`. Exercises the foundation primitives —
`AgentState`, `AgentWorkspace`, `AgentIdentity`, `ToolExtensibilityPoint`,
`AiGateway`, `ProtocolBridge` — end-to-end.

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

## Running

```bash
# From the repo root
./mvnw compile -pl samples/spring-boot-personal-assistant

# Run with an OpenAI key (Spring AI runtime is the default)
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

The sample defaults to Spring AI. Atmosphere discovers runtimes via
`ServiceLoader`; add one runtime artifact to the POM and set
`atmosphere.ai.runtime` in `application.yml` to switch:

- _(fallback)_ — Built-in OpenAI-compatible client
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

## Notes

- The crew members return canned artifacts so the sample runs without
  internet access. A production deployment plugs each crew member into
  an MCP server via `ToolExtensibilityPoint`; credentials flow through
  `AgentIdentity`. The `UpstreamMcpAgent` above demonstrates the wire
  protocol of that production pattern against a local upstream.
- Defaults favor safety: permission mode is `DEFAULT`, network-capable
  tools (`send_message`) require explicit approval.
