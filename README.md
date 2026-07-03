<p align="center">
  <img src="logo.png" alt="Atmosphere" width="120"/>
</p>

<h1 align="center">Atmosphere</h1>

<p align="center">
  The real-time engine for streaming, governable AI agents. Atmosphere's broadcaster transport — WebSocket, SSE, long-polling, gRPC — is the foundation; declare behavior with <code>@Agent</code> and Atmosphere owns runtime dispatch, reconnect, authorization, observability, and the governance path.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/org.atmosphere/atmosphere-runtime"><img src="https://img.shields.io/maven-central/v/org.atmosphere/atmosphere-runtime?label=Maven%20Central&color=blue" alt="Maven Central"/></a>
  <a href="https://www.npmjs.com/package/atmosphere.js"><img src="https://img.shields.io/npm/v/atmosphere.js?label=atmosphere.js&color=blue" alt="npm"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/ci.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI: Core"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/e2e.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/e2e.yml/badge.svg?branch=main" alt="CI: E2E"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/ci-js.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/ci-js.yml/badge.svg?branch=main" alt="CI: atmosphere.js"/></a>
</p>

---

Atmosphere is built for teams that need AI agents to behave like production services: streaming over real transports, guarded before every tool call, observable by tenant and run, and portable across AI frameworks without rewriting the endpoint.

## Why Atmosphere

| Need | What Atmosphere provides |
|---|---|
| Stream to real clients | WebSocket, SSE, long-polling, and gRPC run through one broadcaster pipeline as always-on defaults; WebTransport over HTTP/3 is optional (needs `jetty-http3-server` or `reactor-netty-http` on the classpath plus a dev cert) |
| Swap AI integrations | One `AgentRuntime` SPI with twelve runtime adapters and contract-tested capability flags |
| Govern execution | Policy admission, `@AgentScope`, human approval, plan-and-verify, cost ceilings, PII rewriting, and admin kill switches |
| Pause for humans | Durable HITL approvals hibernate without holding a thread, persist workflow state, and resume through REST approval surfaces |
| Resume long runs | Durable sessions, run IDs, replay buffers, checkpoints, and reconnect-safe continuation |
| Expose the same agent everywhere | Browser endpoints plus MCP (stateless RC on the MCP 2026-07-28 spec, sessions back to 2024-11-05), A2A, AG-UI, Slack, Telegram, Discord, WhatsApp, and Messenger modules |

## Scope

Atmosphere is a real-time, event-driven framework, not an agent-hosting platform. We ship the primitives your application uses at runtime; the host you choose (Tomcat, Jetty, Netty, Undertow, Quarkus, Spring Boot, or any servlet container) owns compute and scheduling. Payment rails and commerce primitives are out of scope. Compared to the agent-platform stack vocabulary that has emerged around offerings like Cloudflare Agents, AWS Bedrock Agents, and Vertex AI Agents:

