# Atmosphere 4.0 Samples

Example applications demonstrating Atmosphere 4.0 across different deployment targets.

All samples inherit their Atmosphere version from the parent POM. The target stack is Java 21, Spring Boot 4.0.7, and Quarkus 3.36.0.

### Chat & Messaging

| Sample | Stack | Packaging | Rooms | Metrics | Native Image |
|--------|-------|-----------|-------|---------|-------------|
| [chat](chat/) | Servlet (WAR) | WAR | — | — | — |
| [spring-boot-chat](spring-boot-chat/) | Spring Boot 4.0.7 | JAR | ✅ | ✅ | ✅ |
| [quarkus-chat](quarkus-chat/) | Quarkus 3.36.0 | JAR | — | — | ✅ |
| [quarkus-ai-chat](quarkus-ai-chat/) | Quarkus 3.36.0 + LangChain4j | JAR | — | — | ✅ |
| [embedded-jetty-websocket-chat](embedded-jetty-websocket-chat/) | Embedded Jetty | JAR | — | — | — |
| [kotlin-dsl-chat](kotlin-dsl-chat/) | Kotlin DSL + coroutines (Embedded Jetty) | JAR | — | — | — |
| [grpc-chat](grpc-chat/) | gRPC + Spring Boot | JAR | — | — | — |

### AI / LLM Streaming

| Sample | AI Backend | Tool Calling | Description |
|--------|-----------|-------------|-------------|
| [spring-boot-ai-chat](spring-boot-ai-chat/) | Built-in (Gemini/OpenAI/Ollama) | — | Basic AI streaming with `@AiEndpoint` |
| [spring-boot-ai-tools](spring-boot-ai-tools/) | Built-in / any `AgentRuntime` | `@AiTool` (portable) | Framework-agnostic tool calling pipeline with live `AiEvent` tool activity |
| [spring-boot-ai-classroom](spring-boot-ai-classroom/) | Built-in | — | Multi-room collaborative AI streaming ([Expo client](spring-boot-ai-classroom/expo-client/)) |
| [spring-boot-rag-chat](spring-boot-rag-chat/) | Built-in + Spring AI VectorStore | `@AiTool` | RAG agent with knowledge base search tools |
| [spring-boot-browser-agent](spring-boot-browser-agent/) | Cohere (Command) | `code_exec` (sandboxed) | Code-as-action agent — writes Playwright that drives a headless browser in an isolated container; screenshots stream to the Console live (**requires Docker**) |
| [spring-boot-spring-ai-advisors](spring-boot-spring-ai-advisors/) | Spring AI `ChatClient` (bound, offline) | — | Bind your own `ChatClient` via `SpringAiAgentRuntime.setChatClient(...)` — Atmosphere keeps your `defaultAdvisors(...)` and you attach more advisors per request |

### Agents (`@Agent` + `@Command`)

One agent class — slash commands and AI work on Web, Slack, Telegram, Discord, WhatsApp, and Messenger simultaneously.

