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
  and trace emission (choke-point wire-in lands in Phase 1.5).

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
`ServiceLoader`; add any of the other six runtime artifacts to the POM and
set `atmosphere.ai.runtime` in `application.yml` to switch:

- `atmosphere-adk` — Google ADK
- `atmosphere-langchain4j` — LangChain4j
- `atmosphere-koog` — Koog (Kotlin)
- `atmosphere-semantic-kernel` — Semantic Kernel
- `atmosphere-embabel` — Embabel
- _(default)_ — Built-in OpenAI-compatible client

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

## Notes

- The crew members return canned artifacts so the sample runs without
  internet access. A production deployment plugs each crew member into
  an MCP server via `ToolExtensibilityPoint`; credentials flow through
  `AgentIdentity`.
- Defaults favor safety: permission mode is `DEFAULT`, network-capable
  tools (`send_message`) require explicit approval.
