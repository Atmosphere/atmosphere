<p align="center">
  <img src="logo.png" alt="Atmosphere" width="120"/>
</p>

<h1 align="center">Atmosphere</h1>

<p align="center">
  A framework for building streaming AI agents on the JVM. Atmosphere owns the transport layer — tokens flow from the LLM runtime to the client through a broadcaster you can filter, gate, and observe. <code>@Agent</code> declares the behavior; the framework handles streaming, tool calling, memory, reconnect, authorization, and cost accounting.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/org.atmosphere/atmosphere-runtime"><img src="https://img.shields.io/maven-central/v/org.atmosphere/atmosphere-runtime?label=Maven%20Central&color=blue" alt="Maven Central"/></a>
  <a href="https://www.npmjs.com/package/atmosphere.js"><img src="https://img.shields.io/npm/v/atmosphere.js?label=atmosphere.js&color=blue" alt="npm"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/ci.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI: Core"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/e2e.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/e2e.yml/badge.svg?branch=main" alt="CI: E2E"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/ci-js.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/ci-js.yml/badge.svg?branch=main" alt="CI: atmosphere.js"/></a>
</p>

---

A `@Agent` class runs on any of seven AI runtimes (built-in OpenAI, Spring AI, LangChain4j, Google ADK, Embabel, JetBrains Koog, Microsoft Semantic Kernel) and is served over any of five transports (WebTransport/HTTP3, WebSocket, SSE, long-polling, gRPC) plus three agent protocols (MCP, A2A, AG-UI) and six channels (web, Slack, Telegram, Discord, WhatsApp, Messenger). Runtime and transport are swapped by changing a Maven dependency.

Atmosphere owns the broadcaster, which enables capabilities a pure orchestration library cannot provide: per-token PII rewriting in flight, per-tenant cost ceilings that block dispatch at the gateway, durable reconnect that replays mid-stream events after a client drop, triple-gate authorization on the admin control plane.

## Quick Start

```bash
brew install Atmosphere/tap/atmosphere

# or

curl -fsSL https://raw.githubusercontent.com/Atmosphere/atmosphere/main/cli/install.sh | sh

# Run a built-in agent sample
atmosphere run spring-boot-multi-agent-startup-team

# Or scaffold your own project from a sample
atmosphere new my-agent --template ai-chat

# Swap the AI runtime by injecting the matching adapter (`builtin` is the default).
# Add `--force` to strip pre-pinned adapters first — useful on samples like ai-tools
# that already ship with one provider wired up.
atmosphere new my-agent --template ai-chat --runtime spring-ai

# Import a skill from an allowed skills repo
atmosphere import https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md
cd frontend-design && LLM_API_KEY=your-key ./mvnw spring-boot:run
```

## `@Agent`

One annotation. The framework wires everything based on what's in the class and what's on the classpath.

```java
// Registers this class as an agent — auto-discovered at startup.
// Endpoints are created based on which modules are on the classpath:
// WebSocket, MCP, A2A, AG-UI, Slack, Telegram, etc.
@Agent(name = "my-agent", description = "What this agent does")
public class MyAgent {

    // Handles user messages. The message is forwarded to whichever AI runtime
    // is on the classpath (Spring AI, LangChain4j, ADK, etc.) and the LLM
    // response is streamed back token-by-token through the session.
    @Prompt
    public void onMessage(String message, StreamingSession session) {
        session.stream(message);
    }

    // Slash command — executed directly, no LLM call.
    // Auto-listed in /help. Works on every channel (web, Slack, Telegram…).
    @Command(value = "/status", description = "Show status")
    public String status() {
        return "All systems operational";
    }

    // confirm = "..." enables human-in-the-loop: the client must approve
    // before the method body runs. The virtual thread parks until approval.
    @Command(value = "/reset", description = "Reset data",
             confirm = "This will delete all data. Are you sure?")
    public String reset() {
        return dataService.resetAll();
    }

    // Registered as a tool the LLM can invoke during inference.
    // Also exposed as an MCP tool if atmosphere-mcp is on the classpath.
    @AiTool(name = "lookup", description = "Look up data")
    public String lookup(@Param("query") String query) {
        return dataService.find(query);
    }
}
```

