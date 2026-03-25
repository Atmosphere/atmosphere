<p align="center">
  <img src="logo.png" alt="Atmosphere" width="120"/>
</p>

<h1 align="center">Atmosphere</h1>

<p align="center">
  <strong>The real-time infrastructure layer for Java AI agents.</strong><br/>
  Pick any LLM library. Build once with <code>@Agent</code> — stream to Web, Slack, Telegram, MCP, A2A, and any transport.<br/>
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

## Try It in 30 Seconds

```bash
brew install Atmosphere/tap/atmosphere    # or: curl -fsSL https://raw.githubusercontent.com/Atmosphere/atmosphere/main/cli/install.sh | sh
atmosphere run spring-boot-dentist-agent  # demo mode — no API key needed
```

Open `http://localhost:8080` — type `/help`, `/firstaid`, or describe your dental emergency. To run with a real LLM:

```bash
LLM_API_KEY=your-key atmosphere run spring-boot-dentist-agent
```

Browse all 18 samples, scaffold a project, or run any sample directly:

```bash
atmosphere install                               # interactive picker (fzf-powered)
atmosphere install --tag ai                      # filter to AI samples
atmosphere new my-agent --skill-file skill.md    # scaffold from a skill file
npx create-atmosphere-app my-app --template ai-chat   # zero install
```

See [cli/README.md](cli/README.md) for the full CLI reference.

## Multi-Agent Startup Team

Five independent AI agents collaborate via the A2A protocol over JSON-RPC to deliver instant startup advisory briefings.

Ask a business question. Four specialist agents — **Research** (web scraping via JSoup), **Strategy** (market analysis), **Finance** (TAM/SAM/SOM projections), and **Writer** (executive briefing) — each run as a headless `@Agent` with `@AgentSkill` methods. A **CEO agent** discovers them via Agent Cards, delegates via A2A, and synthesizes findings into a GO/NO-GO recommendation streamed live over WebSocket.

```bash
GEMINI_API_KEY=your-key atmosphere run spring-boot-multi-agent-startup-team
```

What makes this different from CrewAI, AutoGen, or LangGraph:

- **Real-time visibility** — users watch each agent work via WebSocket, not batch output
- **True A2A protocol** — agents discover each other via Agent Cards and communicate over JSON-RPC, not function calls
- **Unified `@Agent`** — one annotation for both the full-stack CEO (WebSocket UI) and headless specialists (A2A only)
- **Production architecture** — Spring Boot 4.0, MCP auto-registered, conversation memory
- **Demo mode** — works without any API key

See the [full source](samples/spring-boot-multi-agent-startup-team/).

## `@Agent` — One Annotation, Everything Wired

```java
@Agent(name = "devops", skillFile = "prompts/devops-skill.md",
       description = "DevOps assistant with monitoring and deployment")
public class DevOpsAgent {

    @Command(value = "/status", description = "Show service health")
    public String status() { return "All services healthy"; }

    @Command(value = "/deploy", description = "Deploy to staging",
             confirm = "Deploy to staging environment?")
    public String deploy(String args) { return "Deployed " + args; }

    @AiTool(name = "check_service", description = "Check service health")
    public String checkService(@Param("service") String service) { ... }

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

Slash commands execute instantly (no LLM cost). Natural language goes through the full AI pipeline — memory, tools, guardrails, RAG, metrics — on every transport.

**What `@Agent` wires for you:**

- **AI endpoint** at `/atmosphere/agent/{name}` — no servlet config, no router
- **Slash commands** — `@Command` methods execute instantly, auto-generate `/help`
- **AI tools** — `@AiTool` methods callable by the LLM, portable across all backends
- **Skill file** — Markdown system prompt whose `## Skills`, `## Tools`, `## Channels`, `## Guardrails` sections are also parsed for protocol metadata ([reference](docs/skill-files.md))
- **Multi-channel** — same commands and AI pipeline on Web, Slack, Telegram, Discord, WhatsApp, Messenger
- **Protocol exposure** — MCP, A2A, AG-UI auto-registered based on classpath
- **Conversation memory** — multi-turn by default
- **Headless mode** — auto-detected when there's no `@Prompt` (A2A/MCP only, no WebSocket UI)

### Full-Stack vs. Headless

All agents use `@Agent`. The framework detects the mode automatically:

```java
// Full-stack: has @Prompt → WebSocket UI + all protocols
@Agent(name = "ceo", skillFile = "ceo.md")
public class CeoAgent {
    @Prompt
    public void onMessage(String msg, StreamingSession s) { s.stream(msg); }
}

// Headless: has @AgentSkill, no @Prompt → A2A/MCP only
@Agent(name = "research", endpoint = "/atmosphere/a2a/research",
       description = "Web research agent")
public class ResearchAgent {
    @AgentSkill(id = "web_search", name = "Search", description = "Search the web")
    @AgentSkillHandler
    public void search(TaskContext task, @AgentSkillParam(name = "query") String query) {
        task.addArtifact(Artifact.text(doSearch(query)));
        task.complete("Done");
    }
}
```

