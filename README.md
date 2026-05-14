<p align="center">
  <img src="logo.png" alt="Atmosphere" width="120"/>
</p>

<h1 align="center">Atmosphere</h1>

<p align="center">
  JVM runtime for streaming, governable AI agents. Declare behavior with <code>@Agent</code>; Atmosphere owns the transport, runtime dispatch, reconnect, authorization, observability, and governance path.
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
| Stream to real clients | WebTransport over HTTP/3, WebSocket, SSE, long-polling, and gRPC through the same broadcaster pipeline |
| Swap AI integrations | One `AgentRuntime` SPI with nine runtime adapters and contract-tested capability flags |
| Govern execution | Policy admission, `@AgentScope`, human approval, plan-and-verify, cost ceilings, PII rewriting, and admin kill switches |
| Resume long runs | Durable sessions, run IDs, replay buffers, checkpoints, and REST approval/resume surfaces |
| Expose the same agent everywhere | Browser endpoints plus MCP, A2A, AG-UI, Slack, Telegram, Discord, WhatsApp, and Messenger modules |

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
| Runtime adapter | The Atmosphere integration that implements `AgentRuntime` | Built-in, Spring AI, LangChain4j, Google ADK, Embabel, Koog, Semantic Kernel, AgentScope, Spring AI Alibaba |
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
| `atmosphere-agent` | Browser endpoint at `/atmosphere/agent/my-agent`, streaming AI dispatch, memory, commands, `/help` |
| `atmosphere-mcp` | MCP endpoint at `/atmosphere/agent/my-agent/mcp` |
| `atmosphere-a2a` | A2A endpoint at `/atmosphere/agent/my-agent/a2a` with Agent Card discovery |
| `atmosphere-agui` | AG-UI endpoint at `/atmosphere/agent/my-agent/agui` |
| `atmosphere-channels` | Slack, Telegram, Discord, WhatsApp, and Messenger dispatch |
| `atmosphere-admin` | Admin dashboard and `/api/admin/*` control surfaces |
| built-in console | Console UI at `/atmosphere/console/` |

## AI Runtime Adapters

`atmosphere-ai` ships the `AgentRuntime` SPI plus the Built-in OpenAI-compatible adapter. Eight framework adapters live in separate modules. Drop one runtime adapter on the classpath and the same `@Agent` code dispatches through it.

Capabilities are intentionally not identical. The authoritative matrix is pinned by `AbstractAgentRuntimeContractTest.expectedCapabilities()`, so a runtime cannot drift from its declared feature set without breaking tests.

| Runtime adapter | Backing framework | Spring Boot | Capability highlights | Notes |
|---|---|---|---|---|
| `atmosphere-ai` (Built-in) | OpenAI-compatible HTTP client | 3.5 / 4.0 | tool calling, JSON mode, vision, audio, prompt caching, token usage, native retry, tool-call deltas | Default. Works with OpenAI-compatible endpoints such as OpenAI, Gemini compatibility endpoints, Ollama, and local proxies. |
| `atmosphere-spring-ai` | Spring AI 2.0.0-M6 | 4.0 | tool calling, structured output, vision, audio, prompt caching, token usage | Best fit for Spring Boot applications already using Spring AI. |
| `atmosphere-langchain4j` | LangChain4j 1.14.0 | 4.0 | tool calling, structured output, vision, audio, prompt caching, token usage | Best fit for LangChain4j tool ecosystems and non-Spring services. |
| `atmosphere-adk` | Google ADK 1.2.0 | 4.0 | agent orchestration, tool calling, multi-modal, prompt caching | Multi-agent runtime with `AGENT_ORCHESTRATION`. |
| `atmosphere-koog` | JetBrains Koog 0.8.0 | 4.0 | agent orchestration, tool calling, multi-modal, prompt caching, cancellation | Multi-agent runtime. |
| `atmosphere-semantic-kernel` | Microsoft Semantic Kernel 1.5.0 | 4.0 | tool calling, structured output, token usage | No vision/audio path through the SK Java SDK today. |
| `atmosphere-agentscope` | Alibaba AgentScope 1.0.12 | 4.0 | structured output, conversation memory, token usage, cancellation | No native tool-call dispatch in the SDK; tools must be invoked manually. |
| `atmosphere-embabel` | Embabel 0.3.5 | 3.5 only | agent orchestration, tool calling, vision, conversation memory | Requires `atmosphere-spring-boot3-starter` and the `-Pspring-boot3` profile. |
| `atmosphere-spring-ai-alibaba` | Spring AI Alibaba 1.1.2.2 | 3.5 only | structured output, conversation memory | Buffered by `ReactAgent.call()`; token-by-token streaming should use another adapter until Alibaba ships a Spring AI 2.x-aligned agent framework. |

