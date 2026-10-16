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
| `AgentWorkspace` | ServiceLoader-discovered workspace adapter (`OpenClawWorkspaceAdapter` + `AtmosphereNativeWorkspaceAdapter` fallback; third-party adapters plug in via `META-INF/services/`) |

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

## `@Message` results are room-wide

A web-facing agent may also declare a `@Message` method. It runs on a message
that no `@Command` handles, before the `@Prompt` pipeline, and a non-null return
value is broadcast to **every** subscriber of the agent's path, as a
`@ManagedService` `@Message` result is by default. It is not a per-client reply
and needs no tracking id. Only a `null` return passes the message on to the
`@Prompt` pipeline, which replies to the sender's connection alone. Return `null`
from `@Message` for anything one user's conversation should keep to itself;
`@Command` responses always go to the requesting client only.

## Where a `@Command` answer goes

A slash command's answer (and a confirmation prompt, and the answer to its
`yes` / `no`) goes to the connection of the client that sent it, never to the
other subscribers of the path, and is delivered like an AI reply: a
`streaming-text` frame and a `complete` frame under a session id of their own,
fresh for every answer, run through the path broadcaster's filters. Over
WebSocket, SSE and streaming they are written to the sender's socket or stream;
over long-polling they are kept whole and handed to the client's next poll. The
command arrives on a POST whose own response an SSE, streaming or long-polling
client does not read, so the sender's connection is resolved the way an `@AiEndpoint`
prompt's is (see
[Prompt routing over HTTP transports](../ai/README.md#prompt-routing-over-http-transports)):
the WebSocket frame's connection, or the one the POST's `X-Atmosphere-tracking-id`
names, waiting briefly for a long-polling client's next poll. That is done
before the command runs: when no connection of the sender resolves, the message
is refused as a prompt would be (`400`, a retryable `503`, or an error frame over
the socket) and the command does not run, so a client that sends it again does
not run it twice. The answer's own session is started then too, once the
message is known to be a command, before the command method runs: over
long-polling it reads, from the sender's connection, the identity the answer is
kept for, so an authenticated client's next poll still takes the answer when the
poll the command resolved to ends while a slow command runs. The same applies
to an `@AiEndpoint` that declares `@Command` methods and to an `@Coordinator`,
which route commands through the same handler.

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