### Under the Hood

`@Agent` desugars to `@AiEndpoint` + `CommandRouter` + protocol bridges. For simpler cases without commands or channels, use `@AiEndpoint` directly:

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

Or skip Java entirely — **zero-code AI chat:**

```bash
LLM_API_KEY=your-key atmosphere run spring-boot-ai-chat
```

## Pick Your LLM Library

Atmosphere is not an LLM library — it's the infrastructure layer underneath them. Your LLM library calls the model. Atmosphere delivers the response.

| Backend | Dependency | Bridged via |
|---------|-----------|-------------|
| Built-in (Gemini/OpenAI/Ollama/[Embacle](https://github.com/dravr-ai/dravr-embacle)) | `atmosphere-ai` | direct |
| Spring AI | `atmosphere-spring-ai` | `SpringAiToolBridge` |
| LangChain4j | `atmosphere-langchain4j` | `LangChain4jToolBridge` |
| Google ADK | `atmosphere-adk` | `AdkToolBridge` |
| Embabel | `atmosphere-embabel` | `EmbabelAiSupport` |

Swap the backend by changing one Maven dependency. Your `@Agent`, `@AiTool`, and `@Command` code stays the same.

See [modules/ai/README.md](modules/ai/README.md) for the adapter table and SPI details.

## AI Tools — Framework-Agnostic

Tools are declared with `@AiTool` — portable across all backends:

```java
public class AssistantTools {

    @AiTool(name = "get_weather", description = "Get weather for a city")
    public String getWeather(@Param("city") String city) {
        return weatherService.lookup(city);
    }
}
```

Write the tool once. It works with Spring AI, LangChain4j, ADK, Embabel, and the built-in client. See [spring-boot-ai-tools](samples/spring-boot-ai-tools/) for the full sample and [spring-boot-ai-classroom](samples/spring-boot-ai-classroom/) for multi-persona conversation memory.

## Agent Protocols — MCP, A2A, AG-UI

Protocol exposure is **automatic based on classpath** — add the module, and your `@Agent` is discoverable. No protocol-specific annotations needed on the agent class itself.

| Protocol | Direction | What It Does | Sample |
|----------|-----------|--------------|--------|
| **MCP** | Agent &#8596; Tools | Expose tools, resources, and prompts to Claude Desktop, Copilot, Cursor | [spring-boot-mcp-server](samples/spring-boot-mcp-server/) |
| **A2A** | Agent &#8596; Agent | Agent discovery via Agent Cards, task delegation over JSON-RPC 2.0 | [spring-boot-a2a-agent](samples/spring-boot-a2a-agent/) |
| **AG-UI** | Agent &#8596; Frontend | Stream agent state (steps, tool calls, text deltas) to UIs via SSE | [spring-boot-agui-chat](samples/spring-boot-agui-chat/) |

MCP tools are declared with `@McpTool`, `@McpResource`, `@McpPrompt`. A2A skills use `@AgentSkill` + `@AgentSkillHandler`. See [docs/protocols.md](docs/protocols.md) for code examples.

### Multi-Channel — One Agent, Every Platform

Add `atmosphere-channels` to the classpath and set a bot token — `@Command` methods and AI responses automatically route to **Slack**, **Telegram**, **Discord**, **WhatsApp**, and **Messenger**. Same commands, same AI pipeline, every channel.

See [docs/channels.md](docs/channels.md) and the [channels sample](samples/spring-boot-channels-chat/).

### Skill File — System Prompt + Agent Metadata

The `skillFile` is a Markdown document that serves as both **system prompt** (sent to the LLM verbatim) and **protocol metadata** (parsed for A2A Agent Card, tool cross-referencing):

```markdown
# DevOps Assistant
You are a DevOps assistant that helps teams monitor services.

## Skills
- Monitor service health and performance
- Manage deployments to staging and production

## Guardrails
- Never execute production deployments without confirmation
```

See the [Dentist skill file](samples/spring-boot-dentist-agent/src/main/resources/prompts/dentist-skill.md) and [CEO skill file](samples/spring-boot-multi-agent-startup-team/src/main/resources/prompts/ceo-skill.md) for real examples. Full reference: [docs/skill-files.md](docs/skill-files.md).

## Real-Time Transport

The battle-tested foundation that powers everything above. WebSocket, SSE, long-polling — your code never changes:

```java
@ManagedService(path = "/chat")
public class Chat {

    @Ready
    public void onReady(AtmosphereResource r) {
        log.info("{} connected via {}", r.uuid(), r.transport());
    }

    @Message(encoders = JacksonEncoder.class, decoders = JacksonDecoder.class)
    public ChatMessage onMessage(ChatMessage message) {
        return message; // broadcast to all subscribers
    }
}
```

Atmosphere picks the best transport, handles fallback, reconnection with exponential backoff, heartbeats, and message caching. This is the same infrastructure that has been running in production since 2008 — trading floors, healthcare systems, collaboration tools — now powering AI agent streaming.

## Client — atmosphere.js

```bash
npm install atmosphere.js
```

```tsx
// React
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

<details>
<summary><b>Vue</b></summary>

```vue
<script setup lang="ts">
import { useStreaming } from 'atmosphere.js/vue';

const { fullText, isStreaming, send } = useStreaming({
  url: '/atmosphere/ai-chat',
  transport: 'websocket',
});
</script>

<template>
  <button @click="send('What is Atmosphere?')">Ask</button>
  <p>{{ fullText }}</p>
  <span v-if="isStreaming">Generating...</span>
</template>
```
</details>

<details>
<summary><b>Svelte</b></summary>

```svelte
<script>
  import { createStreamingStore } from 'atmosphere.js/svelte';

  const { store, send } = createStreamingStore({
    url: '/atmosphere/ai-chat',
    transport: 'websocket',
  });
</script>

<button on:click={() => send('What is Atmosphere?')}>Ask</button>
<p>{$store.fullText}</p>
{#if $store.isStreaming}<span>Generating...</span>{/if}
```
</details>

<details>
<summary><b>React Native</b></summary>

```tsx
import { setupReactNative, AtmosphereProvider } from 'atmosphere.js/react-native';
import { useStreamingRN } from 'atmosphere.js/react-native';

setupReactNative();

function App() {
  return <AtmosphereProvider><Chat /></AtmosphereProvider>;
}

function Chat() {
  const { fullText, isStreaming, isConnected, send } = useStreamingRN({
    request: { url: 'https://your-server.com/atmosphere/ai-chat', transport: 'websocket' },
  });

  return (
    <View>
      <Button title="Ask" onPress={() => send('What is Atmosphere?')} />
      <Text>{fullText}</Text>
      {isStreaming && <Text>Generating...</Text>}
    </View>
  );
}
```
</details>

Auto-connects on mount, streams tokens as they arrive, cleans up on unmount. See the [atmosphere.js README](atmosphere.js/README.md) for the full API.

## Since 2008

Atmosphere has been in continuous development since 2008 and continuous production use since the Servlet 3.0 era.

- **2008** — Project founded. Async I/O and Comet (long-polling) on Servlet containers
- **2010** — WebSocket support (Grizzly, Jetty). Open-sourced on GitHub
- **2012** — Version 1.0. Annotation-driven API (`@ManagedService`, `@Message`)
- **2013** — Version 2.0. Universal transport abstraction — write once, run on WebSocket/SSE/long-polling
- **2022** — Version 3.0. Jakarta EE migration, modern Servlet containers
- **2026** — Version 4.0. `@Agent`, AI streaming, tool calling, MCP/A2A/AG-UI, multi-channel delivery, CLI, Multi-Agent Teams

**246 releases** on Maven Central · **3,700+ GitHub stars** · Atmosphere 3.x maintained on the [`atmosphere-3.0.x`](https://github.com/Atmosphere/atmosphere/tree/atmosphere-3.0.x) branch.

## Samples

**Multi-Agent** — [startup team](samples/spring-boot-multi-agent-startup-team/) (5 agents via A2A, real-time WebSocket visualization)

**Agents** — [dentist agent](samples/spring-boot-dentist-agent/) (multi-channel with Slack and Telegram)

**AI / LLM Streaming** — [ai-chat](samples/spring-boot-ai-chat/) (swap backend via one dependency), [tool calling](samples/spring-boot-ai-tools/), [RAG](samples/spring-boot-rag-chat/), [model routing](samples/spring-boot-spring-ai-routing/), [AI classroom](samples/spring-boot-ai-classroom/)

**Protocols** — [MCP server](samples/spring-boot-mcp-server/), [A2A agent](samples/spring-boot-a2a-agent/), [AG-UI chat](samples/spring-boot-agui-chat/)

**Infrastructure** — [channels](samples/spring-boot-channels-chat/), [durable sessions](samples/spring-boot-durable-sessions/), [OpenTelemetry](samples/spring-boot-otel-chat/)

**Chat & Messaging** — [spring-boot-chat](samples/spring-boot-chat/), [quarkus-chat](samples/quarkus-chat/), [grpc-chat](samples/grpc-chat/), [embedded-jetty](samples/embedded-jetty-websocket-chat/)

[Browse all 18 samples &rarr;](samples/)

## Modules

25+ modules: core transports, agents, AI adapters, protocol bridges, cloud infrastructure, framework starters, and clients. [Full module reference &rarr;](https://atmosphere.github.io/docs/)

## Requirements

Java 21+ · Spring Boot 4.0+ · Quarkus 3.21+ · Virtual threads enabled by default.

## Documentation

[Tutorial](https://atmosphere.github.io/docs/tutorial/01-introduction/) · [Full docs](https://atmosphere.github.io/docs/) · [CLI](cli/README.md) · [Project generator (JBang)](generator/README.md) · [Samples](samples/) · [Javadoc](https://atmosphere.github.io/apidocs/)

## Support

Need help? Commercial support and consulting available through [Async-IO.org](https://async-io.org).

## License

Apache 2.0 — @Copyright 2008-2026 [Async-IO.org](https://async-io.org)