See the full [capability matrix](modules/ai/README.md#capability-matrix) for text streaming, tool calling, structured output, system prompts, agent orchestration, conversation memory, tool approval, vision, audio, multi-modal, prompt caching, token usage, retry, passivation, and tool-call deltas.

## Enterprise Controls

Atmosphere keeps governance on the critical path rather than as an afterthought.

| Control | Module | What it does |
|---|---|---|
| Policy admission | `atmosphere-ai` | `GovernancePolicy`, `PolicyRing`, allow/deny lists, rate limits, time windows, metadata requirements |
| Scope enforcement | `atmosphere-ai` | `@AgentScope` blocks out-of-purpose prompts before runtime dispatch |
| Human approval | `atmosphere-agent`, `atmosphere-ai` | command confirmations, permission modes, tool approval policies |
| Plan-and-verify | `atmosphere-verifier` | verifies LLM-emitted tool workflows before execution; supports allowlist, well-formedness, capability, taint, automaton, and SMT verifiers |
| PII and cost controls | `atmosphere-ai` | stream-level PII redaction, token usage, per-tenant cost ceilings |
| Admin control plane | `atmosphere-admin` | dashboard, REST/MCP control surfaces, kill switches, flow viewer, governance decisions |
| Compliance evidence | `atmosphere-ai`, `atmosphere-admin` | OWASP Agentic Top 10, EU AI Act, HIPAA, SOC 2 matrices and AGT-compatible verification output |
| Sandbox execution | `atmosphere-sandbox` | `DockerSandboxProvider` default and `SandboxProvider` SPI for isolated code execution |

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
@AiEndpoint("/support")
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
| [ai-chat](samples/spring-boot-ai-chat/) | Streaming AI chat with auth, WebTransport, caching, and runtime adapter portability |
| [ai-tools](samples/spring-boot-ai-tools/) | Framework-agnostic `@AiTool` methods and approval gates |
| [ai-classroom](samples/spring-boot-ai-classroom/) | Multi-room collaborative AI with React Native / Expo client |
| [guarded-email-agent](samples/spring-boot-guarded-email-agent/) | Plan-and-verify taint protection before any email tool fires |
| [ms-governance-chat](samples/spring-boot-ms-governance-chat/) | Microsoft Agent Governance Toolkit-compatible YAML and decision endpoint |
| [mcp-server](samples/spring-boot-mcp-server/) | MCP tools, resources, prompts, and streamable HTTP |
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
- **Protocols**: `atmosphere-mcp`, `atmosphere-a2a`, `atmosphere-agui`
- **Channels**: `atmosphere-channels`
- **Multi-agent**: `atmosphere-coordinator`
- **Admin/control plane**: `atmosphere-admin`
- **Plan-and-verify**: `atmosphere-verifier`
- **Sandbox**: `atmosphere-sandbox`
- **Durable sessions**: `atmosphere-durable-sessions` plus `atmosphere-durable-sessions-sqlite` or `atmosphere-durable-sessions-redis`
- **Checkpoints**: `atmosphere-checkpoint`
- **Audit sinks**: `atmosphere-ai-audit-kafka`, `atmosphere-ai-audit-postgres`
- **Policy engines**: `atmosphere-ai-policy-rego` (OPA), `atmosphere-ai-policy-cedar` (AWS Cedar)

For Spring Boot 3.5 deployments, including Embabel or Spring AI Alibaba, use `atmosphere-spring-boot3-starter` and build with `-Pspring-boot3`.

**Requirements:** Java 21+ · Spring Boot 4.0.6 or Spring Boot 3.5 via `-Pspring-boot3` · Quarkus 3.35.2+ · current release from the Maven Central badge above.

## Documentation

[Tutorial](https://atmosphere.github.io/docs/tutorial/01-introduction/) · [Full docs](https://atmosphere.github.io/docs/) · [CLI](cli/README.md) · [Javadoc](https://atmosphere.github.io/apidocs/) · [Samples](samples/)

## Commercial Support

Production support tiers, compliance attestation, Microsoft Agent Governance Toolkit interop, plan-and-verify adoption, A2A v1.0.0 alignment, and legacy Atmosphere 2.x / 3.x long-term support are available from [Async-IO](https://async-io.live/#support). Book a 30-minute architecture call: [async-io.live/contact](https://async-io.live/contact/).

## Companion Projects

| Project | Description |
|---|---|
| [atmosphere-skills](https://github.com/Atmosphere/atmosphere-skills) | Curated agent skill files: personality, tools, guardrails |
| [homebrew-tap](https://github.com/Atmosphere/homebrew-tap) | Homebrew formulae for the Atmosphere CLI |

## License

Apache 2.0 - Copyright 2008-2026 [Async-IO.org](https://async-io.live)
