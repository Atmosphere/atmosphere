<p align="center">
  <img src="logo.png" alt="Atmosphere" width="120"/>
</p>

<h1 align="center">Atmosphere</h1>

<p align="center">
  <strong>The transport-agnostic real-time framework for the JVM.</strong><br/>
  Build once with <code>@Agent</code> — stream to Web, Slack, Telegram, MCP, A2A, and any transport.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/org.atmosphere/atmosphere-runtime"><img src="https://img.shields.io/maven-central/v/org.atmosphere/atmosphere-runtime?label=Maven%20Central&color=blue" alt="Maven Central"/></a>
  <a href="https://www.npmjs.com/package/atmosphere.js"><img src="https://img.shields.io/npm/v/atmosphere.js?label=atmosphere.js&color=blue" alt="npm"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml/badge.svg?branch=main" alt="Atmosphere CI"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-js-ci.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-js-ci.yml/badge.svg?branch=main" alt="Atmosphere.js CI"/></a>
</p>

---

Atmosphere was built on one idea: **your application code shouldn't care how the client is connected.** Write once, and the framework delivers to every subscriber — whether they're on a WebSocket, an SSE stream, a long-polling loop, a gRPC channel, or an MCP session. Pluggable AI streaming adapters for Spring AI, LangChain4j, Google ADK, Embabel, and any OpenAI-compatible API.

## `@Agent` — Build AI Agents That Work Everywhere

One annotation. Commands, tools, skill file, and multi-channel delivery — all wired automatically:

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

**Try it now — generate an agent from a skill file:**

```bash
brew install Atmosphere/tap/atmosphere    # or: curl -fsSL https://raw.githubusercontent.com/Atmosphere/atmosphere/main/cli/install.sh | sh
atmosphere new my-agent --skill-file skill.md
cd my-agent && LLM_API_KEY=sk-... ./mvnw spring-boot:run
```

Or run a built-in sample:

```bash
LLM_API_KEY=sk-... atmosphere run spring-boot-dentist-agent
```

