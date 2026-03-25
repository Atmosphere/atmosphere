<p align="center">
  <img src="logo.png" alt="Atmosphere" width="120"/>
</p>

<h1 align="center">Atmosphere</h1>

<p align="center">
  <strong>The real-time infrastructure layer for Java AI agents.</strong><br/>
  Pick any LLM library. Build once with <code>@Agent</code> — deliver over WebSocket, SSE, gRPC, MCP, A2A, AG-UI, or any transport.<br/>
  In production since 2008.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/org.atmosphere/atmosphere-runtime"><img src="https://img.shields.io/maven-central/v/org.atmosphere/atmosphere-runtime?label=Maven%20Central&color=blue" alt="Maven Central"/></a>
  <a href="https://www.npmjs.com/package/atmosphere.js"><img src="https://img.shields.io/npm/v/atmosphere.js?label=atmosphere.js&color=blue" alt="npm"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml/badge.svg?branch=main" alt="Atmosphere CI"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-e2e.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-e2e.yml/badge.svg?branch=main" alt="E2E Tests"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-js-ci.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-js-ci.yml/badge.svg?branch=main" alt="Atmosphere.js CI"/></a>
</p>

---

## Try It

```bash
brew install Atmosphere/tap/atmosphere    # or: curl -fsSL https://raw.githubusercontent.com/Atmosphere/atmosphere/main/cli/install.sh | sh
atmosphere run spring-boot-multi-agent-startup-team   # demo mode — no API key needed
```

Open `http://localhost:8080` — ask any business question and watch 5 AI agents collaborate in real-time. To run with a real LLM:

```bash
LLM_API_KEY=your-key atmosphere run spring-boot-multi-agent-startup-team
```

## Multi-Agent Startup Team

Five independent `@Agent` classes collaborate via the A2A protocol to deliver instant startup advisory briefings.

Four specialist agents — **Research** (web scraping), **Strategy** (market analysis), **Finance** (TAM/SAM/SOM projections), **Writer** (executive briefing) — each run headless with `@AgentSkill` methods. A **CEO agent** discovers them via Agent Cards, delegates via A2A over JSON-RPC, and synthesizes findings into a GO/NO-GO recommendation streamed live over WebSocket.

```java
// Full-stack agent: WebSocket UI + all protocols
@Agent(name = "startup-ceo", skillFile = "prompts/ceo-skill.md",
       description = "CEO agent — orchestrates research, strategy, finance, and writing")
public class CeoAgent {

    @AiTool(name = "delegate_research", description = "Delegate research to the research agent")
    public String delegateResearch(@Param("query") String query) {
        return a2aClient.sendTask("research", "web_search", Map.of("query", query));
    }

    @Prompt
    public void onMessage(String message, StreamingSession session) {
        session.stream(message);
    }
}

// Headless agent: A2A only, no WebSocket UI
@Agent(name = "research", endpoint = "/atmosphere/a2a/research",
       description = "Web research agent")
public class ResearchAgent {

    @AgentSkill(id = "web_search", name = "Search", description = "Search the web")
    @AgentSkillHandler
    public void search(TaskContext task, @AgentSkillParam(name = "query") String query) {
        task.addArtifact(Artifact.text(scrapeWeb(query)));
        task.complete("Done");
    }
}
```

See the [full source](samples/spring-boot-multi-agent-startup-team/).

## Transports & Protocols

Your code doesn't change. Atmosphere delivers to every subscriber — regardless of how they're connected.

| Layer | What | How |
|-------|------|-----|
| **Transports** | WebSocket, SSE, Long-Polling, gRPC | Auto-negotiated with fallback. Reconnection, heartbeats, message caching built-in. |
| **Agent Protocols** | MCP, A2A, AG-UI | Auto-registered based on classpath. Your `@Agent` is discoverable by Claude Desktop, other agents, and frontend frameworks. |
| **Channels** | Slack, Telegram, Discord, WhatsApp, Messenger | Set a bot token — `@Command` and AI responses route to every platform automatically. |

| Protocol | Direction | What It Does | Sample |
|----------|-----------|--------------|--------|
| **MCP** | Agent &#8596; Tools | Expose tools to Claude Desktop, Copilot, Cursor | [mcp-server](samples/spring-boot-mcp-server/) |
| **A2A** | Agent &#8596; Agent | Agent discovery via Agent Cards, task delegation over JSON-RPC | [a2a-agent](samples/spring-boot-a2a-agent/) |
| **AG-UI** | Agent &#8596; Frontend | Stream agent state (steps, tool calls, text deltas) via SSE | [agui-chat](samples/spring-boot-agui-chat/) |
| **WebSocket** | Agent &#8596; Browser | Full-duplex streaming with auto-reconnection | [ai-chat](samples/spring-boot-ai-chat/) |
| **SSE** | Agent &#8594; Browser | Server-sent events fallback | Built-in |
| **gRPC** | Agent &#8596; Service | Binary streaming for service-to-service | [grpc-chat](samples/grpc-chat/) |

