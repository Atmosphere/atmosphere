<p align="center">
  <img src="logo.png" alt="Atmosphere" width="120"/>
</p>

<h1 align="center">Atmosphere</h1>

<p align="center">
  <strong>The real-time infrastructure layer for Java AI agents.</strong><br/>
  Pick any LLM library. Build once with <code>@Agent</code> — deliver over WebSocket, SSE, gRPC, MCP, A2A, AG-UI, or any transport.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/org.atmosphere/atmosphere-runtime"><img src="https://img.shields.io/maven-central/v/org.atmosphere/atmosphere-runtime?label=Maven%20Central&color=blue" alt="Maven Central"/></a>
  <a href="https://www.npmjs.com/package/atmosphere.js"><img src="https://img.shields.io/npm/v/atmosphere.js?label=atmosphere.js&color=blue" alt="npm"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml/badge.svg?branch=main" alt="Atmosphere CI"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-e2e.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-e2e.yml/badge.svg?branch=main" alt="E2E Tests"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-js-ci.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-js-ci.yml/badge.svg?branch=main" alt="Atmosphere.js CI"/></a>
</p>

---

Atmosphere is a transport-agnostic runtime for Java. Your application code declares **what** it does — the framework handles **how** it's delivered. A single `@Agent` class can serve browsers over WebSocket, expose tools via MCP, accept tasks from other agents via A2A, stream state to frontends via AG-UI, and route messages to Slack, Telegram, or Discord — all without changing a line of code. Skills follow the [Agent Skills](https://agentskills.io/specification) standard.

## `@Agent`

One annotation. The framework wires everything based on what's in the class and what's on the classpath.

```java
@Agent(name = "my-agent", description = "What this agent does")
public class MyAgent {

    @Prompt
    public void onMessage(String message, StreamingSession session) {
        session.stream(message);  // LLM streaming via configured backend
    }

    @Command(value = "/status", description = "Show status")
    public String status() {
        return "All systems operational";  // Executes instantly, no LLM cost
    }

    @AiTool(name = "lookup", description = "Look up data")
    public String lookup(@Param("query") String query) {
        return dataService.find(query);  // Callable by the LLM during inference
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
| (built-in) | Console UI at `/atmosphere/console/` — auto-detects the agent |

### Full-Stack vs. Headless

An `@Agent` with a `@Prompt` method gets a WebSocket UI. An `@Agent` with only `@AgentSkill` methods runs headless — A2A and MCP only, no browser endpoint. The framework detects the mode automatically.

```java
// Headless: A2A/MCP only
@Agent(name = "research", description = "Web research agent")
public class ResearchAgent {

    @AgentSkill(id = "search", name = "Search", description = "Search the web")
    @AgentSkillHandler
    public void search(TaskContext task, @AgentSkillParam(name = "query") String query) {
        task.addArtifact(Artifact.text(doSearch(query)));
        task.complete("Done");
    }
}
```

Full-stack and headless agents can collaborate via A2A — full-stack agents delegate to headless specialists using Agent Card discovery and JSON-RPC task delegation.

## Skills

A skill file is a Markdown document with YAML frontmatter that becomes the agent's system prompt. Sections like `## Tools`, `## Skills`, and `## Guardrails` are also parsed for protocol metadata.

```markdown
---
name: my-agent
description: "What this agent does"
---
# My Agent
You are a helpful assistant.

## Tools
- lookup: Search the knowledge base
- calculate: Perform calculations

## Guardrails
- Never execute destructive operations without confirmation
```

### Auto-Discovery

Drop a skill file at `META-INF/skills/{agent-name}/SKILL.md` on the classpath and `@Agent` picks it up automatically — no `skillFile` attribute needed. This means skills can be distributed as Maven JARs.

The framework also checks `prompts/{agent-name}.md` and `prompts/skill.md` as fallbacks.

### Import from GitHub

Point the CLI at any skill file on GitHub. Atmosphere generates the `@Agent` class, wires the Spring Boot project, and the built-in console UI is ready to use — one command to a running agent:

```bash
atmosphere import https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md
cd frontend-design && LLM_API_KEY=your-key ./mvnw spring-boot:run
# Open http://localhost:8080/atmosphere/console/ — chat with your agent
```

The import command parses YAML frontmatter into `@Agent` annotations, extracts `## Tools` into `@AiTool` method stubs, and places the skill file at `META-INF/skills/` for auto-discovery. The generated project compiles and runs immediately — WebSocket streaming, MCP, A2A, AG-UI, gRPC, and the Atmosphere AI Console are all wired automatically.