| Sample | Features | Channels | Description |
|--------|----------|----------|-------------|
| [spring-boot-dentist-agent](spring-boot-dentist-agent/) | `@Agent`, `@Command`, `@AiTool`, skill file | Web + Slack + Telegram | Multi-channel dental emergency agent |
| [spring-boot-channels-chat](spring-boot-channels-chat/) | `@AiEndpoint`, channels | Web + Slack + Telegram + Discord + WhatsApp + Messenger | Omnichannel AI assistant |
| [spring-boot-rag-chat](spring-boot-rag-chat/) | `@Agent`, `@Command`, `@AiTool`, RAG | Web | Knowledge base agent with document search tools |
| [spring-boot-checkpoint-agent](spring-boot-checkpoint-agent/) | `@Coordinator`, `@Agent`, `CheckpointStore` | Web | Durable HITL workflow — approval-gated agent chaining |
| [spring-boot-multi-agent-startup-team](spring-boot-multi-agent-startup-team/) | `@Coordinator`, `@Fleet`, A2A, SQLite checkpoints, WebTransport | Web | A `@Coordinator` (CEO) dispatching to 4 `@Agent` specialists with parallel/sequential dispatch |
| [spring-boot-personal-assistant](spring-boot-personal-assistant/) | `@Coordinator`, `@Fleet`, `AgentState`, `AgentWorkspace`, `AgentIdentity`, `ToolExtensibilityPoint`, `InMemoryProtocolBridge` | Web | Long-lived memory-bearing assistant — primary delegates to scheduler / research / drafter crew over `InMemoryProtocolBridge`; ships an OpenClaw-compatible workspace |
| [spring-boot-coding-agent](spring-boot-coding-agent/) | `@Agent`, `Sandbox` | Web | Coding agent — clones a repo into a Docker sandbox, reads files |
| [spring-boot-guarded-email-agent](spring-boot-guarded-email-agent/) | `PlanVerifier`, `@Sink`, `NumericInvariant`, `SmtChecker`, `WorkflowExecutor` | Web | Plan-and-Verify (Meijer) — refuses unsafe plans before any tool fires: taint (exfiltration) + SMT (over-quota bulk send) |
| [spring-boot-orchestration-demo](spring-boot-orchestration-demo/) | `@Agent`, `session.handoff()`, `@RequiresApproval`, `@Command` | Web | Support Desk — agent handoffs with human approval gates |
| [spring-boot-ms-governance-chat](spring-boot-ms-governance-chat/) | `@AiEndpoint`, `PolicyAdmissionGate`, MS Agent Governance YAML | Web | Chat gated every turn by Microsoft Agent Governance Toolkit YAML policies |

### Agent Protocols

| Sample | Protocol | Description |
|--------|----------|-------------|
| [spring-boot-mcp-server](spring-boot-mcp-server/) | MCP | Model Context Protocol — expose tools, resources, prompts to AI agents |
| [spring-boot-a2a-agent](spring-boot-a2a-agent/) | A2A | Agent-to-Agent — discoverable skills via Agent Card, JSON-RPC 2.0 |
| [spring-boot-agui-chat](spring-boot-agui-chat/) | AG-UI | Real `@Agent` whose `AgentRuntime` output (LLM + `get_weather`/`get_time` `@AiTool` calls) streams as AG-UI events over SSE; demo fallback when no key |

### Infrastructure & Integration

| Sample | Stack | Description |
|--------|-------|-------------|
| [spring-boot-durable-sessions](spring-boot-durable-sessions/) | Spring Boot 4.0.7 | Persistent sessions with SQLite/Redis |
| [spring-boot-otel-chat](spring-boot-otel-chat/) | Spring Boot 4.0.7 | OpenTelemetry observability |
| [spring-boot-reattach-harness](spring-boot-reattach-harness/) | Spring Boot 4.0.7 | Deterministic harness for the mid-stream reattach contract (`RunRegistry` + `RunEventReplayBuffer`); driven by `e2e/tests/reattach.spec.ts` |
| [spring-boot-passivation-agent](spring-boot-passivation-agent/) | Spring Boot 4.0.7 + `atmosphere-checkpoint` | Snapshot a paused agent conversation and resume it from where it left off (`AgentPassivation` + `CheckpointStore`); proven offline by `PassivationDeliveryTest` |
| [spring-boot-admin-bundle](spring-boot-admin-bundle/) | Spring Boot 4.0.7 + `atmosphere-admin-bundle` (sole Atmosphere dep) | Single-dependency wiring proof — `AdminBundleWiringTest` boots a real context and asserts the auto-configured beans the bundle brings in (runtime, AI, coordinator, durable sessions) plus the RAG/checkpoint SPIs it aggregates |
| [shared-resources](shared-resources/) | — | Shared static assets (CSS, Grafana dashboard). Not a Maven module — no `pom.xml`. |

## Quick Start

### Atmosphere CLI (recommended)