| Layer | What Atmosphere ships | Provided by your stack |
|---|---|---|
| [Streaming transport](https://atmosphere.github.io/docs/reference/core/) | `atmosphere-runtime` over WebSocket, SSE, long-polling (always-on defaults), gRPC, plus optional WebTransport/HTTP-3 (needs `jetty-http3-server` or `reactor-netty-http` on the classpath plus a dev cert) | — |
| [Runtime dispatch](https://atmosphere.github.io/docs/tutorial/11-ai-adapters/) | `atmosphere-ai` `AgentRuntime` SPI + 12 adapters with contract-tested capability flags | Model hosting (we call providers; we do not host weights) |
| [Orchestration](https://atmosphere.github.io/docs/agents/coordinator/) | `@Coordinator`, `AgentFleet`, handoffs, conditional routing, an event-sourced coordination journal with causal lineage (append-only NDJSON persistence, DAG projection, what-if forking), result evaluation, and durable hibernating `Workflow<S>` over `CheckpointStore` (per-step retry, resume across JVM restart, no thread held while hibernated) — durable step execution, not wall-clock triggering | Cron / wall-clock scheduling (your container scheduler or a dedicated scheduler fires the workflow) |
| [Memory](https://atmosphere.github.io/docs/reference/durable-sessions/) | `AiConversationMemory` per-conversation history (in-memory, plus durable SQLite/Redis through the `ConversationPersistence` SPI in `atmosphere-durable-sessions{-sqlite,-redis}`), `LongTermMemory` per-user facts (`InMemoryLongTermMemory`, `SqliteLongTermMemory`, `RedisLongTermMemory`), `SemanticRecallInterceptor` for BYO vector-store recall | Managed vector stores (use Spring AI's `VectorStore`, LangChain4j embeddings, or your own) |
| [Governance](https://atmosphere.github.io/docs/reference/governance/) | Policy admission, `@AgentScope`, plan-and-verify, PII redaction, cost ceilings, durable HITL approvals, admin kill switches | — |
| [Protocol surface](https://atmosphere.github.io/docs/reference/mcp/) | MCP, A2A, AG-UI, Slack/Telegram/Discord/WhatsApp/Messenger channel adapters | — |
| [Code execution](https://atmosphere.github.io/docs/reference/sandbox/) | `atmosphere-sandbox` `SandboxProvider` SPI + `DockerSandboxProvider` default | Browser automation, headless Chromium |
| [SDK](https://atmosphere.github.io/docs/clients/javascript/) | `atmosphere.js` (React, Vue, Svelte, React Native, vanilla TS), `wasync` (JVM client) | — |

The differentiator is the streaming + JVM + governance combination: long-lived stateful agent sessions over real-time transports, with policy admission on the critical path. For stateless, bursty, autonomous agents that should hibernate when idle, a serverless agent platform is usually the better fit. For human-in-the-loop, multi-channel, governed agents inside an existing JVM stack, Atmosphere is the right fit.

## Quick Start

### Run a sample

```bash
brew install Atmosphere/tap/atmosphere

# Or:
curl -fsSL https://raw.githubusercontent.com/Atmosphere/atmosphere/main/cli/install.sh | sh

atmosphere run spring-boot-multi-agent-startup-team
```

### Create an app

```bash
atmosphere new my-agent --template ai-chat
cd my-agent
LLM_API_KEY=your-key ./mvnw spring-boot:run
```

### Swap the runtime adapter

```bash
# Built-in is the default. This injects the Spring AI adapter dependencies.
atmosphere new my-agent --template ai-chat --runtime spring-ai

# Use --force when a sample already pins a runtime adapter.
atmosphere new my-agent --template ai-tools --runtime langchain4j --force
```

### Import a skill

```bash
atmosphere import https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md
cd frontend-design
LLM_API_KEY=your-key ./mvnw spring-boot:run
```

## Terminology

| Term | Meaning in Atmosphere | Examples |
|---|---|---|
| Model provider | The model/API vendor or endpoint that serves tokens | OpenAI, Gemini compatibility endpoint, Ollama, DashScope, local OpenAI-compatible proxies |
| Runtime adapter | The Atmosphere integration that implements `AgentRuntime` | Built-in, Spring AI, LangChain4j, Google ADK, Embabel, Koog, Semantic Kernel, AgentScope, Spring AI Alibaba, Anthropic, Cohere, CrewAI |
| Capability | A feature advertised by a runtime adapter and pinned by contract tests | tool calling, embeddings, streaming, structured output, prompt caching |

Use **provider** for model vendors and **runtime adapter** for Atmosphere integrations. Not every runtime adapter exposes every capability.

## `@Agent`

One annotation declares the agent. Modules on the classpath decide which endpoints and integrations are registered.

```java
@Agent(name = "my-agent", description = "What this agent does")
public class MyAgent {

    @Prompt
    public void onMessage(String message, StreamingSession session) {
        session.stream(message);
    }

    @Command(value = "/status", description = "Show status")
    public String status() {
        return "All systems operational";
    }

    @Command(value = "/reset", description = "Reset data",
             confirm = "This will delete all data. Are you sure?")
    public String reset() {
        return dataService.resetAll();
    }

    @AiTool(name = "lookup", description = "Look up data")
    public String lookup(@Param("query") String query) {
        return dataService.find(query);
    }
}
```

| Module on classpath | What gets registered |
|---|---|
| [`atmosphere-agent`](https://atmosphere.github.io/docs/agents/agent/) | Browser endpoint at `/atmosphere/agent/my-agent`, streaming AI dispatch, memory, commands, `/help` |
| [`atmosphere-mcp`](https://atmosphere.github.io/docs/reference/mcp/) | MCP endpoint at `/atmosphere/agent/my-agent/mcp` — session protocol plus the stateless RC on the MCP 2026-07-28 spec (Tasks, MCP Apps, OAuth resource server) |
| [`atmosphere-a2a`](https://atmosphere.github.io/docs/agents/a2a/) | A2A endpoint at `/atmosphere/agent/my-agent/a2a` with Agent Card discovery |
| [`atmosphere-agui`](https://atmosphere.github.io/docs/agents/agui/) | AG-UI endpoint at `/atmosphere/agent/my-agent/agui` |
| [`atmosphere-channels`](https://atmosphere.github.io/docs/tutorial/23-channels/) | Slack, Telegram, Discord, WhatsApp, and Messenger dispatch |
| [`atmosphere-admin`](https://atmosphere.github.io/docs/reference/admin/) | Admin dashboard and `/api/admin/*` control surfaces |
| built-in console | Console UI at `/atmosphere/console/` |

## AI Runtime Adapters

`atmosphere-ai` ships the `AgentRuntime` SPI plus the Built-in OpenAI-compatible adapter. Eleven additional adapters live in separate modules — nine wrap a third-party framework, and two (`atmosphere-anthropic`, `atmosphere-cohere`) are native HTTP+SSE clients for the Anthropic Messages API and the Cohere v2 chat API respectively. Drop one runtime adapter on the classpath and the same `@Agent` code dispatches through it.

Capabilities are intentionally not identical. The authoritative matrix is pinned by `AbstractAgentRuntimeContractTest.expectedCapabilities()`, so a runtime cannot drift from its declared feature set without breaking tests.

| Runtime adapter | Backing framework | Spring Boot | Capability highlights | Notes |
|---|---|---|---|---|
| [`atmosphere-ai`](https://atmosphere.github.io/docs/reference/ai/) (Built-in) | OpenAI-compatible HTTP client | 3.5 / 4.0 | tool calling, JSON mode, vision, audio, prompt caching, token usage, native retry, tool-call deltas | Default. Works with OpenAI-compatible endpoints such as OpenAI, Gemini compatibility endpoints, Ollama, and local proxies. |
| [`atmosphere-spring-ai`](https://atmosphere.github.io/docs/integrations/spring-ai/) | Spring AI 2.0.0 | 4.0 | tool calling, structured output, vision, audio, prompt caching, token usage | Best fit for Spring Boot applications already using Spring AI. |
| [`atmosphere-langchain4j`](https://atmosphere.github.io/docs/integrations/langchain4j/) | LangChain4j 1.17.0 | 4.0 | tool calling, structured output, vision, audio, prompt caching, token usage | Best fit for LangChain4j tool ecosystems and non-Spring services. |
| [`atmosphere-adk`](https://atmosphere.github.io/docs/integrations/adk/) | Google ADK 1.5.0 | 4.0 | agent orchestration, tool calling, multi-modal, prompt caching | Multi-agent runtime with `AGENT_ORCHESTRATION`. |
| [`atmosphere-koog`](https://atmosphere.github.io/docs/integrations/koog/) | JetBrains Koog 1.0.0 | 4.0 | agent orchestration, tool calling, multi-modal, prompt caching, cancellation | Multi-agent runtime. |
| [`atmosphere-semantic-kernel`](https://atmosphere.github.io/docs/integrations/semantic-kernel/) | Microsoft Semantic Kernel 1.5.0 | 4.0 | tool calling, structured output, vision, token usage | No audio path through the SK Java SDK today. |
| [`atmosphere-agentscope`](https://atmosphere.github.io/docs/integrations/agentscope/) | Alibaba AgentScope 1.0.12 | 4.0 | tool calling, structured output, vision, audio, conversation memory, token usage, cancellation | `AgentScopeToolBridge` routes every `@AiTool` invocation through `ToolExecutionHelper.executeWithApproval`. |
| [`atmosphere-embabel`](https://atmosphere.github.io/docs/integrations/embabel/) | Embabel 0.5.0 | 3.5 only | agent orchestration, tool calling, vision, conversation memory | Requires `atmosphere-spring-boot3-starter` and the `-Pspring-boot3` profile. |
| [`atmosphere-spring-ai-alibaba`](https://atmosphere.github.io/docs/integrations/spring-ai-alibaba/) | Spring AI Alibaba 1.1.2.3 | 3.5 only | tool calling, structured output, vision, audio, conversation memory, token usage | Buffered streaming (the upstream `ReactAgent.call()` returns one `AssistantMessage`); `UsageCapturingChatModel` decorator threads token usage. For token-by-token streaming, use another adapter until Alibaba ships a Spring AI 2.x-aligned agent framework. |
| [`atmosphere-anthropic`](https://atmosphere.github.io/docs/integrations/anthropic/) | Anthropic Messages API (no third-party SDK) | 3.5 / 4.0 | tool calling, structured output, conversation memory, token usage, per-request retry | Native HTTP+SSE client; tool loop capped at five rounds, cancellation-aware. Configure via `anthropic.api.key` (system property or `AiConfig.LlmSettings`); custom headers (`Helicone-Auth`, tenant IDs, tracing) are passthrough with reserved-header filtering. |
| [`atmosphere-cohere`](https://atmosphere.github.io/docs/integrations/cohere/) | Cohere v2 chat API (no third-party SDK) | 3.5 / 4.0 | tool calling, structured output, vision, multi-modal, conversation memory, token usage, per-request retry | Native HTTP+SSE client against `POST /v2/chat`; tool dispatch routes through `ToolExecutionHelper.executeWithApproval`. Configure via `cohere.api.key` (system property or `AiConfig.LlmSettings`). |
| [`atmosphere-crewai`](https://atmosphere.github.io/docs/integrations/crewai/) | CrewAI 1.14+ sidecar bridge | 3.5 / 4.0 | agent orchestration, tool calling, structured output, token usage, cancellation | Java HTTP+SSE bridge to a Python CrewAI sidecar. Availability is gated by a live `/health` probe, not classpath presence. |

See the full [capability matrix](modules/ai/README.md#capability-matrix) for text streaming, tool calling, structured output, system prompts, agent orchestration, conversation memory, tool approval, vision, audio, multi-modal, prompt caching, token usage, retry, passivation, and tool-call deltas.

## Enterprise Controls

Atmosphere keeps governance on the critical path rather than as an afterthought.

| Control | Module | What it does |
|---|---|---|
| [Policy admission](https://atmosphere.github.io/docs/reference/governance/) | `atmosphere-ai` | `GovernancePolicy`, `PolicyRing`, allow/deny lists, rate limits, time windows, metadata requirements |
| [Scope enforcement](https://atmosphere.github.io/docs/tutorial/31-agent-scope/) | `atmosphere-ai` | `@AgentScope` blocks out-of-purpose prompts before runtime dispatch |
| [Human approval](https://atmosphere.github.io/docs/reference/tool-approval-policy/) | `atmosphere-agent`, `atmosphere-ai` | command confirmations, permission modes, tool approval policies |
| [Durable HITL workflows](https://atmosphere.github.io/docs/tutorial/24-durable-hitl/) | `atmosphere-checkpoint`, `atmosphere-durable-sessions` | checkpointed approval gates, REST approve/reject/resume endpoints, and reconnect-safe replay for long-running agent work |
| [Stateful interactions](https://atmosphere.github.io/docs/reference/interactions/) | `atmosphere-interactions` | stateful agent turns with a durable `steps[]` log, `background` runs retrievable after disconnect, conversation chaining via `previous_interaction_id`, and live step streaming over WebSocket; REST surface at `/api/interactions` (mutations default-deny) |
| [Plan-and-verify](https://atmosphere.github.io/docs/tutorial/33-plan-and-verify/) | `atmosphere-verifier` | verifies LLM-emitted tool workflows before execution; six structural verifiers run today (control-flow gate, allowlist, well-formedness, capability, taint, automaton); a seventh SMT backend ships as `atmosphere-verifier-smt` (SMTInterpol pure-JVM default, Z3 opt-in via native libs) proving numeric invariants over symbolic tool-call data flow; the no-op default applies only when that module is absent. It implements Erik Meijer's [*Guardians of the Agents*](https://cacm.acm.org/practice/guardians-of-the-agents/) (CACM, Jan 2026) pattern in native Java |
| [PII and cost controls](https://atmosphere.github.io/docs/reference/governance/) | `atmosphere-ai` | stream-level PII redaction, token usage, per-tenant cost ceilings |
| [Admin control plane](https://atmosphere.github.io/docs/reference/admin/) | `atmosphere-admin` | dashboard, REST/MCP control surfaces, kill switches, journal flow viewer, governance decisions, **workflow authoring UI** (authoring + persistence; manifest execution runtime not yet wired), **eval dashboard** |
| Enterprise console bundle | `atmosphere-admin-bundle` | single Maven dep aggregating `spring-boot-starter` + `admin` + `ai` + `coordinator` + `agent` + `rag` + `checkpoint` + `durable-sessions` |
| [Compliance evidence](https://atmosphere.github.io/docs/tutorial/32-owasp-agentic-matrix/) | `atmosphere-ai`, `atmosphere-admin` | OWASP Agentic Top 10, EU AI Act, HIPAA, SOC 2 matrices and AGT-compatible verification output |
| [Sandbox execution](https://atmosphere.github.io/docs/reference/sandbox/) | `atmosphere-sandbox` | `DockerSandboxProvider` default and `SandboxProvider` SPI for isolated code execution |

Governance policy can be YAML-driven:

```yaml
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

Or annotation-driven:

```java
@AiEndpoint(path = "/support")
@AgentScope(
    purpose = "Customer support: orders, billing, store hours",
    forbiddenTopics = {"code", "programming", "medical advice"},
    onBreach = AgentScope.Breach.POLITE_REDIRECT)
public class SupportAgent { /* @Prompt method */ }
```

See the [governance policy plane reference](docs/governance-policy-plane.md), [governance docs](https://atmosphere.github.io/docs/reference/governance/), and [`spring-boot-ms-governance-chat`](samples/spring-boot-ms-governance-chat/) sample.

## Client Libraries

Install the TypeScript client:

```bash
npm install atmosphere.js
```

Use React, Vue, Svelte, React Native, or the low-level client directly:

```tsx
import { useStreaming } from 'atmosphere.js/react';

function Chat() {
  const { fullText, isStreaming, send } = useStreaming({
    request: {
      url: '/atmosphere/agent/my-agent',
      transport: 'webtransport',
      fallbackTransport: 'websocket',
    },
  });

  return <p>{fullText}</p>;
}
```

For Java/Kotlin clients, use [wAsync](modules/wasync/) for async WebSocket, SSE, long-polling, and gRPC clients.

## Flagship Samples

| Sample | Shows |
|---|---|
| [startup team](samples/spring-boot-multi-agent-startup-team/) | `@Coordinator` with A2A specialists, governance, checkpoints, skills, admin control plane |
| [ai-chat](samples/spring-boot-ai-chat/) | Streaming AI chat with auth, caching, and runtime adapter portability |
| [ai-tools](samples/spring-boot-ai-tools/) | Framework-agnostic `@AiTool` methods and approval gates |
| [checkpoint-agent](samples/spring-boot-checkpoint-agent/) | Checkpointed `@Coordinator` workflow with REST approval/resume |
| [ai-classroom](samples/spring-boot-ai-classroom/) | Multi-room collaborative AI with React Native / Expo client |
| [guarded-email-agent](samples/spring-boot-guarded-email-agent/) | Plan-and-verify taint protection before any email tool fires |
| [ms-governance-chat](samples/spring-boot-ms-governance-chat/) | Microsoft Agent Governance Toolkit-compatible YAML and decision endpoint |
| [mcp-server](samples/spring-boot-mcp-server/) | MCP tools, resources, prompts, and an MCP App (SEP-1865) with a bidirectional bridge, rendered in the console |
| [rag-chat](samples/spring-boot-rag-chat/) | Retrieval-augmented generation with `ContextProvider` |
| [channels-chat](samples/spring-boot-channels-chat/) | Slack, Telegram, Discord, WhatsApp, and Messenger channel dispatch |
| [coding-agent](samples/spring-boot-coding-agent/) | Docker sandbox provider for clone/read/stream coding workflows |

[All samples](samples/) · [`cli/samples.json`](cli/samples.json) · [`atmosphere install`](cli/README.md#sample-catalog--atmosphere-install) for the interactive picker.

## Add Atmosphere to an App

```xml
<!-- Spring Boot 4.0 starter -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-boot-starter</artifactId>
    <version>${atmosphere.version}</version>
</dependency>

<!-- Agent module: @Agent, @Prompt, @Command, @AiTool -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-agent</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
```

Add only what you need:

- **AI runtime**: `atmosphere-ai` or one runtime adapter from the [runtime table](#ai-runtime-adapters)
- **Protocols**: [`atmosphere-mcp`](https://atmosphere.github.io/docs/reference/mcp/), [`atmosphere-a2a`](https://atmosphere.github.io/docs/agents/a2a/), [`atmosphere-agui`](https://atmosphere.github.io/docs/agents/agui/)
- **Channels**: [`atmosphere-channels`](https://atmosphere.github.io/docs/tutorial/23-channels/)
- **Multi-agent**: [`atmosphere-coordinator`](https://atmosphere.github.io/docs/agents/coordinator/)
- **Admin/control plane**: [`atmosphere-admin`](https://atmosphere.github.io/docs/reference/admin/)
- **Plan-and-verify**: [`atmosphere-verifier`](https://atmosphere.github.io/docs/tutorial/33-plan-and-verify/)
- **Sandbox**: [`atmosphere-sandbox`](https://atmosphere.github.io/docs/reference/sandbox/)
- **Durable sessions**: [`atmosphere-durable-sessions`](https://atmosphere.github.io/docs/reference/durable-sessions/) plus `atmosphere-durable-sessions-sqlite` or `atmosphere-durable-sessions-redis`
- **Checkpoints**: [`atmosphere-checkpoint`](https://atmosphere.github.io/docs/reference/checkpoint/)
- **Audit sinks**: `atmosphere-ai-audit-kafka`, `atmosphere-ai-audit-postgres`
- **[Policy engines](https://atmosphere.github.io/docs/reference/governance/)**: `atmosphere-ai-policy-rego` (OPA), `atmosphere-ai-policy-cedar` (AWS Cedar)

For Spring Boot 3.5 deployments, including Embabel or Spring AI Alibaba, use `atmosphere-spring-boot3-starter` and build with `-Pspring-boot3`.

**Requirements:** Java 21+ · Spring Boot 4.0.7 or Spring Boot 3.5 via `-Pspring-boot3` · Quarkus 3.36.0+ · current release from the Maven Central badge above.

## Documentation

[Tutorial](https://atmosphere.github.io/docs/tutorial/01-introduction/) · [Full docs](https://atmosphere.github.io/docs/) · [CLI](cli/README.md) · [Javadoc](https://atmosphere.github.io/apidocs/) · [Samples](samples/)

| Topic | Reference docs |
|---|---|
| Architecture & transports | [Architecture](https://atmosphere.github.io/docs/architecture/) · [Transports](https://atmosphere.github.io/docs/tutorial/04-transports/) · [Broadcaster](https://atmosphere.github.io/docs/tutorial/05-broadcaster/) · [WebTransport](https://atmosphere.github.io/docs/reference/webtransport/) · [Clustering](https://atmosphere.github.io/docs/tutorial/16-clustering/) |
| AI runtimes | [AI adapters](https://atmosphere.github.io/docs/tutorial/11-ai-adapters/) · [Runtime selection](https://atmosphere.github.io/docs/reference/runtime-selection/) · [AI reference](https://atmosphere.github.io/docs/reference/ai/) |
| Agents & protocols | [`@Agent`](https://atmosphere.github.io/docs/agents/agent/) · [MCP](https://atmosphere.github.io/docs/reference/mcp/) · [A2A](https://atmosphere.github.io/docs/agents/a2a/) · [AG-UI](https://atmosphere.github.io/docs/agents/agui/) · [Coordinator](https://atmosphere.github.io/docs/agents/coordinator/) · [Channels](https://atmosphere.github.io/docs/tutorial/23-channels/) |
| Governance & verification | [Governance](https://atmosphere.github.io/docs/reference/governance/) · [Agent scope](https://atmosphere.github.io/docs/tutorial/31-agent-scope/) · [Plan-and-verify (Meijer)](https://atmosphere.github.io/docs/tutorial/33-plan-and-verify/) · [Tool approval](https://atmosphere.github.io/docs/reference/tool-approval-policy/) · [OWASP Agentic matrix](https://atmosphere.github.io/docs/tutorial/32-owasp-agentic-matrix/) |
| Durability & state | [Durable sessions](https://atmosphere.github.io/docs/reference/durable-sessions/) · [Durable HITL](https://atmosphere.github.io/docs/tutorial/24-durable-hitl/) · [Checkpoints](https://atmosphere.github.io/docs/reference/checkpoint/) · [Interactions](https://atmosphere.github.io/docs/reference/interactions/) |
| Operations | [Admin & control plane](https://atmosphere.github.io/docs/reference/admin/) · [Observability](https://atmosphere.github.io/docs/reference/observability/) · [Benchmarks](https://atmosphere.github.io/docs/reference/benchmarks/) |
| Clients | [JavaScript](https://atmosphere.github.io/docs/clients/javascript/) · [React Native](https://atmosphere.github.io/docs/clients/react-native/) · [Java / wAsync](https://atmosphere.github.io/docs/clients/java/) |

## Commercial Support

Production support tiers, compliance attestation, Microsoft Agent Governance Toolkit interop, plan-and-verify adoption, A2A v1.0.0 alignment, and legacy Atmosphere 2.x / 3.x long-term support are available from [Async-IO](https://async-io.live/#support). Book a 30-minute architecture call: [async-io.live/contact](https://async-io.live/contact/).

## Companion Projects

| Project | Description |
|---|---|
| [atmosphere-skills](https://github.com/Atmosphere/atmosphere-skills) | Curated agent skill files: personality, tools, guardrails |
| [homebrew-tap](https://github.com/Atmosphere/homebrew-tap) | Homebrew formulae for the Atmosphere CLI |

## License

Apache 2.0 - Copyright 2008-2026 [Async-IO.org](https://async-io.live)