Protocol exposure is automatic — add the module to your classpath, and the endpoint appears. See [docs/protocols.md](docs/protocols.md) and [docs/channels.md](docs/channels.md).

## `@Agent` — One Annotation, Everything Wired

**What `@Agent` wires for you:**

- **AI endpoint** at `/atmosphere/agent/{name}` — no servlet config, no router
- **Slash commands** — `@Command` methods execute instantly (no LLM cost), auto-generate `/help`
- **AI tools** — `@AiTool` methods callable by the LLM, portable across all backends
- **Skill file** — Markdown system prompt parsed for protocol metadata ([reference](docs/skill-files.md))
- **Multi-channel** — same commands and AI pipeline on Web, Slack, Telegram, Discord, WhatsApp, Messenger
- **Protocol exposure** — MCP, A2A, AG-UI auto-registered based on classpath
- **Conversation memory** — multi-turn by default
- **Headless mode** — auto-detected when there's no `@Prompt` (A2A/MCP only, no WebSocket UI)

For simpler cases without commands or channels, use `@AiEndpoint` directly:

```java
@AiEndpoint(path = "/atmosphere/ai-chat",
            systemPrompt = "You are a helpful assistant.",
            conversationMemory = true)
public class MyChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

## Pick Your LLM Library

Atmosphere is not an LLM library — it's the infrastructure layer underneath. Your LLM library calls the model. Atmosphere delivers the response.

| Backend | Dependency | Bridged via |
|---------|-----------|-------------|
| Built-in (Gemini/OpenAI/Ollama/[Embacle](https://github.com/dravr-ai/dravr-embacle)) | `atmosphere-ai` | direct |
| Spring AI | `atmosphere-spring-ai` | `SpringAiToolBridge` |
| LangChain4j | `atmosphere-langchain4j` | `LangChain4jToolBridge` |
| Google ADK | `atmosphere-adk` | `AdkToolBridge` |
| Embabel | `atmosphere-embabel` | `EmbabelAiSupport` |

Swap the backend by changing one Maven dependency. Your `@Agent`, `@AiTool`, and `@Command` code stays the same.

## Client — atmosphere.js

```bash
npm install atmosphere.js
```

```tsx
import { AtmosphereProvider, useStreaming } from 'atmosphere.js/react';

function App() {
  return <AtmosphereProvider><Chat /></AtmosphereProvider>;
}

function Chat() {
  const { fullText, isStreaming, send } = useStreaming({
    request: { url: '/atmosphere/ai-chat', transport: 'websocket' },
  });

  return (
    <div>
      <button onClick={() => send('What is Atmosphere?')}>Ask</button>
      <p>{fullText}</p>
      {isStreaming && <span>Generating...</span>}
    </div>
  );
}
```

Vue, Svelte, and React Native bindings also available. See the [atmosphere.js README](atmosphere.js/README.md).

## Samples

**Multi-Agent** — [startup team](samples/spring-boot-multi-agent-startup-team/) (5 agents via A2A)

**AI Streaming** — [ai-chat](samples/spring-boot-ai-chat/) (swap backend via one dependency), [tool calling](samples/spring-boot-ai-tools/), [RAG](samples/spring-boot-rag-chat/), [model routing](samples/spring-boot-spring-ai-routing/), [AI classroom](samples/spring-boot-ai-classroom/)

**Agents** — [dentist agent](samples/spring-boot-dentist-agent/) (commands, tools, Slack, Telegram)

**Protocols** — [MCP server](samples/spring-boot-mcp-server/), [A2A agent](samples/spring-boot-a2a-agent/), [AG-UI chat](samples/spring-boot-agui-chat/)

**Infrastructure** — [channels](samples/spring-boot-channels-chat/), [durable sessions](samples/spring-boot-durable-sessions/), [OpenTelemetry](samples/spring-boot-otel-chat/)

**Chat** — [spring-boot](samples/spring-boot-chat/), [quarkus](samples/quarkus-chat/), [gRPC](samples/grpc-chat/), [embedded-jetty](samples/embedded-jetty-websocket-chat/)

[Browse all 18 samples &rarr;](samples/) · [CLI reference](cli/README.md) · `atmosphere install` for interactive picker

## Requirements

Java 21+ · Spring Boot 4.0+ · Quarkus 3.21+ · Virtual threads enabled by default.

## Documentation

[Tutorial](https://atmosphere.github.io/docs/tutorial/01-introduction/) · [Full docs](https://atmosphere.github.io/docs/) · [CLI](cli/README.md) · [Samples](samples/) · [Javadoc](https://atmosphere.github.io/apidocs/)

## Support

Commercial support and consulting available through [Async-IO.org](https://async-io.org).

## License

Apache 2.0 — @Copyright 2008-2026 [Async-IO.org](https://async-io.org)
