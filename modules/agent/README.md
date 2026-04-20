# atmosphere-agent

Annotation-driven `@Agent` processor and protocol bridging for single
AI agents. Pairs with `atmosphere-coordinator` (fleets of agents) and
with `atmosphere-ai` (the underlying pipeline).

## What `@Agent` is

A plain Java class becomes a web-reachable AI agent by adding three
annotations:

```java
@Agent(name = "support-agent",
       version = "1.0.0",
       description = "Support-tier agent that answers billing questions")
public class SupportAgent {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }

    @AiTool(description = "Open a billing ticket on behalf of the user")
    public Ticket openTicket(String customerId, String reason) { ... }
}
```

At startup `AgentProcessor` walks the class path, finds every
`@Agent`-annotated class, and:

1. Registers an Atmosphere handler at `/atmosphere/agent/{name}`.
2. Wires the `@Prompt` method into an `AiEndpointHandler` (the same
   pipeline the standalone `@AiEndpoint` uses — one code path).
3. Bridges the agent to every protocol module on the classpath:
   `atmosphere-a2a` → `/atmosphere/a2a/{name}`, `atmosphere-mcp` →
   `/atmosphere/agent/{name}/mcp`, `atmosphere-agui` → `/atmosphere/agent/{name}/agui`.
4. Builds per-agent foundation primitives (`AgentState`, `AgentIdentity`,
   `AgentWorkspace`) and publishes them into the handler's injectables
   map so `@Prompt` / `@AiTool` methods can declare SPI types as
   parameters.

## Framework-scoped resolution pattern

Every optional dependency resolves through `framework.getAtmosphereConfig().properties()`
first, then `ServiceLoader`, then a safe fallback — same template used by
`AsyncSupport`, `Broadcaster`, `CoordinationJournal`. Two consequences:

- DI containers bridge their beans by writing to the well-known property
  key. Spring Boot's `AtmosphereAutoConfiguration` publishes
  `AgentState` / `AgentIdentity` / `FactResolver` / `CoordinationJournal`
  this way.
- Plain-servlet / Quarkus / embedded deployments discover the same
  primitives via `META-INF/services/*` without any framework changes.

## Registration log line

Watch for this on boot:

```
Agent 'support-agent' registered at /atmosphere/agent/support-agent (class: SupportAgent, commands: 0, tools: 1, memory: on(max=50), protocols: [a2a, mcp])
```

The `protocols` segment reflects runtime-resolved state — if
`atmosphere-a2a` isn't on the classpath, `a2a` doesn't appear. This is
Correctness Invariant #5 — report only confirmed runtime state, never
configuration intent.

## Per-agent injectable primitives

`AgentProcessor.buildFoundationPrimitives` creates one instance of each
foundation primitive per agent, rooted at
`~/.atmosphere/workspace/agents/{name}/` by default:

| Primitive | Use |
|-----------|-----|
| `AgentState` (`FileSystemAgentState`) | Durable per-agent state between restarts |
| `AgentIdentity` (`InMemoryAgentIdentity` + `AtmosphereEncryptedCredentialStore`) | Per-user `PermissionMode` + encrypted credential store |
| `AgentWorkspace` | ServiceLoader-discovered workspace adapter (OpenClaw, SWE-bench, none) |

`@Prompt` / `@AiTool` method parameters of any of these types are
injected from the agent-scoped map — no ThreadLocal, no static holder
(see `feedback_primitive_needs_consumer.md` for the history of why the
holder approach was abandoned).

## Headless vs web-facing

Two `@Agent` shapes:

- **Headless** (`@Agent(headless = true)`): no `@Prompt` method; the
  agent is reachable only via A2A/MCP. Use for specialist agents driven
  by a parent coordinator. Log format: `Agent 'research-agent' registered (headless, protocols: [a2a, mcp])`.
- **Web-facing**: `@Prompt` method present; reachable via WebSocket/SSE
  at `/atmosphere/agent/{name}`. Log format: `Agent '...' registered at /atmosphere/agent/{name} (...)`.

## @RequiresApproval

Mark the sensitive subset of `@AiTool` methods with `@RequiresApproval`:

```java
@AiTool(description = "Issue a refund to a customer account")
@RequiresApproval(
    message = "Confirm the refund amount and destination.",
    timeoutSeconds = 3600
)
public RefundResult issueRefund(String customerId, BigDecimal amount) { ... }
```

`ToolExecutionHelper` consults the session-scoped `PermissionMode`
(`DEFAULT` / `PLAN` / `ACCEPT_EDITS` / `BYPASS` / `DENY_ALL`) first and
the per-tool annotation second. See the
[trust-phases tutorial](../../../atmosphere.github.io/docs/src/content/docs/tutorial/25-trust-phases.md)
for the three-phase adoption pattern.

## Testing notes

- `AgentProcessorTest` pins the registration log format.
- `AgentHandlerTest` covers the request/response loop.
- Integration samples (`spring-boot-personal-assistant`,
  `spring-boot-coding-agent`) exercise the whole chain end-to-end in CI
  via the `foundation-e2e.yml` workflow (Playwright specs drive the
  bundled AI console).

## Related modules

- [`atmosphere-coordinator`](../coordinator/README.md) — `@Coordinator` +
  `@Fleet` orchestration across multiple `@Agent` instances.
- [`atmosphere-ai`](../ai/README.md) — the pipeline every agent's
  `@Prompt` invokes, with guardrails, fact resolver, metrics, and tool
  registry.
- [`atmosphere-admin`](../admin/README.md) — control plane (REST + MCP +
  flow viewer) that enumerates running agents.
