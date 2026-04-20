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
| `/api/admin/*` | Full REST API (~25 endpoints) |
| `/atmosphere/admin/events` | WebSocket event stream (via Atmosphere itself) |
| MCP tools | `atmosphere_overview`, `atmosphere_list_agents`, etc. |

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
   1. servlet `HttpServletRequest.getUserPrincipal()` (Spring Security,
      Jakarta Security, OIDC filters);
   2. `org.atmosphere.auth.principal` request attribute set by
      Atmosphere's own `AuthInterceptor` on `X-Atmosphere-Auth` token
      validation;
   3. `ai.userId` request attribute set by the AI pipeline.

   Returns 401 when all three are null/blank.
3. **`ControlAuthorizer`** — user-supplied `@Bean` wins; fallback is
   `REQUIRE_PRINCIPAL`. Returns 403 on deny.

Every decision (grant and deny) is recorded in the audit log.

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
