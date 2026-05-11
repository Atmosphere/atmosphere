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

A `@Agent` class runs on one of nine `AgentRuntime` adapters — a built-in OpenAI-compatible client plus framework integrations for Spring AI, LangChain4j, Google ADK, JetBrains Koog, Microsoft Semantic Kernel, and Alibaba AgentScope on Spring Boot 4, with Embabel and Spring AI Alibaba pinned to Spring Boot 3.5 (see the [adapter table](#ai-runtimes)). The same `@Agent` is served over five transports (WebTransport over HTTP/3, WebSocket, SSE, long-polling, gRPC), three agent protocols (MCP, A2A v1.0.0, AG-UI), and five external messaging channels (Slack, Telegram, Discord, WhatsApp, Messenger) in addition to the default browser endpoint. Runtime, transport, and channel are swapped by changing a Maven dependency.

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

### Real-time core

| Capability | Module | Key types |
|---|---|---|
| Streaming transports | `atmosphere-runtime` | WebTransport/HTTP3, WebSocket, SSE, long-polling, gRPC — negotiated via `AsyncSupport`; Jetty 12 QUIC native or Reactor Netty HTTP/3 sidecar |
| Authentication | `atmosphere-runtime` | `TokenValidator`, `TokenRefresher`, `AuthInterceptor` — rejects at WebSocket / HTTP upgrade |
| Observability | `atmosphere-runtime`, `atmosphere-ai` | OpenTelemetry spans, Micrometer metrics, token usage; `BusinessMdcBenchmark` pins the MDC hot-path cost |
| Business tags | `atmosphere-ai` | `BusinessMetadata` → SLF4J MDC (tenant, customer, session, event kind) |

### Agents

| Capability | Module | Key types |
|---|---|---|
| Agent declaration | `atmosphere-agent` | `@Agent`, `@Prompt`, `@Command`, `@AiTool`, `@RequiresApproval` |
| Skill files | `atmosphere-agent` | Markdown system prompts with tool / guardrail / channel sections; classpath-discovered |
| Memory | `atmosphere-ai` | sliding window, LLM summarization; durable via `atmosphere-durable-sessions` (SQLite / Redis) |
| Checkpoints | `atmosphere-checkpoint` | `CheckpointStore` — parent-chained snapshots, fork, resume by REST |
| Reconnect & replay | `atmosphere-durable-sessions` + `RunRegistry` | clients reconnect with `X-Atmosphere-Run-Id`; `AiEndpointHandler` replays the mid-stream buffer to the new resource |
| Grounded facts | `atmosphere-ai` | `FactResolver` SPI, per-turn; values escaped before prompt injection |
| Permission modes | `atmosphere-ai` | `PermissionMode.DEFAULT` / `PLAN` / `ACCEPT_EDITS` / `BYPASS` / `DENY_ALL` — runtime config, not redeploy |

### AI runtimes

`atmosphere-ai` ships the `AgentRuntime` SPI plus the **Built-in** OpenAI-compatible adapter (works against OpenAI / Anthropic / Ollama / any OpenAI-compatible endpoint). Eight framework adapters live in their own modules — drop one on the classpath and `@Agent` dispatches through it. Each adapter's capability flags are pinned by a contract test in `AbstractAgentRuntimeContractTest.expectedCapabilities()`, so the rows below cannot drift from the running code.

| Module | Backing framework | Spring Boot | Capability highlights | Notes |
|---|---|---|---|---|
| `atmosphere-ai` (Built-in) | OpenAI-compatible HTTP client | 3.5 / 4.0 | tool calling (5 rounds), JSON mode, vision, audio, prompt caching, token usage, native retry, tool-call deltas | Default. No third-party SDK on the classpath required. |
| `atmosphere-spring-ai` | Spring AI 2.0.0-M6 | 4.0 | tool calling, structured output, vision, audio, prompt caching, token usage | |
| `atmosphere-langchain4j` | LangChain4j 1.14.0 | 4.0 | tool calling, structured output, vision, audio, prompt caching, token usage | |
| `atmosphere-adk` | Google ADK 1.2.0 | 4.0 | agent orchestration, tool calling, multi-modal, prompt caching | Multi-agent runtime — exposes `AGENT_ORCHESTRATION`. |
| `atmosphere-koog` | JetBrains Koog 0.8.0 | 4.0 | agent orchestration, tool calling, multi-modal, prompt caching (Bedrock cache control), cancellation | Multi-agent runtime. |
| `atmosphere-semantic-kernel` | Microsoft Semantic Kernel 1.5.0 | 4.0 | tool calling, structured output, token usage | No vision / audio path through the SK Java SDK today. |
| `atmosphere-agentscope` | Alibaba AgentScope 1.0.12 | 4.0 | structured output, conversation memory, token usage, cancellation | No native tool-call dispatch in the SDK; tools must be invoked manually. |
| `atmosphere-embabel` | Embabel 0.3.5 | **3.5 only** | agent orchestration, tool calling, vision, conversation memory | Embabel does not yet support Spring Boot 4. Use `atmosphere-spring-boot3-starter` and the `-Pspring-boot3` profile. |
| `atmosphere-spring-ai-alibaba` | Spring AI Alibaba 1.1.2.2 (transitively pins Spring AI 1.1.2) | **3.5 only** | structured output, conversation memory | LLM round-trip is buffered by `ReactAgent.call()` — no token deltas; Atmosphere still streams the final reply chunk over WebSocket / SSE. For token-by-token streaming, use `atmosphere-spring-ai`. SB4 path blocked on Alibaba publishing a Spring AI 2.x agent-framework. |

The full capability matrix (text streaming, tool calling, structured output, system prompt, agent orchestration, conversation memory, tool approval, vision, audio, multi-modal, prompt caching, token usage, per-request retry, tool-call deltas) lives in [`modules/ai/README.md`](modules/ai/README.md#capability-matrix); contract tests fail the build if any runtime drifts from its declared row.

### Multi-agent, protocols, channels

| Capability | Module | Key types |
|---|---|---|
| Multi-agent coordination | `atmosphere-coordinator` | `@Coordinator`, `@Fleet`, `@AgentRef`; `LocalAgentTransport` (in-JVM) and `A2aAgentTransport` (HTTP JSON-RPC); parallel / sequential / conditional routing; coordination journal |
| Agent protocols | `atmosphere-mcp`, `atmosphere-a2a`, `atmosphere-agui` | auto-registered endpoint per `@Agent` per protocol; A2A v1.0.0 with pre-1.0 method aliases |
| Messaging channels | `atmosphere-channels` | Five `MessagingChannel` implementations: Slack, Telegram, Discord, WhatsApp, Messenger — one `@Command` dispatched to all, plus the default browser endpoint via WebSocket / SSE |
| Evaluation | `atmosphere-ai-test` | `LlmJudge` (`meetsIntent`, `isGroundedIn`, `hasQuality`); `AbstractAgentRuntimeContractTest` for runtime contract pinning |

### Governance & compliance

| Capability | Module | Key types |
|---|---|---|
| Guardrails | `atmosphere-ai` | `PiiRedactionGuardrail`, `OutputLengthZScoreGuardrail` (tenant-partitioned), `CostCeilingGuardrail` |
| Governance policy plane | `atmosphere-ai` | `GovernancePolicy` SPI; YAML `PolicyParser` auto-detects Atmosphere-native + [Microsoft Agent Governance Toolkit](https://github.com/microsoft/agent-governance-toolkit) schema; MS-compatible `POST /api/admin/governance/check` |
| Policy admission primitives | `atmosphere-ai` | `AllowListPolicy`, `DenyListPolicy`, `MessageLengthPolicy`, `RateLimitPolicy`, `ConcurrencyLimitPolicy`, `TimeWindowPolicy`, `MetadataPresencePolicy`, `AuthorizationPolicy`, `ConfidenceThresholdGuardrail` — composable via `PolicyRing` |
| Ops primitives | `atmosphere-ai` | `KillSwitchPolicy` break-glass; `DryRunPolicy` shadow rollouts; `SwappablePolicy` hot-reload; `SloTracker` burn-rate; `PolicyHashDigest` drift detection |
| Multi-agent governance | `atmosphere-coordinator` | `FleetInterceptor` SPI for per-dispatch gating; `GovernanceFleetInterceptor` bridges `FleetInterceptor` → `GovernancePolicy` at the coord→specialist edge |
| Commitment records | `atmosphere-coordinator` | W3C Verifiable-Credential-subtype records signed Ed25519; `CommitmentRecordsFlag` flag-off default; admin Commitments tab |
| Plan-and-verify | `atmosphere-verifier` | Static verification of LLM-emitted tool-call workflows ([Meijer, *Guardians of the Agents*, CACM Jan 2026](https://cacm.acm.org/research/guardians-of-the-agents/)); sealed `Workflow` AST; six `PlanVerifier` SPIs (Allowlist / WellFormedness / Capability / Taint / Automaton / Smt); `@Sink` + `@RequiresCapability` co-located policy; `verify` CLI |
| Stream-level PII rewrite | `atmosphere-ai` | `PiiRedactionFilter` — `BroadcasterFilter` auto-installed; rewrites tokens before bytes flush to the client |
| Cost enforcement | `atmosphere-ai` | `CostCeilingAccountant` bridges `TokenUsage` → `CostCeilingGuardrail.addCost` keyed by `business.tenant.id`; per-tenant budgets block dispatch |
| Compliance evidence | `atmosphere-ai` | OWASP Agentic Top 10 + EU AI Act / HIPAA / SOC 2 matrices; CI-enforced via `OwaspMatrixPinTest`, `ComplianceMatrixPinTest`, `EvidenceConsumerGrepPinTest` |

### Admin & sandbox

| Capability | Module | Key types |
|---|---|---|
| Admin control plane | `atmosphere-admin` | `/atmosphere/admin/` UI, `/api/admin/*` REST, MCP tools; triple-gate (feature flag → Principal → `ControlAuthorizer`) |
| Flow viewer | `atmosphere-admin` | `GET /api/admin/flow` — JSON graph keyed by `coordinationId` (nodes, edges, success / failure / avg duration) |
| Admin governance surface | `atmosphere-admin` + starter | `GET /governance/{policies,health,decisions,owasp,compliance,agt-verify}`; `POST /governance/{check,reload,kill-switch/arm,kill-switch/disarm}` |
| Sandbox | `atmosphere-sandbox` | `Sandbox` / `SandboxProvider` SPI; two providers ship in-tree — `DockerSandboxProvider` (default; `--network none`, argv-form exec, strict mount validation) and `InProcessSandboxProvider` (tests). The `ServiceLoader` is open for third-party Firecracker / Kata / Vercel Sandbox / E2B / Modal / Blaxel providers; none ship in-tree. |

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

YAML auto-detects either Atmosphere-native shape or [Microsoft Agent Governance Toolkit](https://github.com/microsoft/agent-governance-toolkit) schema; an MS rule like `{field: tool_name, operator: eq, value: drop_database, action: deny}` fires before the tool's executor runs. Decisions are surfaced at `GET /api/admin/governance/decisions`, mapped to OWASP Agentic Top 10 + EU AI Act / HIPAA / SOC2 evidence at `GET /api/admin/governance/agt-verify` (the same shape MS's `agt verify` CLI consumes), and a Vue admin tab renders the live state.

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
| [ms-governance-chat](samples/spring-boot-ms-governance-chat/) | Chat gated by [Microsoft Agent Governance Toolkit](https://github.com/microsoft/agent-governance-toolkit) YAML (MS schema, verbatim) |
| [channels-chat](samples/spring-boot-channels-chat/) | Slack, Telegram, Discord, WhatsApp, Messenger |
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

Add only what you need — every module below is opt-in and auto-registers when on the classpath:

- **AI runtime** — `atmosphere-ai` (Built-in), or one of the framework adapters listed in the [adapter table](#ai-runtimes)
- **Protocols** — `atmosphere-mcp`, `atmosphere-a2a`, `atmosphere-agui`
- **External channels** — `atmosphere-channels` (Slack, Telegram, Discord, WhatsApp, Messenger)
- **Multi-agent** — `atmosphere-coordinator`
- **Admin / control plane** — `atmosphere-admin`
- **Plan-and-verify** — `atmosphere-verifier`
- **Sandbox** — `atmosphere-sandbox` (Docker by default)
- **Durable sessions / replay** — `atmosphere-durable-sessions` plus `atmosphere-durable-sessions-sqlite` or `atmosphere-durable-sessions-redis`
- **Checkpoints** — `atmosphere-checkpoint`
- **Audit sinks** — `atmosphere-ai-audit-kafka`, `atmosphere-ai-audit-postgres`
- **Policy engines** — `atmosphere-ai-policy-rego` (OPA), `atmosphere-ai-policy-cedar` (AWS Cedar)

For Spring Boot 3.5 deployments (required if you use Embabel or Spring AI Alibaba), substitute `atmosphere-spring-boot3-starter` and build with the `-Pspring-boot3` profile.

**Requirements:** Java 21+ &middot; Spring Boot 4.0.5 (or 3.5 via the `-Pspring-boot3` profile) or Quarkus 3.35.2+ &middot; Current release: see the Maven Central badge above

## Documentation

[Tutorial](https://atmosphere.github.io/docs/tutorial/01-introduction/) &middot; [Full docs](https://atmosphere.github.io/docs/) &middot; [CLI](cli/README.md) &middot; [Javadoc](https://atmosphere.github.io/apidocs/) &middot; [Samples](samples/)

## Support

[Async-IO](https://async-io.live) — the team that wrote and maintains
Atmosphere — provides commercial support, consulting, and engineering
services for production deployments.

- **Production support tiers** with response-time SLAs, emergency
  patches, and 24×7 coverage available — [tiers & SLA matrix](https://async-io.live/#support).
- **Compliance evidence package** — control matrices ship in-tree, mapping
  Atmosphere primitives to specific control IDs in four frameworks, with CI
  gates that fail the build on drift:
  - **OWASP Agentic AI Top 10** — `OwaspAgenticMatrix` covers all 10 IDs (T1–T10)
    with a `Coverage` enum (`COVERED` / `PARTIAL` / `DESIGN` / `NOT_ADDRESSED`)
    and a list of `Evidence` pointers (feature class, contract test, consumer-grep
    pattern). Pinned by `OwaspMatrixPinTest`.
  - **EU AI Act, HIPAA, SOC 2** — `ComplianceMatrix` covers 5 EU AI Act controls
    (EU-AIA-9 / 12 / 13 / 14 / 15), 5 HIPAA Security Rule safeguards
    (164.308 / .312), and 5 SOC 2 Trust Services Criteria
    (CC6.1 / .6, CC7.2 / .3, CC8.1). Pinned by `ComplianceMatrixPinTest`.
  - **Evidence drift gate** — `EvidenceConsumerGrepPinTest` re-runs every grep
    pattern against the source tree. If a referenced primitive is renamed,
    removed, or no longer reachable from a production caller, the test fails —
    the matrices cannot drift from what's actually running.
  - **Auditor-ready surface** — the same matrices are exposed at
    `GET /api/admin/governance/owasp` and `/governance/compliance` with
    rows, coverage, and evidence pointers in JSON, so an auditor can pull
    evidence without reading our source tree.
- **[Microsoft Agent Governance Toolkit](https://github.com/microsoft/agent-governance-toolkit) interop** — drop-in for shops
  standardising on MS Agent OS:
  - **YAML schema auto-detect** — `YamlPolicyParser` recognises the MS schema
    (`rules:` sequence at the document root, each rule carrying `name`,
    `condition: {field, operator, value}`, and `action`) and parses it verbatim
    alongside the Atmosphere-native `policies:` shape. The two roots are
    mutually exclusive, so a single file is unambiguous.
  - **Operators supported** — `eq`, `contains`, `matches` (regex), with
    reject-with-context on unknown operators (no silent fallthrough).
  - **`audit_entry` JSON shape** — the `AuditEntry` record (`timestamp`,
    `policyName`, `policySource`, `policyVersion`,
    `decision ∈ admit / transform / deny`, `reason`, `contextSnapshot`)
    mirrors MS's emitted shape, so a SIEM consumer can ingest both Atmosphere
    and MS Agent OS audit streams without transforms.
  - **`agt verify` CLI parity** — `GET /api/admin/governance/agt-verify`
    returns OWASP coverage in the same `schemaVersion: agt-verify/1` envelope
    MS's CLI consumes.
- **Plan-and-verify** (`atmosphere-verifier`) — refuses unsafe LLM-emitted
  workflows before any tool fires, implementing the architecture from Erik
  Meijer's *"[Guardians of the Agents: Formal Verification of AI Workflows](https://cacm.acm.org/research/guardians-of-the-agents/)"*
  (CACM, January 2026):
  - **Plan upfront, verify, then execute** — instead of letting the LLM call
    tools one at a time, the LLM emits a structured `Workflow` JSON with
    symbolic references (placeholders, not real data). A static verifier runs
    over the plan; only verified plans execute.
  - **Six built-in `PlanVerifier` SPIs** — `Allowlist` (whitelist of allowed
    tools), `WellFormedness` (AST shape + symbolic-reference resolution),
    `Capability` (each call holds the required `@RequiresCapability`), `Taint`
    (no `@Sink` reads tainted input), `Automaton` (security automaton
    state-machine), `Smt` (SMT-backed pre / post-condition checks). All
    discovered via `ServiceLoader`.
  - **Co-located policy** — `@Sink` and `@RequiresCapability` annotations live
    on the tool method itself, scanned by `SinkScanner` / `CapabilityScanner`
    at startup. The policy file lists taint sources and capabilities;
    verifiers cross-reference annotation + policy + plan.
  - **`verify` CLI** — `verify --policy email.policy.json --workflow attack.plan.json`
    for offline auditor / red-team review. Exits 0 (clean), 1 (violation),
    2 (usage / IO error).
- **A2A v1.0.0** spec alignment — Atmosphere ships the released spec,
  not a pre-1.0 draft; pre-1.0 method names aliased so existing
  clients keep working.
- **Legacy Atmosphere 2.x / 3.x** long-term support and migration
  engagements — <support@async-io.org>.

Book a 30-min architecture call: [async-io.live/contact](https://async-io.live/contact/).

## Companion Projects

| Project | Description |
|---------|-------------|
| [atmosphere-skills](https://github.com/Atmosphere/atmosphere-skills) | Curated agent skill files — personality, tools, guardrails |
| [homebrew-tap](https://github.com/Atmosphere/homebrew-tap) | Homebrew formulae for the Atmosphere CLI |

## License

Apache 2.0 — @Copyright 2008-2026 [Async-IO.org](https://async-io.live)