Open `http://localhost:8080/atmosphere/console/` and type `/help`, `/firstaid`, or just describe your broken tooth. To connect Slack or Telegram, [create a bot](https://atmosphere.github.io/docs/tutorial/23-channels/) and set the token as an environment variable.

### Multi-Channel — One Agent, Every Platform

When `atmosphere-channels` is on the classpath, `@Command` slash commands are automatically routed to all configured channels. AI responses on external channels go through the full `AiPipeline` (memory, tools, guardrails, RAG, metrics):

| Channel | Activation | Commands | AI |
|---------|-----------|:--------:|:--:|
| Web (WebSocket) | Built-in | `@Command` via `CommandRouter` | `@Prompt` + `@AiTool` + `AiInterceptor` |
| Slack | `SLACK_BOT_TOKEN` | Auto-routed | `AiPipeline` (full chain, no `AiInterceptor`) |
| Telegram | `TELEGRAM_BOT_TOKEN` | Auto-routed | `AiPipeline` (full chain, no `AiInterceptor`) |
| Discord | `DISCORD_BOT_TOKEN` | Auto-routed | `AiPipeline` (full chain, no `AiInterceptor`) |
| WhatsApp | `WHATSAPP_ACCESS_TOKEN` | Auto-routed | `AiPipeline` (full chain, no `AiInterceptor`) |
| Messenger | `MESSENGER_PAGE_TOKEN` | Auto-routed | `AiPipeline` (full chain, no `AiInterceptor`) |

### Skill File — System Prompt + Agent Metadata

The `skillFile` is a markdown file that becomes the system prompt verbatim. Its sections are also parsed for protocol metadata:

```markdown
# DevOps Assistant
You are a DevOps assistant that helps teams monitor services.

## Skills
- Monitor service health and performance
- Manage deployments to staging and production

## Guardrails
- Never execute production deployments without confirmation
```

See the [DevOps skill file](samples/spring-boot-agent-chat/src/main/resources/prompts/devops-skill.md) and [Dentist skill file](samples/spring-boot-dentist-agent/src/main/resources/prompts/dentist-skill.md) for real examples. Full samples: [spring-boot-agent-chat](samples/spring-boot-agent-chat/) (DevOps agent) and [spring-boot-dentist-agent](samples/spring-boot-dentist-agent/) (multi-channel with Slack and Telegram).

### Under the Hood

`@Agent` desugars to `@AiEndpoint` + `CommandRouter` + protocol bridges. For simpler cases without commands or channels, you can use `@AiEndpoint` directly:

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
LLM_API_KEY=your-key atmosphere run spring-boot-ai-console
```

### Client — atmosphere.js

Connect to any Atmosphere endpoint from any framework. Install with `npm install atmosphere.js`.

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

Swap the AI backend by changing one Maven dependency — no tool code changes:

| Backend | Dependency | Bridged via |
|---------|-----------|-------------|
| Built-in (Gemini/OpenAI/Ollama/[Embacle](https://github.com/dravr-ai/dravr-embacle)) | `atmosphere-ai` | direct |
| Spring AI | `atmosphere-spring-ai` | `SpringAiToolBridge` |
| LangChain4j | `atmosphere-langchain4j` | `LangChain4jToolBridge` |
| Google ADK | `atmosphere-adk` | `AdkToolBridge` |
| Embabel | `atmosphere-embabel` | `EmbabelAiSupport` |

See [spring-boot-ai-tools](samples/spring-boot-ai-tools) for the full tool-calling sample and [spring-boot-ai-classroom](samples/spring-boot-ai-classroom) for multi-persona conversation memory.

## Agent Protocols — MCP, A2A, AG-UI

Three protocols for the agentic ecosystem, all riding Atmosphere's transport:

```java
// MCP — expose tools to AI agents (Claude Desktop, Copilot, Cursor)
@McpServer(name = "my-tools", path = "/atmosphere/mcp")
public class MyTools {
    @McpTool(name = "ask_ai", description = "Ask AI and stream the answer")
    public String askAi(@McpParam(name = "question") String q, StreamingSession session) {
        session.stream(q);
        return "streaming";
    }
}

// A2A — agent-to-agent discovery and task delegation (Google/Linux Foundation)
@A2aServer(name = "weather-agent", endpoint = "/atmosphere/a2a")
public class WeatherAgent {
    @A2aSkill(id = "get-weather", name = "Get Weather", description = "Weather for a city")
    @A2aTaskHandler
    public void weather(TaskContext task, @A2aParam(name = "city") String city) {
        task.addArtifact(Artifact.text(weatherService.lookup(city)));
        task.complete("Done");
    }
}

// AG-UI — stream agent state to frontends (CopilotKit compatible)
@AgUiEndpoint(path = "/atmosphere/agui")
public class Assistant {
    @AgUiAction
    public void onRun(RunContext run, StreamingSession session) {
        session.emit(new AiEvent.AgentStep("analyze", "Thinking...", Map.of()));
        session.emit(new AiEvent.TextDelta("Hello! "));
        session.emit(new AiEvent.TextComplete("Hello!"));
    }
}
```

| Protocol | Purpose | Sample |
|----------|---------|--------|
| **MCP** | Agent &#8596; Tools | [spring-boot-mcp-server](samples/spring-boot-mcp-server/) |
| **A2A** | Agent &#8596; Agent | [spring-boot-a2a-agent](samples/spring-boot-a2a-agent/) |
| **AG-UI** | Agent &#8596; Frontend | [spring-boot-agui-chat](samples/spring-boot-agui-chat/) |

## Real-Time Chat (Transport-Agnostic)

The classic Atmosphere pattern — works with WebSocket, SSE, Long-Polling, gRPC, or any transport:

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

## Try It Now

```bash
# Install the Atmosphere CLI
curl -fsSL https://raw.githubusercontent.com/Atmosphere/atmosphere/main/cli/install.sh | sh

# Browse all 20+ samples and pick one to run
atmosphere install

# Run samples directly
atmosphere run spring-boot-dentist-agent                          # multi-channel agent
atmosphere run spring-boot-ai-chat --env LLM_API_KEY=your-key    # AI streaming chat
atmosphere run spring-boot-chat                                   # classic real-time chat

# Scaffold a new project
atmosphere new my-app --template ai-chat
```

Or with npx (zero install):

```bash
npx create-atmosphere-app my-chat-app
npx create-atmosphere-app my-ai-app --template ai-chat
```

See [cli/README.md](cli/README.md) for all commands and options.

## Modules

**Core** — [runtime](https://atmosphere.github.io/docs/reference/core/) (WebSocket, SSE, Long-Polling), [gRPC](https://atmosphere.github.io/docs/reference/grpc/), [rooms](https://atmosphere.github.io/docs/reference/rooms/) · **Agents** — [`@Agent`](modules/agent/) (unified annotation, `@Command`, skill files), [channels](modules/channels/) (Slack, Telegram, Discord, WhatsApp, Messenger) · **AI** — adapters for [Spring AI](https://atmosphere.github.io/docs/integrations/spring-ai/), [LangChain4j](https://atmosphere.github.io/docs/integrations/langchain4j/), [ADK](https://atmosphere.github.io/docs/integrations/adk/), [Embabel](https://atmosphere.github.io/docs/integrations/embabel/), [RAG](modules/rag/README.md) · **Protocols** — [MCP](https://atmosphere.github.io/docs/reference/mcp/), [A2A](samples/spring-boot-a2a-agent/), [AG-UI](samples/spring-boot-agui-chat/) · **Cloud** — [Redis](https://atmosphere.github.io/docs/infrastructure/redis/), [Kafka](https://atmosphere.github.io/docs/infrastructure/kafka/), [durable sessions](https://atmosphere.github.io/docs/reference/durable-sessions/) · **Starters** — [Spring Boot](https://atmosphere.github.io/docs/integrations/spring-boot/), [Quarkus](https://atmosphere.github.io/docs/integrations/quarkus/), [Kotlin](https://atmosphere.github.io/docs/reference/kotlin/) · **Clients** — [atmosphere.js](https://atmosphere.github.io/docs/clients/javascript/) (React, Vue, Svelte, [React Native](https://atmosphere.github.io/docs/clients/react-native/)), [wAsync](https://atmosphere.github.io/docs/clients/java/) (Java)

[Full module reference &rarr;](https://atmosphere.github.io/docs/)

## Requirements

Java 21+ · Spring Boot 4.0+ · Quarkus 3.21+ · JDK 21 virtual threads used by default.

## Documentation

[Tutorial](https://atmosphere.github.io/docs/tutorial/01-introduction/) · [Full docs](https://atmosphere.github.io/docs/) · [CLI](cli/README.md) · [Project generator (JBang)](generator/README.md) · [Samples](samples/) · [Javadoc](https://atmosphere.github.io/apidocs/)

## Support

Need help? Commercial support and consulting available through [Async-IO.org](https://async-io.org).

## License

Apache 2.0 — @Copyright 2008-2026 [Async-IO.org](https://async-io.org)
