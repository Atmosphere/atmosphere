# atmosphere-a2a

Agent-to-Agent (A2A) protocol support for Atmosphere `@Agent`s. When
`atmosphere-a2a` is on the classpath, every `@Agent` automatically exposes an
A2A JSON-RPC endpoint (default `{basePath}/a2a`) and serves a discovery
**AgentCard** at `/.well-known/agent.json`. A2A clients can then call
`message/send` / `message/stream` to invoke the agent, and
`agent/authenticatedExtendedCard` to enumerate its skills.

The implementation targets A2A **v1.0.0** (typed `securitySchemes`, structured
`AgentProvider`, `signatures`, `supportedInterfaces`).

## ⚠️ Security: the A2A endpoint is unauthenticated by default

`message/send` drives the agent — **LLM dispatch and tool execution** — so the
A2A inbound endpoint is a mutating surface. Like every Atmosphere transport, it
ships with **no framework-enforced authentication**: the framework registers the
handler with no auth interceptor and delegates authentication to the deployer
(servlet container, Spring Security, or an API gateway), consistent with
Correctness Invariant #6 ("never ship insecure defaults without a startup
warning").

**In production you MUST front the A2A endpoint with authentication.** A startup
`WARN` is emitted for every A2A endpoint registered without a declared security
scheme:

```
A2A endpoint '/agent/a2a' for agent 'support-bot' is exposed WITHOUT
authentication — message/send invokes the agent (LLM dispatch + tool execution)
for any caller. Front it with auth ... and declare
org.atmosphere.a2a.securityScheme=bearer|apiKey so the served AgentCard is honest.
```

### Declaring the security scheme

Once you front the endpoint with auth, declare the scheme so the served
AgentCard honestly tells clients what credential to present. The framework does
**not** enforce the scheme (your gateway/filter does) — it relays the declared
contract onto the card (Correctness Invariant #5: advertise only what is true;
here, what the deployer declares they enforce).

| Init parameter | Default | Effect |
|----------------|---------|--------|
| `org.atmosphere.a2a.securityScheme` | *(unset)* | `bearer` or `apiKey` — advertised on the AgentCard's `securitySchemes` + `securityRequirements`. Unset/unknown leaves the card open and keeps the startup warning. |
| `org.atmosphere.a2a.apiKeyHeader` | `X-API-Key` | Header name for the `apiKey` scheme. |
| `org.atmosphere.a2a.suppressAuthWarning` | `false` | Set `true` once auth is enforced out-of-band to silence the startup warning. |

Spring Boot example (`application.yml`):

```yaml
atmosphere:
  init-params:
    org.atmosphere.a2a.securityScheme: bearer
```

The agent is then responsible for the actual enforcement — e.g. a Spring
Security filter chain requiring a bearer token on the `/**/a2a` path.

### Defense in depth already present

Even unauthenticated, two controls limit blast radius (but are **not** a
substitute for fronting the endpoint with auth):

- **Governance runs on the A2A path.** `message/send` dispatches through the
  shared `AiPipeline`, so any installed `GovernancePolicy` (deny rules, tool
  approval, guardrails) is enforced and fails closed.
- **Task access uses unguessable capability tokens.** Task ids are
  server-generated random UUIDs; `tasks/get` / `tasks/cancel` require knowing
  the id, and `tasks/list` requires a `contextId`.

## Other card decorations

| Init parameter | Default | Effect |
|----------------|---------|--------|
| `org.atmosphere.a2a.signCards` | `false` | Sign the served AgentCard with an ephemeral Ed25519 key (tamper-detection; key rotates on restart). |
| `org.atmosphere.a2a.pushNotifications` | `false` | Enable webhook push notifications on terminal task state. |

## Sample

See [`samples/spring-boot-a2a-agent`](../../samples/spring-boot-a2a-agent) for a
runnable A2A agent. **That sample ships without auth for local demonstration** —
read its README's security note before exposing it beyond localhost.
