<p align="center">
  <img src="logo.png" alt="Atmosphere" width="120"/>
</p>

<h1 align="center">Atmosphere</h1>

<p align="center">
  <strong>The transport-agnostic real-time framework for the JVM.</strong><br/>
  WebSocket, SSE, Long-Polling, gRPC, MCP, A2A, AG-UI — one API, any transport.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/org.atmosphere/atmosphere-runtime"><img src="https://img.shields.io/maven-central/v/org.atmosphere/atmosphere-runtime?label=Maven%20Central&color=blue" alt="Maven Central"/></a>
  <a href="https://www.npmjs.com/package/atmosphere.js"><img src="https://img.shields.io/npm/v/atmosphere.js?label=atmosphere.js&color=blue" alt="npm"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml/badge.svg?branch=main" alt="Atmosphere CI"/></a>
  <a href="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-js-ci.yml"><img src="https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-js-ci.yml/badge.svg?branch=main" alt="Atmosphere.js CI"/></a>
</p>

---

Atmosphere was built on one idea: **your application code shouldn't care how the client is connected.** Write once, and the framework delivers to every subscriber — whether they're on a WebSocket, an SSE stream, a long-polling loop, a gRPC channel, or an MCP session.

Pluggable AI streaming adapters for Spring AI, LangChain4j, Google ADK, Embabel, and any OpenAI-compatible API.

## Zero-Code AI Chat

**No Java code, no frontend code** — just run:

```bash
LLM_API_KEY=your-key atmosphere run spring-boot-ai-console
```

