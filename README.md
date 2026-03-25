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

## Try It

```bash
brew install Atmosphere/tap/atmosphere    # or: curl -fsSL https://raw.githubusercontent.com/Atmosphere/atmosphere/main/cli/install.sh | sh

# Run a built-in sample
atmosphere run spring-boot-multi-agent-startup-team

# Import any skill from GitHub and run it
atmosphere import https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md
cd frontend-design && LLM_API_KEY=your-key ./mvnw spring-boot:run
```

Open `http://localhost:8080/atmosphere/console/` — the console auto-connects to your agent. Import skills from [Anthropic](https://github.com/anthropics/skills), [Antigravity](https://github.com/sickn33/antigravity-awesome-skills) (1,200+ skills), or any GitHub URL.

## Multi-Agent Startup Team

Five independent `@Agent` classes collaborate via A2A to deliver startup advisory briefings. A CEO agent discovers four headless specialists (Research, Strategy, Finance, Writer) via Agent Cards, delegates over JSON-RPC, and synthesizes a GO/NO-GO recommendation — streamed live over WebSocket.

<details>
<summary><b>Show the code</b></summary>

```java
// Full-stack agent: WebSocket UI + all protocols
@Agent(name = "startup-ceo", skillFile = "prompts/ceo-skill.md",
       description = "CEO agent — orchestrates the team")
public class CeoAgent {

    @AiTool(name = "delegate_research", description = "Delegate to research agent")
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

</details>

See the [full source](samples/spring-boot-multi-agent-startup-team/).

## Transports & Protocols

Your code doesn't change. Atmosphere delivers to every subscriber — regardless of how they're connected.

| Layer | What | How |
|-------|------|-----|
| **Transports** | WebSocket, SSE, Long-Polling, gRPC | Auto-negotiated with fallback. Reconnection, heartbeats, message caching. |
| **Agent Protocols** | MCP, A2A, AG-UI | Auto-registered based on classpath. Your `@Agent` is discoverable by any MCP client, any A2A agent, and any AG-UI frontend. |
| **Channels** | Slack, Telegram, Discord, WhatsApp, Messenger | Set a bot token — interact with your agent from any messaging platform. |

| Protocol | Direction | What It Does | Sample |
|----------|-----------|--------------|--------|
| **MCP** | Agent &#8596; Tools | Expose tools to Claude Desktop, Copilot, Cursor | [mcp-server](samples/spring-boot-mcp-server/) |
| **A2A** | Agent &#8596; Agent | Agent discovery via Agent Cards, task delegation over JSON-RPC | [a2a-agent](samples/spring-boot-a2a-agent/) |
| **AG-UI** | Agent &#8596; Frontend | Stream agent state (steps, tool calls, text deltas) via SSE | [agui-chat](samples/spring-boot-agui-chat/) |
| **WebSocket** | Agent &#8596; Browser | Full-duplex streaming with auto-reconnection | [ai-chat](samples/spring-boot-ai-chat/) |
| **SSE** | Agent &#8594; Browser | Server-sent events fallback | Built-in |
| **Long-Polling** | Agent &#8596; Browser | Universal fallback for restrictive networks | Built-in |
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

## Add Protocols to Existing Apps

Already have an Atmosphere 3.x `@ManagedService`? Add `atmosphere-mcp` or `atmosphere-a2a` to the classpath and annotate methods directly on the same class — no separate `@Agent` needed:

```java
@ManagedService(path = "/chat")
public class Chat {

    @Inject @Named("/chat")
    private Broadcaster broadcaster;

    @Ready
    public void onReady(AtmosphereResource r) {
        log.info("{} connected via {}", r.uuid(), r.transport());
    }

    @Message(encoders = JacksonEncoder.class, decoders = JacksonDecoder.class)
    public ChatMessage onMessage(ChatMessage message) {
        return message;
    }

    // Add atmosphere-mcp to classpath → MCP endpoint at /chat/mcp
    @McpTool(name = "list_users", description = "List connected chat users")
    public List<String> listUsers() {
        return broadcaster.getAtmosphereResources()
                .stream().map(AtmosphereResource::uuid).toList();
    }

    // Add atmosphere-a2a to classpath → A2A endpoint at /chat/a2a
    @AgentSkill(id = "broadcast", name = "Broadcast", description = "Send to all users")
    @AgentSkillHandler
    public void broadcast(TaskContext task, @AgentSkillParam(name = "message") String message) {
        broadcaster.broadcast(message);
        task.complete("Sent to " + broadcaster.getAtmosphereResources().size() + " users");
    }
}
```

Same class, three entry points: browsers via WebSocket at `/chat`, MCP clients at `/chat/mcp`, A2A agents at `/chat/a2a`. Protocol endpoints are auto-registered when the module is on the classpath.

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

| Category | Sample | Description |
|----------|--------|-------------|
| Multi-Agent | [startup team](samples/spring-boot-multi-agent-startup-team/) | 5 agents collaborate via A2A with real-time WebSocket visualization |
| Agent | [dentist agent](samples/spring-boot-dentist-agent/) | Commands, tools, skill file, Slack and Telegram |
| AI Streaming | [ai-chat](samples/spring-boot-ai-chat/) | Swap backend via one dependency (Gemini, OpenAI, Spring AI, LangChain4j, ADK, Embabel) |
| AI Streaming | [ai-tools](samples/spring-boot-ai-tools/) | Framework-agnostic tool calling with real-time events |
| AI Streaming | [rag-chat](samples/spring-boot-rag-chat/) | RAG with document retrieval and embeddings |
| AI Streaming | [ai-routing](samples/spring-boot-spring-ai-routing/) | Content-based model routing |
| AI Streaming | [ai-classroom](samples/spring-boot-ai-classroom/) | Multi-room, multi-persona streaming |
| Protocol | [mcp-server](samples/spring-boot-mcp-server/) | MCP tools, resources, and prompts |
| Protocol | [a2a-agent](samples/spring-boot-a2a-agent/) | Headless A2A agent with Agent Card discovery |
| Protocol | [agui-chat](samples/spring-boot-agui-chat/) | AG-UI streaming to frontends via SSE |
| Infrastructure | [channels](samples/spring-boot-channels-chat/) | Slack, Telegram, Discord, WhatsApp, Messenger |
| Infrastructure | [durable-sessions](samples/spring-boot-durable-sessions/) | Survive server restarts with SQLite |
| Infrastructure | [otel-chat](samples/spring-boot-otel-chat/) | OpenTelemetry tracing |
| Chat | [spring-boot-chat](samples/spring-boot-chat/) | WebSocket chat with Spring Boot |
| Chat | [quarkus-chat](samples/quarkus-chat/) | WebSocket chat with Quarkus |
| Chat | [grpc-chat](samples/grpc-chat/) | Chat over gRPC transport |
| Chat | [embedded-jetty](samples/embedded-jetty-websocket-chat/) | Embedded Jetty, no framework |

`atmosphere install` for interactive picker · [CLI reference](cli/README.md)

## Skills Ecosystem

Import any skill file from GitHub and get a running agent — WebSocket, MCP, A2A, and console UI auto-wired:

```bash
# From Anthropic's official skills (17 skills)
atmosphere import https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md

# From Antigravity community collection (1,200+ skills)
atmosphere import https://github.com/sickn33/antigravity-awesome-skills/blob/main/skills/customer-support/SKILL.md

# From our curated registry
atmosphere skills run dentist-agent

# Browse and search
atmosphere skills list
atmosphere skills search medical
```

Skills use the [Agent Skills](https://agentskills.io/specification) format — YAML frontmatter + Markdown body. Atmosphere auto-discovers skill files at `META-INF/skills/{name}/SKILL.md` on the classpath, so skills can also be distributed as Maven JARs.

| Trusted Source | Skills | Link |
|---------------|-------:|------|
| Atmosphere | 6 | [atmosphere-skills](https://github.com/Atmosphere/atmosphere-skills) |
| Anthropic | 17 | [anthropics/skills](https://github.com/anthropics/skills) |
| Antigravity | 1,200+ | [antigravity-awesome-skills](https://github.com/sickn33/antigravity-awesome-skills) |
| K-Dense AI | 200+ | [claude-scientific-skills](https://github.com/K-Dense-AI/claude-scientific-skills) |

Untrusted sources require `--trust` flag. See [cli/README.md](cli/README.md) for the full CLI reference.

## Requirements

Java 21+ · Spring Boot 4.0+ · Quarkus 3.21+ · Virtual threads enabled by default.

## Documentation

[Tutorial](https://atmosphere.github.io/docs/tutorial/01-introduction/) · [Full docs](https://atmosphere.github.io/docs/) · [CLI](cli/README.md) · [Samples](samples/) · [Javadoc](https://atmosphere.github.io/apidocs/)

## Support

Commercial support and consulting available through [Async-IO.org](https://async-io.org).

## License

Apache 2.0 — @Copyright 2008-2026 [Async-IO.org](https://async-io.org)