What this registers depends on which modules are on the classpath:

| Module on classpath | What gets registered |
|---|---|
| `atmosphere-agent` (required) | WebSocket endpoint at `/atmosphere/agent/my-agent` with streaming AI, conversation memory, `/help` auto-generation |
| `atmosphere-mcp` | MCP endpoint at `/atmosphere/agent/my-agent/mcp` |
| `atmosphere-a2a` | A2A endpoint at `/atmosphere/agent/my-agent/a2a` with Agent Card discovery |
| `atmosphere-agui` | AG-UI endpoint at `/atmosphere/agent/my-agent/agui` |
| `atmosphere-channels` + bot token | Same agent responds on Slack, Telegram, Discord, WhatsApp, Messenger |
| `atmosphere-admin` | Admin dashboard at `/atmosphere/admin/` with live event stream |
| (built-in) | Console UI at `/atmosphere/console/` |

## Modules

| Capability | Module | Key types |
|---|---|---|
| Streaming transports | `atmosphere-runtime` | WebTransport/HTTP3, WebSocket, SSE, long-polling, gRPC — negotiated via `AsyncSupport`; Jetty 12 QUIC native or Reactor Netty HTTP/3 sidecar |
| Agent declaration | `atmosphere-agent` | `@Agent`, `@Prompt`, `@Command`, `@AiTool`, `@RequiresApproval` |
| AI runtimes | `atmosphere-ai` + `atmosphere-{spring-ai,langchain4j,adk,koog,embabel,semantic-kernel}` | `AgentRuntime` SPI; capability matrix pinned in `AbstractAgentRuntimeContractTest` |
| Multi-agent coordination | `atmosphere-coordinator` | `@Coordinator`, `@Fleet`, `@AgentRef`; parallel / sequential / conditional routing; coordination journal |
| Agent protocols | `atmosphere-mcp`, `atmosphere-a2a`, `atmosphere-agui` | auto-registered endpoints per `@Agent` |
| Channels | `atmosphere-channels` | Slack, Telegram, Discord, WhatsApp, Messenger dispatch from one `@Command` |
| Memory | `atmosphere-ai` | sliding window, LLM summarization; durable via `atmosphere-durable-sessions` (SQLite / Redis) |
| Checkpoints | `atmosphere-checkpoint` | `CheckpointStore` — parent-chained snapshots, fork, resume by REST |
| Reconnect & replay | `atmosphere-durable-sessions` + `RunRegistry` in `atmosphere-ai` | clients reconnect with `X-Atmosphere-Run-Id`; `AiEndpointHandler` replays the mid-stream buffer to the new resource |
| Sandbox | `atmosphere-sandbox` | `Sandbox` / `SandboxProvider`; Docker default (`--network none`, argv-form exec, strict mount); `ServiceLoader` for Firecracker / Kata / E2B / Modal |
| Authentication | `atmosphere-runtime` | `TokenValidator`, `TokenRefresher`, `AuthInterceptor` — rejects at WebSocket / HTTP upgrade |
| Admin control plane | `atmosphere-admin` | `/atmosphere/admin/` UI, `/api/admin/*` REST, MCP tools; triple-gate (feature flag → Principal → `ControlAuthorizer`) |
| Flow viewer | `atmosphere-admin` | `GET /api/admin/flow` — JSON graph keyed by `coordinationId` (nodes, edges, success / failure / avg duration) |
| Grounded facts | `atmosphere-ai` | `FactResolver` SPI, per-turn; values escaped before prompt injection |
| Business tags | `atmosphere-ai` | `BusinessMetadata` → SLF4J MDC (tenant, customer, session, event kind) |
| Guardrails | `atmosphere-ai` | `PiiRedactionGuardrail`, `OutputLengthZScoreGuardrail` (tenant-partitioned), `CostCeilingGuardrail` |
| Governance policy plane | `atmosphere-ai` | `GovernancePolicy` SPI; YAML `PolicyParser` auto-detects Atmosphere-native + **Microsoft Agent Governance Toolkit** schema; `PolicyAdmissionGate` for non-pipeline paths; MS-compatible `POST /api/admin/governance/check` decision endpoint |
| Policy admission primitives | `atmosphere-ai` | `AllowListPolicy`, `DenyListPolicy`, `MessageLengthPolicy`, `RateLimitPolicy`, `ConcurrencyLimitPolicy`, `TimeWindowPolicy`, `MetadataPresencePolicy`, `AuthorizationPolicy`, `ConfidenceThresholdGuardrail` — all declarable in YAML and composable via `PolicyRing` |
| Ops primitives | `atmosphere-ai` | `KillSwitchPolicy` break-glass; `DryRunPolicy` shadow rollouts; `SwappablePolicy` hot-reload; `TimedPolicy` / `CountingPolicy` metrics wrappers; `SloTracker` SRE burn-rate; `PolicyHashDigest` supply-chain drift detection |
| Multi-agent governance | `atmosphere-coordinator` | `FleetInterceptor` SPI for per-dispatch gating; `GovernanceFleetInterceptor` bridges `FleetInterceptor` → `GovernancePolicy` chain at the coord→specialist edge |
| Commitment records | `atmosphere-coordinator` | W3C Verifiable-Credential-subtype records signed with Ed25519; `CommitmentRecordsFlag` flag-off default; `JournalingAgentFleet.signer()` installs per-session; admin Commitments tab renders verified records |
| Admin governance surface | `atmosphere-admin` + starter | `GET /governance/{policies,health,decisions,owasp,compliance,agt-verify}`; `POST /governance/{check,reload,kill-switch/arm,kill-switch/disarm}` — all verified end-to-end via `StartupTeamGovernanceE2ETest` |
| Compliance evidence | `atmosphere-ai` | OWASP Agentic Top 10 + EU AI Act / HIPAA / SOC2 matrices; CI-enforced via `OwaspMatrixPinTest`, `ComplianceMatrixPinTest`, and `EvidenceConsumerGrepPinTest` (asserts every claimed coverage row has a real production consumer) |
| Plan-and-verify | `atmosphere-verifier` | Static verification of LLM-emitted tool-call workflows ([Meijer, *Guardians of the Agents*, CACM Jan 2026](https://cacm.acm.org/research/guardians-of-the-agents/)); sealed `Workflow` AST; `PlanVerifier` SPI with `AllowlistVerifier` / `WellFormednessVerifier` / `CapabilityVerifier` / `TaintVerifier` / `AutomatonVerifier` / `SmtVerifier` (Z3-ready SPI); `@Sink` + `@RequiresCapability` annotations co-locate the policy with the protected code; CLI `verify` for offline plan inspection |
| Stream-level PII rewrite | `atmosphere-ai` | `PiiRedactionFilter` — `BroadcasterFilter` auto-installed on every present and future broadcaster when enabled; rewrites tokens before bytes flush to the client |
| Cost enforcement | `atmosphere-ai` | `CostCeilingAccountant` bridges `TokenUsage` → `CostCeilingGuardrail.addCost` keyed by `business.tenant.id`; outbound `@Prompt` blocks once the tenant hits budget |
| Observability | `atmosphere-runtime`, `atmosphere-ai` | OpenTelemetry spans, Micrometer metrics, token usage; `BusinessMdcBenchmark` pins the MDC hot-path cost |
| Permission modes | `atmosphere-ai` | `PermissionMode.DEFAULT` / `PLAN` / `ACCEPT_EDITS` / `BYPASS` / `DENY_ALL` — runtime config, not redeploy |
| Evaluation | `atmosphere-ai-test` | `LlmJudge` (`meetsIntent`, `isGroundedIn`, `hasQuality`); `AbstractAgentRuntimeContractTest` pins the capability matrix per runtime |
| Skill files | `atmosphere-agent` | Markdown system prompts with tool / guardrail / channel sections; classpath-discovered |

The AI-runtime capability matrix — which runtimes ship tool calling, structured output, multi-modal input, prompt caching, embeddings, retry — lives in [`modules/ai/README.md`](modules/ai/README.md#capability-matrix) and is enforced by `AbstractAgentRuntimeContractTest.expectedCapabilities()`, so the matrix and the runtime code cannot drift.

## Governance

Drop `atmosphere-policies.yaml` on the classpath and every `@Prompt` and `@AiTool` dispatch flows through it. No code changes, no redeploy on policy edits.

```yaml
# atmosphere-policies.yaml
version: "1.0"
policies:
  - name: deny-destructive-sql
    type: deny-list
    config:
      phrases: ["DROP TABLE", "TRUNCATE", "DELETE FROM"]
  - name: tenant-cost-ceiling
    type: cost-ceiling
    config:
      ceilingDollars: 50.0
```

Or annotate an endpoint with `@AgentScope` so a customer-support bot stops answering Python questions:

```java
@AiEndpoint("/support")
@AgentScope(
    purpose = "Customer support — orders, billing, store hours",
    forbiddenTopics = {"code", "programming", "medical advice"},
    onBreach = AgentScope.Breach.POLITE_REDIRECT)
public class SupportAgent { /* @Prompt method */ }
```

YAML auto-detects either Atmosphere-native shape or **Microsoft Agent Governance Toolkit** schema; an MS rule like `{field: tool_name, operator: eq, value: drop_database, action: deny}` fires before the tool's executor runs. Decisions are surfaced at `GET /api/admin/governance/decisions`, mapped to OWASP Agentic Top 10 + EU AI Act / HIPAA / SOC2 evidence at `GET /api/admin/governance/agt-verify` (the same shape MS's `agt verify` CLI consumes), and a Vue admin tab renders the live state.

Optional sinks ship in separate modules: `atmosphere-ai-audit-kafka`, `atmosphere-ai-audit-postgres`, `atmosphere-ai-policy-rego` (OPA), `atmosphere-ai-policy-cedar` (AWS).

[Governance policy plane reference](docs/governance-policy-plane.md) · [tutorial site](https://atmosphere.github.io/docs/reference/governance/) · [`ms-governance-chat` sample](samples/spring-boot-ms-governance-chat/)

## Client — atmosphere.js

```bash
npm install atmosphere.js
```

```tsx
import { useStreaming } from 'atmosphere.js/react';

function Chat() {
  const { fullText, isStreaming, send } = useStreaming({
    request: {
      url: '/atmosphere/agent/my-agent',
      transport: 'webtransport',         // HTTP/3 over QUIC
      fallbackTransport: 'websocket',    // auto-fallback
    },
  });
  return <p>{fullText}</p>;
}
```

React, [Vue](atmosphere.js/README.md#vue), [Svelte](atmosphere.js/README.md#svelte), and [React Native](atmosphere.js/README.md#react-native) bindings available. For Java/Kotlin clients, see [wAsync](modules/wasync/) — async WebSocket, SSE, long-polling, and gRPC client, shipped in-tree.

## Samples

| Sample | Description |
|--------|-------------|
| [startup team](samples/spring-boot-multi-agent-startup-team/) | `@Coordinator` with 4 A2A specialist agents |
| [dentist agent](samples/spring-boot-dentist-agent/) | Commands, tools, skill file, Slack + Telegram |
| [ai-tools](samples/spring-boot-ai-tools/) | Framework-agnostic tool calling + approval gates |
| [orchestration-demo](samples/spring-boot-orchestration-demo/) | Agent handoffs and approval gates |
| [chat](samples/spring-boot-chat/) | Room protocol, presence, WebTransport/HTTP3 |
| [ai-chat](samples/spring-boot-ai-chat/) | AI chat with auth, WebTransport, caching |
| [mcp-server](samples/spring-boot-mcp-server/) | MCP tools, resources, prompts |
| [rag-chat](samples/spring-boot-rag-chat/) | RAG with knowledge base search tools |
| [a2a-agent](samples/spring-boot-a2a-agent/) | A2A assistant with weather/time tools |
| [agui-chat](samples/spring-boot-agui-chat/) | AG-UI framework integration |
| [durable-sessions](samples/spring-boot-durable-sessions/) | SQLite/Redis session persistence |
| [checkpoint-agent](samples/spring-boot-checkpoint-agent/) | Durable HITL workflow — @Coordinator + CheckpointStore + REST approval |
| [ai-classroom](samples/spring-boot-ai-classroom/) | Multi-room collaborative AI |
| [ms-governance-chat](samples/spring-boot-ms-governance-chat/) | Chat gated by **Microsoft Agent Governance Toolkit** YAML (MS schema, verbatim) |
| [channels-chat](samples/spring-boot-channels-chat/) | Slack, Telegram, WhatsApp, Messenger |
| [personal-assistant](samples/spring-boot-personal-assistant/) | `@Coordinator` + `AgentFleet` over `InMemoryProtocolBridge`, `@AiTool` → crew dispatch, OpenClaw workspace |
| [coding-agent](samples/spring-boot-coding-agent/) | Docker `Sandbox` provider — clone, read, stream real file bytes to the client |
| [guarded-email-agent](samples/spring-boot-guarded-email-agent/) | Plan-and-verify demo — LLM-emitted workflow refused by `TaintVerifier` before any tool fires (Meijer "Guardians of the Agents" pattern) |

[All samples](samples/) &middot; `atmosphere install` for interactive picker &middot; `atmosphere compose` to scaffold multi-agent projects &middot; [CLI reference](cli/README.md)

## Getting Started

```xml
<!-- Spring Boot 4.0 starter -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-boot-starter</artifactId>
    <version>${atmosphere.version}</version>
</dependency>

<!-- Agent module (required for @Agent, @Coordinator) -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-agent</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
```

Optional: `atmosphere-ai`, `atmosphere-spring-ai`, `atmosphere-langchain4j`, `atmosphere-adk`, `atmosphere-koog`, `atmosphere-embabel`, `atmosphere-semantic-kernel`, `atmosphere-mcp`, `atmosphere-a2a`, `atmosphere-agui`, `atmosphere-channels`, `atmosphere-coordinator`, `atmosphere-admin`. Add to classpath and features auto-register.

**Requirements:** Java 21+ &middot; Spring Boot 4.0.5+ or Quarkus 3.31.3+ &middot; Current release: see the Maven Central badge above

## Documentation

[Tutorial](https://atmosphere.github.io/docs/tutorial/01-introduction/) &middot; [Full docs](https://atmosphere.github.io/docs/) &middot; [CLI](cli/README.md) &middot; [Javadoc](https://atmosphere.github.io/apidocs/) &middot; [Samples](samples/)

## Support

Commercial support and consulting available through [Async-IO.org](https://async-io.org).

## Companion Projects

| Project | Description |
|---------|-------------|
| [atmosphere-skills](https://github.com/Atmosphere/atmosphere-skills) | Curated agent skill files — personality, tools, guardrails |
| [homebrew-tap](https://github.com/Atmosphere/homebrew-tap) | Homebrew formulae for the Atmosphere CLI |
| [javaclaw-atmosphere](https://github.com/Atmosphere/javaclaw-atmosphere) | Atmosphere chat transport plugin for JavaClaw |

## License

Apache 2.0 — @Copyright 2008-2026 [Async-IO.org](https://async-io.org)
