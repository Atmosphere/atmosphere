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

What this registers:
- **WebSocket endpoint** at `/atmosphere/agent/my-agent` — streaming AI chat with conversation memory
- **MCP endpoint** at `/atmosphere/agent/my-agent/mcp` — if `atmosphere-mcp` is on the classpath
- **A2A endpoint** at `/atmosphere/agent/my-agent/a2a` — if `atmosphere-a2a` is on the classpath
- **AG-UI endpoint** at `/atmosphere/agent/my-agent/agui` — if `atmosphere-agui` is on the classpath
- **Console UI** at `/atmosphere/console/` — built-in, auto-detects the agent
- **Slash commands** — `/status` executes instantly, `/help` auto-generated
- **Multi-channel** — add `atmosphere-channels` + a bot token and the same agent responds on Slack, Telegram, Discord, WhatsApp, Messenger

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

Atmosphere is not an LLM library. Your LLM library calls the model. Atmosphere delivers the response. Swap the backend by changing one Maven dependency — your `@Agent`, `@AiTool`, and `@Command` code stays the same.

| Backend | Dependency | What You Get |
|---------|-----------|-------------|
| Built-in (Gemini / OpenAI / Ollama) | `atmosphere-ai` | Direct OpenAI-compatible client. Zero framework overhead. Works with any API that speaks the OpenAI chat completions format. |
| Spring AI | `atmosphere-spring-ai` | Spring AI's `ChatClient`, embeddings, vector stores, and RAG pipelines — streamed over WebSocket/MCP/A2A instead of HTTP. Portable RAG: build once with Spring AI's `VectorStore`, deliver to every transport. |
| LangChain4j | `atmosphere-langchain4j` | LangChain4j chains, agents, and tool calling — with Atmosphere handling the streaming delivery. `@AiTool` methods are automatically bridged to LangChain4j's tool format. |
| Google ADK | `atmosphere-adk` | Google's Agent Development Kit for multi-agent orchestration. ADK agents run inside Atmosphere's transport layer with real-time WebSocket visibility. |
| Embabel | `atmosphere-embabel` | Embabel's goal-driven agent framework. Embabel agents stream their output through Atmosphere to browsers, MCP clients, and messaging channels. |

**What Atmosphere adds on top of each backend:**
- **Streaming delivery** — LLM tokens streamed to browsers via WebSocket, not buffered as HTTP responses
- **Protocol exposure** — your Spring AI RAG pipeline is automatically accessible via MCP, A2A, and AG-UI
- **Multi-channel** — the same LangChain4j chain responds on Web, Slack, and Telegram
- **Conversation memory** — multi-turn context managed by the framework, independent of which LLM backend you use
- **Tool portability** — `@AiTool` methods work with every backend. Write the tool once, swap the LLM library freely
- **Guardrails and filters** — pre/post processing applied before any backend processes the message

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
| `Broadcaster` injection | yes | yes | Pub/sub to Kafka, Redis, etc. |
| `@PathParam` | yes | yes | URL path parameter injection |
| `@DeliverTo` | — | yes | Message delivery scope |
| `@Singleton` | — | yes | Single instance per path |
| `@Get` / `@Post` / `@Put` / `@Delete` | — | yes | HTTP method handlers |

## Client — atmosphere.js

```bash
npm install atmosphere.js
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

Vue, Svelte, and React Native bindings also available. See [atmosphere.js](atmosphere.js/README.md).

## Samples

| Category | Sample | Description |
|----------|--------|-------------|
| Multi-Agent | [startup team](samples/spring-boot-multi-agent-startup-team/) | 5 agents collaborate via A2A with real-time visualization |
| Agent | [dentist agent](samples/spring-boot-dentist-agent/) | Commands, tools, skill file, Slack and Telegram |
| AI Streaming | [ai-chat](samples/spring-boot-ai-chat/) | Swap backend via one dependency |
| AI Streaming | [ai-tools](samples/spring-boot-ai-tools/) | Framework-agnostic tool calling |
| AI Streaming | [rag-chat](samples/spring-boot-rag-chat/) | RAG with document retrieval |
| Protocol | [mcp-server](samples/spring-boot-mcp-server/) | MCP tools, resources, and prompts |
| Protocol | [a2a-agent](samples/spring-boot-a2a-agent/) | Headless A2A agent |
| Protocol | [agui-chat](samples/spring-boot-agui-chat/) | AG-UI streaming via SSE |
| Infrastructure | [channels](samples/spring-boot-channels-chat/) | Slack, Telegram, Discord, WhatsApp, Messenger |
| Infrastructure | [durable-sessions](samples/spring-boot-durable-sessions/) | Survive server restarts with SQLite |
| Chat | [spring-boot-chat](samples/spring-boot-chat/) | WebSocket chat |
| Chat | [quarkus-chat](samples/quarkus-chat/) | WebSocket chat with Quarkus |

[All 18 samples](samples/) · `atmosphere install` for interactive picker · [CLI reference](cli/README.md)

## Requirements

Java 21+ · Spring Boot 4.0+ · Quarkus 3.21+ · Virtual threads enabled by default.

## Documentation

[Tutorial](https://atmosphere.github.io/docs/tutorial/01-introduction/) · [Full docs](https://atmosphere.github.io/docs/) · [CLI](cli/README.md) · [Samples](samples/) · [Javadoc](https://atmosphere.github.io/apidocs/)

## Support

Commercial support and consulting available through [Async-IO.org](https://async-io.org).

## License

Apache 2.0 — @Copyright 2008-2026 [Async-IO.org](https://async-io.org)
