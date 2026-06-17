# Atmosphere Admin

Management and control plane for Atmosphere. Provides a real-time dashboard, REST API, WebSocket event stream, and MCP tools for inspecting and controlling broadcasters, agents, coordinators, A2A tasks, AI runtimes, and protocol registries at runtime.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-admin</artifactId>
    <version>${project.version}</version>
</dependency>
```

Add this alongside your Atmosphere starter. The admin control plane auto-configures when the dependency is on the classpath.

## What You Get

| URL | Description |
|-----|-------------|
| `/atmosphere/admin/` | Real-time dashboard UI with live event feed |
| `/api/admin/overview` | System overview JSON (status, connections, agents, tasks, runtime) |
| `/api/admin/*` | Full REST API (63 endpoints across `AtmosphereAdminEndpoint`'s `@GetMapping` / `@PostMapping` / `@DeleteMapping` declarations) |
| `/atmosphere/admin/events` | WebSocket event stream (via Atmosphere itself) |
| MCP tools | `atmosphere_overview`, `atmosphere_list_agents`, etc. |

## Packaging Matrix

| Packaging | Dashboard | REST API | Event stream | Notes |
|-----------|-----------|----------|--------------|-------|
| Spring Boot starter | `/atmosphere/admin/` | `/api/admin/*` | `/atmosphere/admin/events` | Add `atmosphere-admin`; `AtmosphereAdminEndpoint` wires the dashboard, flow viewer, governance endpoints, and event handler. |
| Quarkus extension | `/admin/` | `/api/admin/*` | `/atmosphere/admin/events` | Add the Quarkus admin extension; Quarkus serves the console at the shorter `/admin/` path while preserving the REST API. |
| Servlet / embedded | `/atmosphere/admin/` when registered | `/api/admin/*` when registered | `/atmosphere/admin/events` | Register the admin handlers explicitly with the framework. |

The Spring Boot starter is the blessed enterprise packaging path today because
it wires admin, governance, runtime discovery, and coordinator journal bridges
from regular Spring beans. Quarkus is supported for operator inspection and
native-image deployments; mutating endpoints use the same `ControlAuthorizer`
SPI and fail closed unless write operations are explicitly enabled.

## Dashboard

The admin dashboard is a self-contained HTML/CSS/JS page served at `/atmosphere/admin/`. No build step required.

**Tabs:**
- **Dashboard** — Live counters (status, connections, broadcasters, agents, sessions, tasks, AI runtime), real-time event feed, broadcaster list, connected resources table
- **Agents** — Registered agents with name, version, path, headless flag, protocol badges (a2a, mcp, agui). AI runtimes with capabilities. MCP tool registry.
- **Journal** — Coordination event viewer with filtering by coordination ID or agent name
- **Control** — Broadcast messages, disconnect resources, cancel A2A tasks. All write operations logged in the audit trail.

The event feed streams `AdminEvent` instances over WebSocket — the dashboard eats its own dog food by connecting to Atmosphere's own transport layer.

## REST API

### Read Operations

| Endpoint | Description |
|----------|-------------|
| `GET /api/admin/overview` | Aggregated system snapshot |
| `GET /api/admin/broadcasters` | All active broadcasters |
| `GET /api/admin/broadcasters/detail?id=...` | Single broadcaster detail with subscribers |
| `GET /api/admin/resources` | All connected resources (UUID, transport, broadcaster) |
| `GET /api/admin/handlers` | Registered Atmosphere handlers |
| `GET /api/admin/interceptors` | Interceptor chain |
| `GET /api/admin/agents` | All registered agents (name, version, headless, protocols) |
| `GET /api/admin/agents/{name}` | Single agent detail |
| `GET /api/admin/agents/{name}/sessions` | Active sessions for an agent |
| `GET /api/admin/coordinators` | Coordinator fleet summaries |
| `GET /api/admin/coordinators/{name}/fleet` | Fleet detail (agents, availability, weight) |
| `GET /api/admin/journal` | Query coordination events (params: coordinationId, agent, since, until, limit) |
| `GET /api/admin/journal/{id}` | Events for a specific coordination |
| `GET /api/admin/journal/{id}/log` | Formatted log output |
| `GET /api/admin/tasks` | A2A tasks (param: contextId) |
| `GET /api/admin/tasks/{taskId}` | Task detail (state, messages, artifacts) |
| `GET /api/admin/runtimes` | Available AI runtimes with capabilities |
| `GET /api/admin/runtimes/active` | Currently active runtime |
| `GET /api/admin/mcp/tools` | Registered MCP tools |
| `GET /api/admin/mcp/resources` | Registered MCP resources |
| `GET /api/admin/mcp/prompts` | Registered MCP prompts |
| `GET /api/admin/audit` | Recent control action audit trail |

### Write Operations

| Endpoint | Description |
|----------|-------------|
| `POST /api/admin/broadcasters/broadcast` | Broadcast message (`{"broadcasterId":"...","message":"..."}`) |
| `POST /api/admin/broadcasters/unicast` | Unicast to resource (`{"broadcasterId":"...","uuid":"...","message":"..."}`) |
| `DELETE /api/admin/broadcasters/destroy?id=...` | Destroy a broadcaster |
| `DELETE /api/admin/resources/{uuid}` | Disconnect a client |
| `POST /api/admin/resources/{uuid}/resume` | Resume a suspended resource |
| `POST /api/admin/tasks/{taskId}/cancel` | Cancel an A2A task |

All write operations are recorded in the audit log.

Every mutating endpoint passes through three gates (Correctness
Invariant #6 — Security):

1. **Feature flag** — `atmosphere.admin.http-write-enabled=true`.
   Defaults off. Consulted on every call (dynamic) so an operator can
   flip the emergency-write switch without restarting.
2. **Authenticated principal** — resolved in this order so the
   gate works across every auth stack the framework supports:
   1. servlet / Jakarta REST `getUserPrincipal()` (Spring Security,
      Jakarta Security, OIDC filters);
   2. `org.atmosphere.auth.principal` request attribute set by
      Atmosphere's own `AuthInterceptor` on `X-Atmosphere-Auth` token
      validation;
   3. `ai.userId` request attribute set by the AI pipeline;
   4. **Quarkus only** — `X-Atmosphere-Auth` header compared
      constant-time against `atmosphere.admin.auth.token`; on match
      a synthetic principal is admitted. Intended for sample fixtures
      and operator tooling that have not yet integrated Jakarta
      Security.

   Returns 401 when all applicable sources are null/blank.
3. **`ControlAuthorizer`** — user-supplied `@Bean` / CDI bean wins;
   fallback is `REQUIRE_PRINCIPAL`. Returns 403 on deny.

Every decision (grant and deny) is recorded in the audit log.

#### Opt-in read-side auth

Default posture keeps `GET` / `HEAD` / `OPTIONS` on `/api/admin/*`
open so local demo consoles and dashboards work without credentials.
Set `atmosphere.admin.http-read-auth-required=true` to require the
same principal chain (minus `ControlAuthorizer`) on read endpoints —
anonymous readers then receive `401`. Wired by
`AdminApiAuthFilter` on Spring and `AdminReadAuthFilter`
(a JAX-RS `@Provider`) on Quarkus. Multi-tenant operators who
expose `/api/admin/*` on a routable network flip this one flag.

#### Spring Boot operator setup

```java
@Bean
TokenValidator myTokenValidator() {
  return token -> validateAndResolvePrincipal(token);  // your JWT lib
}

@Bean
ControlAuthorizer roleScoped() {
  return (action, target, principal) ->
      roles(principal).contains("atmosphere-admin");
}
```

`application.yml`:

```yaml
atmosphere:
  admin:
    http-write-enabled: true
```

The starter's `AdminApiAuthFilter` reads `X-Atmosphere-Auth` on
`/api/admin/*` and populates `getUserPrincipal()` — no servlet
filter-chain wiring required.

#### Quarkus operator setup

Produce a CDI `ControlAuthorizer`:

```java
@ApplicationScoped
public class RoleAuthorizer implements ControlAuthorizer {
  @Override
  public boolean authorize(String action, String target, String principal) {
    return principal != null && isAdmin(principal);
  }
}
```

`application.properties`:

```properties
atmosphere.admin.http-write-enabled=true
```

Jakarta Security (e.g. `quarkus-smallrye-jwt`) populates
`SecurityContext.getUserPrincipal()` natively; the gate picks it up
without extra wiring. Atmosphere `AuthInterceptor` populates the
`org.atmosphere.auth.principal` attribute, so either auth stack works.

### Agent-to-Agent Flow Viewer

| Endpoint | Description |
|----------|-------------|
| `GET /api/admin/flow[?lookbackMinutes=N]` | Render the coordination journal as a graph (nodes=agents, edges=dispatches) |
| `GET /api/admin/flow/{coordinationId}` | Same graph scoped to a single run |

Operator checks:

```bash
curl http://localhost:8080/api/admin/flow?lookbackMinutes=30
curl http://localhost:8080/api/admin/flow/<coordination-id>
curl http://localhost:8080/api/admin/journal/<coordination-id>/log
```

Payload shape:

```json
{
  "nodes": [{"id": "ceo", "label": "ceo"}, {"id": "research-agent", "label": "research-agent"}],
  "edges": [
    {"from": "ceo", "to": "research-agent",
     "dispatches": 3, "successes": 3, "failures": 0,
     "averageDurationMs": 142}
  ]
}
```

The viewer attributes edges by `coordinationId` — concurrent
coordinator runs that interleave their events in the journal stay
correctly scoped to their own coordinator. Closes the 44% gap the
Dynatrace 2026 report flagged around manual agent-to-agent flow
review.

Backed by `atmosphere-coordinator`'s `CoordinationJournal` (Spring bean
bridge via `AtmosphereCoordinatorAutoConfiguration`). When no journal
is installed the endpoint returns empty nodes/edges rather than
failing.

### Governance Decision Viewer

When `atmosphere-ai` governance is on the classpath, the admin module exposes
read-side decision inspection and health checks:

```bash
curl http://localhost:8080/api/admin/governance/policies
curl http://localhost:8080/api/admin/governance/health
curl http://localhost:8080/api/admin/governance/decisions
curl http://localhost:8080/api/admin/governance/agt-verify
```

Mutating governance operations, including the kill switch, require
`atmosphere.admin.http-write-enabled=true`, an authenticated principal, and a
grant from `ControlAuthorizer`.

### Workflow Authoring

`WorkflowManifest` is the JSON authoring/persistence record for a
workflow — a directed graph of nodes (agent / branch / fan-out /
approval) connected by edges, intended to be dispatched through
`@Coordinator` + `AgentFleet`. The manifest format, validation, and
storage ship today; the runtime that executes a manifest by dispatching
each node is not yet wired. The admin endpoint lets operators
list / create / edit / delete workflows from the UI at
`/atmosphere/admin/workflow.html`:

```bash
curl http://localhost:8080/api/admin/workflow                  # list
curl http://localhost:8080/api/admin/workflow/{id}             # one
curl -X POST http://localhost:8080/api/admin/workflow \         # save
     -H 'Content-Type: application/json' -d '{…manifest…}'
curl -X DELETE http://localhost:8080/api/admin/workflow/{id}    # delete
```

`POST` and `DELETE` route through `ControlAuthorizer` (`workflow.write`
/ `workflow.delete`) and emit a `ControlAuditLog` entry. The
caller-supplied `version` field implements optimistic concurrency —
the server returns `409` when it does not equal `existing.version + 1`.

`WorkflowStore` is the persistence SPI. `InMemoryWorkflowStore` is the
default (loses state on JVM restart); production deployments register a
JDBC- / Redis-backed implementation via a Spring `@Bean` (the auto-config
picks it up via `ObjectProvider<WorkflowStore>`).

### Eval Dashboard

The eval dashboard surfaces LLM-as-judge results submitted by CI so an
operator sees pass-rate trends per `GoldenEvalBaseline` without leaving
the control plane. CI pipelines `POST` an `EvalRun` JSON after every
golden-eval run:

```bash
curl http://localhost:8080/api/admin/evals/runs                # most-recent runs
curl 'http://localhost:8080/api/admin/evals/runs?baseline=intent-support'
curl http://localhost:8080/api/admin/evals/baselines           # pass-rate per baseline
curl -X POST http://localhost:8080/api/admin/evals/runs \       # record (CI)
     -H 'Content-Type: application/json' -d '{…run…}'
```

UI lives at `/atmosphere/admin/evals.html`. `EvalRunStore` is the SPI;
the default `InMemoryEvalRunStore` is a bounded ring buffer (500 runs
per baseline, oldest evicted) so a long-running deployment does not
accumulate unbounded history.

## WebSocket Event Stream

Connect to `/atmosphere/admin/events` to receive real-time `AdminEvent` JSON:

```json
{"type":"ResourceConnected","uuid":"abc123","transport":"WEBSOCKET","broadcaster":"/atmosphere/agent/ceo","timestamp":"..."}
{"type":"MessageBroadcast","broadcasterId":"/atmosphere/agent/ceo","resourceCount":3,"timestamp":"..."}
{"type":"AgentSessionStarted","agentName":"ceo","sessionId":"xyz789","timestamp":"..."}
```

### Event Types

| Event | Fields |
|-------|--------|
| `ResourceConnected` | uuid, transport, broadcaster |
| `ResourceDisconnected` | uuid, reason |
| `BroadcasterCreated` | id |
| `BroadcasterDestroyed` | id |
| `MessageBroadcast` | broadcasterId, resourceCount |
| `AgentSessionStarted` | agentName, sessionId |
| `AgentSessionEnded` | agentName, sessionId, duration, messageCount |
| `TaskStateChanged` | taskId, oldState, newState |
| `AgentDispatched` | coordinationId, agentName, skill |
| `AgentCompleted` | coordinationId, agentName, duration |
| `ControlActionExecuted` | principal, action, target, success |

## MCP Tools (AI-manages-AI)

When `atmosphere-mcp` is on the classpath, admin operations are registered as MCP tools — enabling an AI operator agent to inspect and control the fleet.

**Read tools** (always registered): `atmosphere_overview`, `atmosphere_list_broadcasters`, `atmosphere_list_resources`, `atmosphere_list_agents`, `atmosphere_agent_sessions`, `atmosphere_list_handlers`, `atmosphere_list_interceptors`, `atmosphere_audit_log`, plus optional subsystem tools.

When `atmosphere-verifier` is on the classpath, the static plan-and-verify ("Guardians") stack is also exposed read-only: `atmosphere_verifier_summary` (active chain, SMT solver, policy), `atmosphere_verifier_examples`, and `atmosphere_verifier_check` (plan a goal and run every verifier over the resulting plan *without executing it* — status `verified`/`refused` with the per-verifier violations). The mutating verify-then-execute path stays behind the admin write gate.

**Write tools** (opt-in via `atmosphere.admin.mcp-write-tools=true`): `atmosphere_broadcast`, `atmosphere_disconnect_resource`, `atmosphere_destroy_broadcaster`, `atmosphere_cancel_task`. Gated by `ControlAuthorizer` SPI.

## Configuration

```properties
atmosphere.admin.enabled=true              # Master kill switch (default: true)
atmosphere.admin.mcp-tools=true            # Register read MCP tools (default: true)
atmosphere.admin.mcp-write-tools=false     # Register write MCP tools (default: false)
```

## Key Components

| Class / Interface | Description |
|-------------------|-------------|
| `AtmosphereAdmin` | Central facade aggregating all domain controllers |
| `FrameworkController` | Broadcaster, resource, handler, interceptor operations |
| `AgentController` | Agent discovery, session listing, metadata extraction |
| `CoordinatorController` | Fleet inspection, coordination journal queries |
| `TaskController` | A2A task listing, detail, cancellation |
| `AiRuntimeController` | Runtime listing, active runtime, capabilities |
| `McpController` | MCP tool/resource/prompt registry inspection |
| `AdminEvent` | Sealed interface — 11 event types for real-time streaming |
| `AdminEventHandler` | Atmosphere handler at `/atmosphere/admin/events` |
| `AdminEventProducer` | Hooks into framework listeners, produces events |
| `AdminMcpBridge` | Registers operations as MCP tools |
| `ControlAuthorizer` | SPI for gating write operations |
| `ControlAuditLog` | In-memory ring buffer for write operation audit |
| `AgentSessionRegistry` | Tracks active sessions per agent (in `atmosphere-agent` module) |

## Requirements

- Java 21+
- `atmosphere-runtime` (transitive)
- Optional: `atmosphere-ai`, `atmosphere-agent`, `atmosphere-coordinator`, `atmosphere-a2a`, `atmosphere-mcp`