The fastest way to try any sample:

```bash
# Install the CLI
curl -fsSL https://raw.githubusercontent.com/Atmosphere/atmosphere/main/cli/install.sh | sh

# Interactive picker — browse samples, pick one, run it
atmosphere install

# Or run a specific sample directly
atmosphere run spring-boot-chat
atmosphere run spring-boot-ai-chat --env LLM_API_KEY=your-key

# List all available samples
atmosphere list
atmosphere list --tag ai

# Scaffold a new project from a sample as a standalone starter
# (sparse-clones the sample and rewrites its pom.xml to resolve the
#  atmosphere-project parent from Maven Central — compiles out of the box)
atmosphere new my-chat-app --template chat
atmosphere new my-ai-app --template ai-chat
atmosphere new my-fleet --template multi-agent
atmosphere new my-classroom --template classroom
```

Available `--template` values: `chat`, `ai-chat`, `ai-tools`, `mcp-server`, `rag`, `agent`, `multi-agent`, `classroom`, `ms-governance`, `coding-agent`, `guarded-agent`, `assistant`, `browser-agent`. See [cli/README.md](../cli/README.md#available-templates) for the template-to-sample mapping.

### Flagship enterprise templates

Five production-shaped templates that demonstrate the canonical enterprise
agent shapes. Each is a real sample with a working backend, a working frontend,
and end-to-end tests — not a stub. Pick the one whose use case is closest to
yours; the rest of the catalog covers transport variants, observability
patterns, and framework-specific demos.

| Template | Sample | Use case | Key capabilities |
|----------|--------|----------|------------------|
| `rag` | [`spring-boot-rag-chat`](spring-boot-rag-chat) | RAG support bot over a chunked Markdown knowledge base | `ContextProvider` SPI, `RagChunker`, vector-store bridge, slash commands |
| `ai-tools` | [`spring-boot-ai-tools`](spring-boot-ai-tools) | Internal tool agent — portable `@AiTool` calls, cost metering, audit listener | `@AiTool`, `@RequiresApproval`, `CostMeteringInterceptor`, audit log |
| `guarded-agent` | [`spring-boot-guarded-email-agent`](spring-boot-guarded-email-agent) | Approval workflow — Plan-and-Verify gate that refuses unsafe tool-call plans before any tool fires | `PlanAndVerify`, durable HITL, `CheckpointStore`, replay |
| `coding-agent` | [`spring-boot-coding-agent`](spring-boot-coding-agent) | Coding agent — sandboxed git clone + file edit + AgentResumeHandle reattach | Sandbox SPI, `@Agent` skill files, reattach on disconnect |
| `ms-governance` | [`spring-boot-ms-governance-chat`](spring-boot-ms-governance-chat) | Governance demo — Microsoft Agent Governance Toolkit alignment, decision viewer, kill switch | `PolicyAdmissionGate`, `ControlAuthorizer`, decision log, mutating-endpoint auth |

```bash
atmosphere new my-support-bot      --template rag --runtime spring-ai
atmosphere new my-tool-agent       --template ai-tools
atmosphere new my-approval-agent   --template guarded-agent
atmosphere new my-coding-agent     --template coding-agent
atmosphere new my-governance-demo  --template ms-governance
```

### 10-minute enterprise agent

For a production-shaped AI service, start from the tool template instead of the
plain chat template:

```bash
atmosphere new support-agent --template ai-tools --runtime builtin
cd support-agent
export LLM_API_KEY=...
mvn spring-boot:run
```

That gives you streaming, portable `@AiTool` calls, HITL approval points, cost
metering, an audit listener, and `atmosphere-admin` via the Spring Boot starter
for the operator console, `/api/admin/runtimes`, governance decisions, and A2A
flow viewer. Add the `rag` template when proprietary documents need chunked
vector-store retrieval.

Or with npx (zero install — delegates to the `atmosphere` CLI):

```bash
npx create-atmosphere-app my-chat-app
npx create-atmosphere-app my-ai-app --template ai-chat
```

See [cli/README.md](../cli/README.md) for full CLI documentation.

### Manual Build

Each sample can be built independently:

```bash
# WAR sample (Jetty Maven plugin)
cd chat && mvn clean install && mvn jetty:run

# Spring Boot
cd spring-boot-chat && mvn clean package && java -jar target/*.jar

# Quarkus
cd quarkus-chat && mvn clean package && java -jar target/quarkus-app/quarkus-run.jar

# Embedded Jetty
cd embedded-jetty-websocket-chat && mvn clean install && mvn -Pserver
```

Most samples run on **http://localhost:8080**. A few AI samples use different ports so they can run simultaneously (for example `spring-boot-mcp-server` on 8083, `spring-boot-a2a-agent` on 8084, `spring-boot-agui-chat` on 8085, `spring-boot-ai-tools` and `spring-boot-browser-agent` on 8090 — run these two individually). Check each sample's `application.yml` / `application.properties` for the exact port.

### Picking an LLM provider

- **Gemini (free tier).** Fast to set up, but the free tier caps at roughly **20 requests/day** across all models. A single multi-agent sample (e.g. `spring-boot-multi-agent-startup-team`) can burn 5+ requests per turn, so expect `429 RESOURCE_EXHAUSTED` after a handful of prompts. Fine for a one-off demo, not for iterating on tool-heavy or coordinator samples.
- **OpenAI / Anthropic (paid).** Practical for local development — higher per-minute limits, no daily cap. Point `LLM_BASE_URL` / `LLM_API_KEY` at the provider and you are done.
- **Ollama (local).** Zero quota, no network required. Start `ollama serve`, then `export LLM_BASE_URL=http://localhost:11434/v1 LLM_MODEL=llama3.2` and every sample will hit the local model.
- **[`dravr-embacle`](https://github.com/dravrtx/dravr-embacle).** OpenAI-compatible proxy in front of the Claude Code CLI / GitHub Copilot CLI — useful when you want quota-free local testing without running Ollama.

Samples fall back to `DemoAgentRuntime` (canned responses through the real pipeline) when no LLM is configured, so the UI still demos even without a key.

## The Same Handler Everywhere

The core `Chat.java` handler is nearly identical across all chat samples:

```java
@ManagedService(path = "/chat")
public class Chat {

    @Ready
    public void onReady() { }

    @Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) {
        return message;
    }
}
```

Only packaging and configuration differ — your business logic is portable across Spring Boot, Quarkus, and plain Servlet containers.

## Unified @Agent Feature Matrix

| Sample | Tool calling | HITL approval | Prompt caching | Lifecycle listeners | Embeddings |
|--------|:------------:|:-------------:|:--------------:|:-------------------:|:----------:|
| [spring-boot-ai-chat](spring-boot-ai-chat/) | — | — | ✅ `promptCache = CONSERVATIVE` | — | startup log |
| [spring-boot-ai-tools](spring-boot-ai-tools/) | ✅ | ✅ `@RequiresApproval` | — | ✅ `ToolAuditListener` | — |
| [spring-boot-rag-chat](spring-boot-rag-chat/) | ✅ | — | — | — | ✅ via `ContextProvider` |
| [spring-boot-multi-agent-startup-team](spring-boot-multi-agent-startup-team/) | ✅ | ✅ | — | — | — |
| [spring-boot-dentist-agent](spring-boot-dentist-agent/) | ✅ | ✅ | — | — | — |
| [spring-boot-checkpoint-agent](spring-boot-checkpoint-agent/) | ✅ | ✅ durable | — | — | — |
| [spring-boot-orchestration-demo](spring-boot-orchestration-demo/) | ✅ | ✅ | — | — | — |

**Feature reference:**
- **Tool calling + HITL approval**: `@AiTool` + `@RequiresApproval`; every runtime routes through `ToolExecutionHelper.executeWithApproval`.
- **Multi-modal**: `Content.Image` / `Audio` / `File` on `context.parts()`; Spring AI `Media`, LC4j `ImageContent`, ADK `Part.fromBytes`, Built-in OpenAI `image_url`.
- **Lifecycle listeners**: implement `AgentLifecycleListener` and attach via `context.withListeners(...)`. Bridges fire `onToolCall`/`onToolResult` on every round.
- **Prompt caching**: `@AiEndpoint(promptCache = CachePolicy.CONSERVATIVE)` or `CacheHint` on context metadata. Spring AI/LC4j/Built-in emit OpenAI `prompt_cache_key`; pipeline-level `ResponseCache` also replays identical requests across all runtimes.
- **Embeddings**: `EmbeddingRuntime` SPI auto-discovered via `ServiceLoader`. Ships for Spring AI, Spring AI Alibaba, LangChain4j, Semantic Kernel, Embabel, Koog, and the Built-in OpenAI client (7 runtimes).
- **Retry policy**: `@AiEndpoint(retry = @Retry(maxRetries = 5))` or programmatic `context.withRetryPolicy(...)`.

See the [atmosphere-ai capability matrix](../modules/ai/README.md#capability-matrix) for the cross-runtime support view.

## Governance coverage across samples

The governance policy plane has four axes: accepting MS Agent Governance
Toolkit YAML, architectural scope enforcement, signed commitment records
on cross-agent dispatch, and OWASP + compliance evidence. Each sample
below exercises at least one axis with CI-verified end-to-end tests that
boot the Spring Boot context and assert decisions fire live. See
[docs/governance-policy-plane.md](../docs/governance-policy-plane.md)
for the full picture.

| Sample | MS YAML | Scope | Commitments | OWASP | Atmosphere-unique angle |
|---|:-:|:-:|:-:|:-:|---|
| [spring-boot-ms-governance-chat](spring-boot-ms-governance-chat/) | ✅ | ✅ | — | ✅ | MS Agent Governance Toolkit YAML accepted verbatim |
| [spring-boot-ai-classroom](spring-boot-ai-classroom/) | ✅ | ✅ | — | — | **Per-request scope install** — one endpoint, four YAML-backed scopes |
| [spring-boot-multi-agent-startup-team](spring-boot-multi-agent-startup-team/) | ✅ | ✅ | ✅ | ✅ | Streaming + signed `CommitmentRecord`s + `GovernanceFleetInterceptor` at every dispatch |
| [spring-boot-checkpoint-agent](spring-boot-checkpoint-agent/) | — | — | ✅ | — | **Signed audit trail across HITL pause** — durable + cryptographic in one |
| [spring-boot-mcp-server](spring-boot-mcp-server/) | — | ✅ | — | ✅ | MCP tool-call governance over the streaming transport (MS gateway is HTTP-only) |

### Admin control plane — try it on any of the samples above

```bash
curl http://localhost:8080/api/admin/governance/policies       # installed policies + sha256 digests
curl http://localhost:8080/api/admin/governance/health         # kill-switch + dry-run + slos
curl http://localhost:8080/api/admin/governance/agt-verify     # OWASP + compliance, agt verify schema
curl http://localhost:8080/api/admin/governance/decisions      # recent policy decisions (ring buffer)

# Break-glass: halt all AI traffic without a redeploy
curl -X POST http://localhost:8080/api/admin/governance/kill-switch/arm \
     -H 'Content-Type: application/json' \
     -d '{"reason":"incident","operator":"oncall"}'
```

## Documentation

- [Full Documentation](https://atmosphere.github.io/docs/)
- [Getting Started with Spring Boot](https://atmosphere.github.io/docs/integrations/spring-boot/)
- [Getting Started with Quarkus](https://atmosphere.github.io/docs/integrations/quarkus/)
- [Core Runtime](https://atmosphere.github.io/docs/reference/core/)
- [Governance policy plane](../docs/governance-policy-plane.md)