Compatible with [Anthropic](https://github.com/anthropics/skills), [Antigravity](https://github.com/sickn33/antigravity-awesome-skills) (1,200+ skills), [K-Dense AI](https://github.com/K-Dense-AI/claude-scientific-skills), and any repository following the [Agent Skills](https://agentskills.io/specification) format.

Remote imports are restricted to [trusted sources](cli/README.md) by default. Use `--trust` for other URLs.

## Transports

Your code never changes. Atmosphere picks the best transport, handles fallback, reconnection, heartbeats, and message caching.

| Transport | Direction | Use Case |
|-----------|-----------|----------|
| **WebSocket** | Full-duplex | Default for browsers and agents |
| **SSE** | Server → Client | Fallback when WebSocket is unavailable |
| **Long-Polling** | Request/Response | Universal fallback for restrictive networks |
| **gRPC** | Full-duplex | Service-to-service binary streaming |

## Agent Protocols

Auto-registered based on classpath — add the module, the endpoint appears. No configuration.

| Protocol | Direction | Purpose | Annotations |
|----------|-----------|---------|-------------|
| **MCP** | Agent &#8596; Tools | Expose tools to any MCP client | `@McpTool`, `@McpResource`, `@McpPrompt` |
| **A2A** | Agent &#8596; Agent | Agent Card discovery and task delegation over JSON-RPC | `@AgentSkill`, `@AgentSkillHandler` |
| **AG-UI** | Agent &#8596; Frontend | Stream agent state (steps, tool calls, text deltas) via SSE | `@AgUiEndpoint`, `@AgUiAction` |

## Channels

Set a bot token — interact with your agent from any messaging platform. Same `@Command` methods and AI pipeline, every channel.

| Channel | Activation |
|---------|-----------|
| Web (WebSocket/SSE) | Built-in |
| Slack | `SLACK_BOT_TOKEN` |
| Telegram | `TELEGRAM_BOT_TOKEN` |
| Discord | `DISCORD_BOT_TOKEN` |
| WhatsApp | `WHATSAPP_ACCESS_TOKEN` |
| Messenger | `MESSENGER_PAGE_TOKEN` |

See [docs/protocols.md](docs/protocols.md) and [docs/channels.md](docs/channels.md).

## LLM Backends

Atmosphere is not an LLM library — it's the layer between the LLM and your users. Your code is never locked to a single AI framework. `@Agent`, `@AiTool`, `@Command`, skill files, conversation memory, guardrails, and protocol exposure are all **framework-independent**. The LLM backend is a pluggable dependency — swap it without touching your agent code.

| Backend | Dependency | What It Brings |
|---------|-----------|-------------|
| Built-in (Gemini / OpenAI / Ollama) | `atmosphere-ai` | Direct OpenAI-compatible client. Zero framework overhead. Good starting point — upgrade to a full AI framework later without rewriting your agent. |
| Spring AI | `atmosphere-spring-ai` | Spring AI's `ChatClient`, embeddings, vector stores, and RAG pipelines. Atmosphere adds what Spring AI doesn't: real-time WebSocket streaming, MCP/A2A/AG-UI exposure, and multi-channel delivery. Your Spring AI RAG pipeline becomes portable across every transport and protocol. |
| LangChain4j | `atmosphere-langchain4j` | LangChain4j chains, agents, and tool calling. `@AiTool` methods are automatically bridged to LangChain4j's tool format. You get LangChain4j's inference capabilities with Atmosphere's delivery infrastructure. |
| Google ADK | `atmosphere-adk` | Google's Agent Development Kit for multi-agent orchestration. ADK agents run inside Atmosphere's transport layer with real-time WebSocket visibility and protocol exposure that ADK alone doesn't provide. |
| Embabel | `atmosphere-embabel` | Embabel's goal-driven agent framework. Embabel agents stream their output through Atmosphere to browsers, MCP clients, and messaging channels. |

**Why not use Spring AI / LangChain4j / ADK directly?**

You can — and you should use their LLM capabilities. But they handle **inference**, not **delivery**. When you add Atmosphere:

- **Streaming** — LLM tokens stream to browsers via WebSocket in real-time, not buffered as HTTP responses
- **Protocol exposure** — your RAG pipeline is automatically accessible via MCP, A2A, and AG-UI with zero extra code
- **Multi-channel** — the same agent responds on Web, Slack, Telegram, Discord — not just HTTP
- **Conversation memory** — multi-turn context managed by the framework, works identically across all backends
- **Tool portability** — `@AiTool` methods work with every backend. Start with built-in, move to Spring AI later — tools don't change
- **RAG portability** — build your retrieval pipeline with any backend's vector store. Atmosphere delivers the augmented response to every transport and protocol
- **Skill file portability** — same Markdown skill file (system prompt, tools, guardrails) works across all backends
- **Agent composition** — headless agents collaborate via A2A regardless of which backend each one uses. A Spring AI agent can delegate to a LangChain4j agent
- **Durable sessions** — conversation state survives server restarts (SQLite, Redis), independent of backend
- **No lock-in** — switch from LangChain4j to Spring AI by changing one Maven dependency. Your `@Agent`, tools, commands, skill file, and tests stay the same

## Annotation Compatibility

Atmosphere 4.x is fully backward-compatible with 3.x annotations. All `@ManagedService` lifecycle annotations (`@Ready`, `@Message`, `@Disconnect`, `@Heartbeat`, `@PathParam`, `Broadcaster` injection) work in `@Agent`. Protocol annotations (`@McpTool`, `@AgentSkill`) can be added directly to existing `@ManagedService` classes — no migration required.

See the [full annotation reference](docs/annotations.md) for all supported annotations, parameters, and usage examples.

| Annotation | `@Agent` | `@ManagedService` | Purpose |
|-----------|:--------:|:-----------------:|---------|
| `@Prompt` | yes | — | LLM streaming entry point |
| `@Command` | yes | — | Slash commands (no LLM cost) |
| `@AiTool` / `@Param` | yes | — | LLM-callable tool methods |
| `@McpTool` / `@McpResource` / `@McpPrompt` | yes | yes | MCP protocol exposure |
| `@AgentSkill` / `@AgentSkillHandler` | yes | yes | A2A protocol exposure |
| `@Ready` | yes | yes | Connection established |
| `@Disconnect` | yes | yes | Connection closed |
| `@Heartbeat` | yes | yes | Keep-alive received |
| `@Message` (encoders/decoders) | yes | yes | Raw message handling |
| `@Inject @Named("...")` `Broadcaster` | yes | yes | Pub/sub to Kafka, Redis, etc. |
| `@PathParam` | yes | yes | URL path parameter injection |
| `@DeliverTo` | — | yes | Message delivery scope |
| `@Singleton` | — | yes | Single instance per path |
| `@Get` / `@Post` / `@Put` / `@Delete` | — | yes | HTTP method handlers |

## Client — atmosphere.js

```bash
npm install atmosphere.js                                    # add to existing project
npx create-atmosphere-app my-app --template ai-chat          # scaffold a new React app
```

```tsx
import { AtmosphereProvider, useStreaming } from 'atmosphere.js/react';

function Chat() {
  const { fullText, isStreaming, send } = useStreaming({
    request: { url: '/atmosphere/agent/my-agent', transport: 'websocket' },
  });
  return (
    <div>
      <button onClick={() => send('Hello')}>Send</button>
      <p>{fullText}</p>
    </div>
  );
}
```

[Vue](atmosphere.js/README.md#vue), [Svelte](atmosphere.js/README.md#svelte), and [React Native](atmosphere.js/README.md#react-native) bindings also available. See [atmosphere.js](atmosphere.js/README.md).

## Samples

| Category | Sample | Description |
|----------|--------|-------------|
| Multi-Agent | [startup team](samples/spring-boot-multi-agent-startup-team/) | 5 agents collaborate via A2A with real-time visualization |
| Agent | [dentist agent](samples/spring-boot-dentist-agent/) | Commands, tools, skill file, Slack and Telegram |
| AI Streaming | [ai-chat](samples/spring-boot-ai-chat/) | Swap backend via one dependency |
| AI Streaming | [ai-tools](samples/spring-boot-ai-tools/) | Framework-agnostic tool calling |
| AI Streaming | [rag-chat](samples/spring-boot-rag-chat/) | RAG with document retrieval |
| AI Streaming | [ai-classroom](samples/spring-boot-ai-classroom/) | Multi-room, multi-persona streaming |
| AI Streaming | [ai-routing](samples/spring-boot-spring-ai-routing/) | Content-based model routing with Spring AI |
| Protocol | [mcp-server](samples/spring-boot-mcp-server/) | MCP tools, resources, and prompts |
| Protocol | [a2a-agent](samples/spring-boot-a2a-agent/) | Headless A2A agent |
| Protocol | [agui-chat](samples/spring-boot-agui-chat/) | AG-UI streaming via SSE |
| Infrastructure | [channels](samples/spring-boot-channels-chat/) | Slack, Telegram, Discord, WhatsApp, Messenger |
| Infrastructure | [durable-sessions](samples/spring-boot-durable-sessions/) | Survive server restarts with SQLite |
| Infrastructure | [otel-chat](samples/spring-boot-otel-chat/) | OpenTelemetry tracing |
| Chat | [spring-boot-chat](samples/spring-boot-chat/) | WebSocket chat with Spring Boot |
| Chat | [quarkus-chat](samples/quarkus-chat/) | WebSocket chat with Quarkus |
| Chat | [grpc-chat](samples/grpc-chat/) | Chat over gRPC transport |
| Chat | [embedded-jetty](samples/embedded-jetty-websocket-chat/) | Embedded Jetty, no framework |

[All 18 samples](samples/) · `atmosphere install` for interactive picker · [CLI reference](cli/README.md)

## Requirements

Java 21+ · Spring Boot 4.0+ · Quarkus 3.21+ · Virtual threads enabled by default.

## Documentation

[Tutorial](https://atmosphere.github.io/docs/tutorial/01-introduction/) · [Full docs](https://atmosphere.github.io/docs/) · [CLI](cli/README.md) · [Samples](samples/) · [Javadoc](https://atmosphere.github.io/apidocs/)

## Support

Commercial support and consulting available through [Async-IO.org](https://async-io.org).

## License

Apache 2.0 — @Copyright 2008-2026 [Async-IO.org](https://async-io.org)