Open `http://localhost:8080/atmosphere/console/` → working AI chat with streaming, conversation memory, and dark mode. Works with any [OpenAI-compatible API](https://platform.openai.com/docs/api-reference) or any Coding Agent CLI via [Embacle](https://github.com/dravr-ai/dravr-embacle).

Want full control? Add one class:

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

### Client — atmosphere.js

Connect to the same AI endpoint from any framework. Install with `npm install atmosphere.js`.

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

### Real-Time Chat (Transport-Agnostic)

The classic Atmosphere pattern — works with WebSocket, SSE, Long-Polling, gRPC, or any transport:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-runtime</artifactId>
</dependency>
```

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

# Or run a sample directly
atmosphere run spring-boot-chat
atmosphere run spring-boot-ai-chat --env LLM_API_KEY=your-key

# Scaffold a new project
atmosphere new my-app --template ai-chat
```

Or with npx (zero install):

```bash
npx create-atmosphere-app my-chat-app
npx create-atmosphere-app my-ai-app --template ai-chat
```

See [cli/README.md](cli/README.md) for all commands and options.

## What's New in 4.0 ([full list](https://atmosphere.github.io/docs/whats-new/))

Atmosphere applies the same philosophy to AI: **your code shouldn't care which AI framework is on the classpath.** Tools (`@AiTool`), conversation memory, guardrails, multi-backend routing, metrics, and observability are declared once with Atmosphere annotations and automatically bridged to Spring AI, LangChain4j, Google ADK, or Embabel at runtime. Per-endpoint model selection, auto-detected persistence (Redis/SQLite), and broadcast filter auto-registration round out the platform.

```java
@AiEndpoint(path = "/ai/chat",
            systemPrompt = "You are a helpful assistant",
            conversationMemory = true,
            tools = AssistantTools.class)
public class AiChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // auto-detects the AI framework from the classpath
    }
}
```

Tools are declared with `@AiTool` — framework-agnostic, portable across all backends:

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
| Built-in (Gemini/OpenAI/Ollama) | `atmosphere-ai` | direct |
| Spring AI | `atmosphere-spring-ai` | `SpringAiToolBridge` |
| LangChain4j | `atmosphere-langchain4j` | `LangChain4jToolBridge` |
| Google ADK | `atmosphere-adk` | `AdkToolBridge` |
| Embabel | `atmosphere-embabel` | `EmbabelAiSupport` |

See [spring-boot-ai-tools](samples/spring-boot-ai-tools) for the full tool-calling sample, [spring-boot-ai-classroom](samples/spring-boot-ai-classroom) for multi-persona conversation memory, and [expo-client](samples/spring-boot-ai-classroom/expo-client) for React Native/Expo mobile chat. Four official framework samples have been [forked and augmented](https://atmosphere.github.io/docs/whats-new/#samples--forked--augmented) with Atmosphere streaming: [LangChain4j tools](samples/spring-boot-langchain4j-tools), [Spring AI routing](samples/spring-boot-spring-ai-routing), [Embabel horoscope](samples/spring-boot-embabel-horoscope), and [ADK tools](samples/spring-boot-adk-tools).

### CLI-powered LLM backend

Already have a Claude Code, Copilot, Cursor, or Gemini CLI license? [Embacle](https://github.com/dravr-ai/dravr-embacle) turns any CLI tool into an OpenAI-compatible LLM provider — no separate API key required.

```bash
LLM_BASE_URL=http://localhost:3000/v1 LLM_MODEL=copilot:claude-sonnet-4.6 LLM_API_KEY=not-needed \
  ./mvnw spring-boot:run -pl samples/spring-boot-ai-classroom
```

### Agent Protocols — MCP, A2A, AG-UI

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
| **MCP** | Agent ↔ Tools | [spring-boot-mcp-server](samples/spring-boot-mcp-server/) |
| **A2A** | Agent ↔ Agent | [spring-boot-a2a-agent](samples/spring-boot-a2a-agent/) |
| **AG-UI** | Agent ↔ Frontend | [spring-boot-agui-chat](samples/spring-boot-agui-chat/) |

## JBang Support

```bash
jbang generator/AtmosphereInit.java --name my-app --handler ai-chat --ai builtin --tools
cd my-app && ./mvnw spring-boot:run
```

Generates a ready-to-run Spring Boot project with your choice of handler (chat, ai-chat, mcp-server), AI framework, and optional `@AiTool` methods. See [generator/README.md](generator/README.md) for all options.

## Modules

### Core

| Module | Artifact | What it does |
|--------|----------|--------------|
| [**Runtime**](https://atmosphere.github.io/docs/reference/core/) | `atmosphere-runtime` | WebSocket, SSE, Long-Polling (Servlet 6.0+) |
| [**gRPC**](https://atmosphere.github.io/docs/reference/grpc/) | `atmosphere-grpc` | Bidirectional streaming transport (grpc-java 1.71) |
| [**Rooms**](https://atmosphere.github.io/docs/reference/rooms/) | built into runtime | Room management with join/leave and presence |

### AI

| Module | Artifact | What it does |
|--------|----------|--------------|
| [**AI core**](https://atmosphere.github.io/docs/reference/ai/) | `atmosphere-ai` | `AiSupport` SPI, `@AiEndpoint`, filters, routing, conversation memory |
| [**Spring AI**](https://atmosphere.github.io/docs/integrations/spring-ai/) | `atmosphere-spring-ai` | Adapter for Spring AI `ChatClient` |
| [**LangChain4j**](https://atmosphere.github.io/docs/integrations/langchain4j/) | `atmosphere-langchain4j` | Adapter for LangChain4j `StreamingChatLanguageModel` |
| [**Google ADK**](https://atmosphere.github.io/docs/integrations/adk/) | `atmosphere-adk` | Adapter for Google ADK `Runner` |
| [**Embabel**](https://atmosphere.github.io/docs/integrations/embabel/) | `atmosphere-embabel` | Adapter for Embabel `AgentPlatform` |
| [**RAG**](modules/rag/README.md) | `atmosphere-rag` | `ContextProvider` SPI with Spring AI and LangChain4j bridges |
| [**MCP server**](https://atmosphere.github.io/docs/reference/mcp/) | `atmosphere-mcp` | MCP server + bidirectional tool invocation (server-to-client) |
| [**A2A**](samples/spring-boot-a2a-agent/) | `atmosphere-a2a` | Agent-to-Agent protocol — agent discovery, task delegation (JSON-RPC 2.0) |
| [**AG-UI**](samples/spring-boot-agui-chat/) | `atmosphere-agui` | Agent-User Interaction — stream agent state to frontends via SSE |
| [**Protocol Common**](modules/protocol-common/) | `atmosphere-protocol-common` | Shared JSON-RPC 2.0 infrastructure (sessions, tracing, param binding) |

### Cloud

| Module | Artifact | What it does |
|--------|----------|--------------|
| [**Redis**](https://atmosphere.github.io/docs/infrastructure/redis/) | `atmosphere-redis` | Cross-node broadcasting via Redis pub/sub |
| [**Kafka**](https://atmosphere.github.io/docs/infrastructure/kafka/) | `atmosphere-kafka` | Cross-node broadcasting via Kafka |
| [**Durable sessions**](https://atmosphere.github.io/docs/reference/durable-sessions/) | `atmosphere-durable-sessions` | Session persistence across restarts (SQLite / Redis) |

### Extensions

| Module | Artifact | What it does |
|--------|----------|--------------|
| [**Spring Boot**](https://atmosphere.github.io/docs/integrations/spring-boot/) | `atmosphere-spring-boot-starter` | Auto-configuration for Spring Boot 4.0+ |
| [**Quarkus**](https://atmosphere.github.io/docs/integrations/quarkus/) | `atmosphere-quarkus-extension` | Build-time processing for Quarkus 3.21+ |
| [**Kotlin DSL**](https://atmosphere.github.io/docs/reference/kotlin/) | `atmosphere-kotlin` | Builder API and coroutine extensions |
| [**atmosphere.js**](https://atmosphere.github.io/docs/clients/javascript/) | `atmosphere.js` (npm) | Browser & React Native client with React, Vue, Svelte, and [RN hooks](https://atmosphere.github.io/docs/clients/react-native/) |
| [**wAsync**](https://atmosphere.github.io/docs/clients/java/) | `atmosphere-wasync` | Async Java client — WebSocket, SSE, long-polling, gRPC |

## Requirements

| Java | Spring Boot | Quarkus |
|------|-------------|---------|
| 21+  | 4.0.2+      | 3.21+   |

JDK 21 virtual threads are used by default.

## Documentation

- [**Tutorial**](https://atmosphere.github.io/docs/tutorial/01-introduction/) — step-by-step guide from first app to AI streaming, MCP, gRPC, and production deployment
- [**Full documentation**](https://atmosphere.github.io/docs/) — architecture, configuration, and API reference for every module
- [**CLI**](cli/README.md) — install, run samples, scaffold projects from your terminal
- [**Project generator**](generator/README.md) — generate a ready-to-run project with JBang
- [**Samples**](samples/) — runnable apps covering every transport and integration
- [**Javadoc**](https://atmosphere.github.io/apidocs/)

## Commercial Support

Available via [Async-IO.org](https://async-io.org)

## License

Apache 2.0 — @Copyright 2008-2026 [Async-IO.org](https://async-io.org)
