<p align="center">
  <img src="logo.png" alt="Atmosphere" width="120"/>
</p>

<h1 align="center">Atmosphere</h1>

<p align="center">
  <strong>A portable layer across Java AI runtimes.</strong><br/>
  Write <code>@Agent</code> once against a unified API (tool calling, memory, streaming, structured output); swap the runtime — Spring AI, LangChain4j, Google ADK, Embabel, JetBrains Koog, Microsoft Semantic Kernel, or built-in OpenAI — by changing one dependency. <code>@Coordinator</code> orchestrates multi-agent fleets with parallel, sequential, and conditional routing. Served over transports (WebTransport/HTTP3, WebSocket, SSE, long-polling, gRPC) and protocols (MCP, A2A, AG-UI). Built by Async-IO.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/org.atmosphere/atmosphere-runtime"><img src="https://img.shields.io/maven-central/v/org.atmosphere/atmosphere-runtime?label=Maven%20Central&color=blue" alt="Maven Central"/></a>
  <a href="https://www.npmjs.com/package/atmosphere.js"><img src="https://img.shields.io/npm/v/atmosphere.js?label=atmosphere.js&color=blue" alt="npm"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/ci.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI: Core"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/e2e.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/e2e.yml/badge.svg?branch=main" alt="CI: E2E"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/ci-js.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/ci-js.yml/badge.svg?branch=main" alt="CI: atmosphere.js"/></a>
</p>

---

Atmosphere is a portable layer across Java AI runtimes. Your application code declares **what** it does against a unified API — tool calling, memory, streaming, structured output — and the framework handles **which runtime** executes it (Spring AI, LangChain4j, Google ADK, Embabel, JetBrains Koog, Microsoft Semantic Kernel, or built-in OpenAI) and **how** it's delivered (WebTransport/HTTP3, WebSocket, SSE, long-polling, gRPC, MCP, A2A, AG-UI, Slack, Telegram, Discord). A single `@Agent` class runs on any runtime and serves any transport — swap either by changing one dependency.

## Quick Start

```bash
brew install Atmosphere/tap/atmosphere

# or

curl -fsSL https://raw.githubusercontent.com/Atmosphere/atmosphere/main/cli/install.sh | sh

# Run a built-in agent sample
atmosphere run spring-boot-multi-agent-startup-team

# Or scaffold your own project from a sample
atmosphere new my-agent --template ai-chat

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

## Key Features

**[Multi-Agent Orchestration](https://atmosphere.github.io/docs/agents/coordinator/)** — `@Coordinator` manages a fleet of agents with parallel fan-out, sequential pipelines, conditional routing, coordination journal, and result evaluation. Test with `StubAgentFleet` — no infrastructure needed.

**[Agent Handoffs & Human-in-the-Loop](https://atmosphere.github.io/docs/reference/ai/)** — Transfer conversations between agents with `session.handoff()`. Pause tool execution with `@RequiresApproval` for human-in-the-loop approval — the virtual thread parks cheaply until the client approves or denies.

**[Durable HITL Workflows](https://atmosphere.github.io/docs/reference/checkpoint/)** — `CheckpointStore` SPI persists agent workflow state as parent-chained snapshots with fork semantics. Pause workflows without holding a live thread; resume via REST or programmatic replay. Pairs with `atmosphere-durable-sessions` for streaming reconnect + workflow continuation.

**[7 AI Runtimes](https://atmosphere.github.io/docs/reference/ai/)** — Built-in, LangChain4j, Spring AI, Google ADK, Embabel, JetBrains Koog, Microsoft Semantic Kernel. Switch backends by changing one Maven dependency. All share tool calling, structured output, conversation memory, usage tracking, human-in-the-loop approval, multi-modal input, prompt caching, and embeddings.

**[3 Agent Protocols](https://atmosphere.github.io/docs/agents/a2a/)** — MCP (tools for Claude, Copilot, Cursor), A2A (agent-to-agent via JSON-RPC), AG-UI (streaming state to frontends). Auto-registered from classpath.

**[6 Channels](https://atmosphere.github.io/docs/tutorial/23-channels/)** — Web, Slack, Telegram, Discord, WhatsApp, Messenger. Set a bot token and the same `@Command` + AI pipeline works everywhere.

**[Skill Files](https://atmosphere.github.io/docs/agents/skills/)** — Markdown system prompts with sections for tools, guardrails, and channels. Auto-discovered from classpath. Browse curated skills in the [Atmosphere Skills](https://github.com/Atmosphere/atmosphere-skills) registry.

**[Long-Term Memory](https://atmosphere.github.io/docs/agents/coordinator/)** — Agents remember users across sessions. `LongTermMemoryInterceptor` extracts facts via LLM and injects them into future system prompts. Three strategies: on session close, per message, or periodic.

**[Conversation Memory](https://atmosphere.github.io/docs/reference/ai/)** — Pluggable compaction strategies (sliding window, LLM summarization). Durable sessions via SQLite or Redis survive server restarts.

**[Eval Assertions](https://atmosphere.github.io/docs/reference/testing/)** — `LlmJudge` tests agent quality with `meetsIntent()`, `isGroundedIn()`, and `hasQuality()`. `StubAgentFleet` and `CoordinatorAssertions` for testing coordinators without infrastructure.

**[15 Event Types](https://atmosphere.github.io/docs/reference/ai/)** — `AiEvent` sealed interface: text deltas, tool start/result/error, agent steps, handoffs, approval prompts, structured output, routing decisions. Normalized across all runtimes.

**[5 Transports](https://atmosphere.github.io/docs/tutorial/04-transports/)** — WebTransport/HTTP3, WebSocket, SSE, Long-Polling, gRPC. Automatic fallback, reconnection, heartbeats, message caching. First Java framework with [WebTransport over HTTP/3](https://atmosphere.github.io/docs/webtransport/) — auto-detected via `AsyncSupport`: native Jetty 12 QUIC connector (zero-config, no sidecar) or Reactor Netty HTTP/3 sidecar for Tomcat/Undertow. Self-signed cert for dev, `Alt-Svc` header advertisement, transparent fallback to WebSocket.

**[Authentication](modules/spring-boot-starter/README.md#webtransport-over-http3)** — `TokenValidator` + `TokenRefresher` SPIs with `AuthInterceptor`. Define a validator bean and connections without valid tokens are rejected at the WebSocket/HTTP upgrade. Auto-configured via Spring Boot.

**[Observability](https://atmosphere.github.io/docs/reference/observability/)** — OpenTelemetry tracing, Micrometer metrics, AI token usage tracking. Auto-configured with Spring Boot.

**[Admin Control Plane](https://atmosphere.github.io/docs/reference/admin/)** — Real-time dashboard at `/atmosphere/admin/`, 25 REST endpoints, WebSocket event stream, and MCP tools for managing agents, broadcasters, tasks, and runtimes. AI-manages-AI via MCP tool registration.

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
| [channels-chat](samples/spring-boot-channels-chat/) | Slack, Telegram, WhatsApp, Messenger |

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

**Requirements:** Java 21+ &middot; Spring Boot 4.0.5+ or Quarkus 3.31.3+ &middot; Current release: `4.0.36-SNAPSHOT`

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
