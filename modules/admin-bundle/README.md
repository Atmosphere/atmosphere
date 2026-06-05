# `atmosphere-admin-bundle` — Enterprise Console

Single-dependency Maven artifact that pulls in everything an enterprise
deployment needs to operate a fleet of Atmosphere AI agents: the streaming
runtime, the AI pipeline, the coordinator, RAG providers (in-memory +
Spring AI + LangChain4j + pgvector + Qdrant + Pinecone), durable sessions,
and the **admin control plane** with its dashboard, journal flow viewer,
governance decision viewer, **workflow authoring UI**, and **eval
dashboard**.

## What's in the bundle

| Subsystem | Provided by | Surface |
|-----------|-------------|---------|
| Streaming runtime | `atmosphere-runtime` | WebSocket / SSE / long-poll / WebTransport endpoints |
| Spring Boot wiring | `atmosphere-spring-boot-starter` | `AtmosphereAdmin` bean, auto-configs, `/atmosphere/admin/` static assets |
| Admin control plane | `atmosphere-admin` | `ControlAuthorizer` SPI, `ControlAuditLog`, REST endpoints under `/api/admin/*` |
| AI runtime SPI | `atmosphere-ai` | `AgentRuntime`, `AiPipeline`, `@AiEndpoint`, `@AiTool`, governance primitives |
| Coordinator | `atmosphere-coordinator` | `@Coordinator`, `AgentFleet`, `CoordinationJournal` (the flow viewer's data source) |
| Agent | `atmosphere-agent` | `@Agent`, `AgentState`, `AgentWorkspace` |
| RAG | `atmosphere-rag` | `ContextProvider` SPI + six built-in providers (in-memory, Spring AI VectorStore bridge, LangChain4j EmbeddingStore bridge, pgvector, Qdrant, Pinecone) |
| Durable checkpoints | `atmosphere-checkpoint` | `CheckpointStore` SPI for `PASSIVATION` |
| Durable sessions | `atmosphere-durable-sessions` + `atmosphere-durable-sessions-sqlite` | Persistent broadcaster state across restarts |

## What it deliberately does NOT include

- **No `AgentRuntime` adapter is pinned.** Operators pick one
  (`atmosphere-ai` ships the Built-in OpenAI-compatible runtime; or add
  `atmosphere-spring-ai`, `atmosphere-langchain4j`, `atmosphere-adk`, etc.).
  See [`docs/runtime-selection.md`](../../docs/runtime-selection.md) for
  the decision tree.
- **No vector-store driver dependency.** Spring AI / LangChain4j bring
  their own drivers; pgvector uses your existing JDBC `DataSource`; Qdrant
  and Pinecone use `java.net.http.HttpClient` (JDK stdlib). The bundle
  stays light by not pinning any vendor SDK.
- **No MCP / A2A protocol artifacts.** Add `atmosphere-mcp` or
  `atmosphere-a2a` when you need cross-agent protocol bridges; they layer
  on top of this bundle without duplication.

## Quick start

Add the bundle to your Spring Boot project:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-admin-bundle</artifactId>
    <version>4.0.50</version>
</dependency>

<!-- Pick exactly one AgentRuntime adapter -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-ai</artifactId>
    <version>4.0.50</version>
</dependency>
```

Run the app and visit:

| Page | URL | What it shows |
|------|-----|---------------|
| Dashboard | `/atmosphere/admin/` | Overview: broadcasters, agents, governance summary, runtime status |
| Journal flow | `/atmosphere/admin/flow.html` | Live agent-to-agent dispatch graph (read-only) |
| Workflow authoring | `/atmosphere/admin/workflow.html` | Create / edit `WorkflowManifest` JSON saved through `WorkflowStore` |
| Eval dashboard | `/atmosphere/admin/evals.html` | Pass-rate per golden eval baseline + recent run table |

## Hardening the bundle for production

The bundle ships fail-closed by default:

- `ControlAuthorizer` defaults to `DENY_ALL` — install a tenant-aware
  authorizer at startup.
- `atmosphere.admin.http-write-enabled` defaults to `false` — mutating
  REST endpoints return 403 until an operator opts in.
- `InMemoryWorkflowStore` and `InMemoryEvalRunStore` lose state on JVM
  restart — production deployments should swap in JDBC / Redis
  implementations of `WorkflowStore` and `EvalRunStore`.

The `ms-governance` flagship template ([samples/spring-boot-ms-governance-chat](../../samples/spring-boot-ms-governance-chat))
is the canonical "everything wired correctly" example — start from there
when productionizing.
