# Atmosphere 4.0 Samples

Example applications demonstrating Atmosphere 4.0 across different deployment targets.

### Chat & Messaging

| Sample | Stack | Packaging | Rooms | Metrics | Native Image |
|--------|-------|-----------|-------|---------|-------------|
| [chat](chat/) | Servlet (WAR) | WAR | — | — | — |
| [spring-boot-chat](spring-boot-chat/) | Spring Boot 4.0 | JAR | ✅ | ✅ | ✅ |
| [quarkus-chat](quarkus-chat/) | Quarkus 3.21+ | JAR | — | — | ✅ |
| [embedded-jetty-websocket-chat](embedded-jetty-websocket-chat/) | Embedded Jetty | JAR | — | — | — |
| [grpc-chat](grpc-chat/) | gRPC + Spring Boot | JAR | — | — | — |

### AI / LLM Streaming

| Sample | AI Backend | Tool Calling | Description |
|--------|-----------|-------------|-------------|
| [spring-boot-ai-chat](spring-boot-ai-chat/) | Built-in (Gemini/OpenAI/Ollama) | — | Basic AI streaming with `@AiEndpoint` |
| [spring-boot-langchain4j-chat](spring-boot-langchain4j-chat/) | LangChain4j | — | LangChain4j adapter |
| [spring-boot-spring-ai-chat](spring-boot-spring-ai-chat/) | Spring AI | — | Spring AI adapter |
| [spring-boot-adk-chat](spring-boot-adk-chat/) | Google ADK | — | Google ADK adapter |
| [spring-boot-embabel-chat](spring-boot-embabel-chat/) | Embabel | — | Embabel agent adapter |
| [spring-boot-langchain4j-tools](spring-boot-langchain4j-tools/) | LangChain4j | `@Tool` (native) | LangChain4j-native tool calling |
| [spring-boot-ai-tools](spring-boot-ai-tools/) | LangChain4j | `@AiTool` (portable) | Framework-agnostic tool calling pipeline |
| [spring-boot-adk-tools](spring-boot-adk-tools/) | Google ADK | `@AiTool` (portable) | ADK with Atmosphere tool bridge |
| [spring-boot-spring-ai-routing](spring-boot-spring-ai-routing/) | Spring AI | — | Cost/latency-based model routing |
| [spring-boot-ai-classroom](spring-boot-ai-classroom/) | Built-in | — | Multi-persona AI classroom ([Expo client](spring-boot-ai-classroom/expo-client/)) |
| [spring-boot-embabel-horoscope](spring-boot-embabel-horoscope/) | Embabel | — | Embabel agent orchestration |

### Agents (`@Agent` + `@Command`)

One agent class — commands and AI work on Web, Slack, Telegram, Discord, WhatsApp, and Messenger simultaneously.

| Sample | Features | Channels | Description |
|--------|----------|----------|-------------|
| [spring-boot-agent-chat](spring-boot-agent-chat/) | `@Agent`, `@Command`, `@AiTool`, skill.md | Web (+ any via `atmosphere-channels`) | DevOps assistant with slash commands and AI tools |
| [spring-boot-dentist-agent](spring-boot-dentist-agent/) | `@Agent`, `@Command`, `@AiTool`, skill.md | Web + Slack + Telegram | Multi-channel dental emergency agent |

### Agent Protocols

| Sample | Protocol | Description |
|--------|----------|-------------|
| [spring-boot-mcp-server](spring-boot-mcp-server/) | MCP | Model Context Protocol — expose tools, resources, prompts to AI agents |
| [spring-boot-a2a-agent](spring-boot-a2a-agent/) | A2A | Agent-to-Agent — discoverable skills via Agent Card, JSON-RPC 2.0 |
| [spring-boot-agui-chat](spring-boot-agui-chat/) | AG-UI | Agent-User Interaction — stream agent state to frontends via SSE |

### Infrastructure & Integration

| Sample | Stack | Description |
|--------|-------|-------------|
| [spring-boot-durable-sessions](spring-boot-durable-sessions/) | Spring Boot 4.0 | Persistent sessions with SQLite/Redis |
| [spring-boot-otel-chat](spring-boot-otel-chat/) | Spring Boot 4.0 | OpenTelemetry observability |
| [shared-resources](shared-resources/) | — | Shared frontend assets |

## Quick Start

### Atmosphere CLI (recommended)

The fastest way to try any sample:

```bash
# Install the CLI
curl -fsSL https://raw.githubusercontent.com/Atmosphere/atmosphere/main/cli/install.sh | sh

# Interactive picker — browse samples, pick one, run it
atmosphere install

# Or run a specific sample directly
atmosphere run spring-boot-chat
atmosphere run spring-boot-ai-chat --env LLM_API_KEY=your-key

# List all available samples
atmosphere list
atmosphere list --tag ai
```

Or with npx (zero install):

```bash
npx create-atmosphere-app my-chat-app
npx create-atmosphere-app my-ai-app --template ai-chat
```

See [cli/README.md](../cli/README.md) for full CLI documentation.

### JBang

Scaffold a full project with the [JBang](https://www.jbang.dev) generator:

```bash
jbang https://raw.githubusercontent.com/Atmosphere/atmosphere/main/generator/AtmosphereInit.java \
  --name my-app --template ai-chat
```

See [generator/README.md](../generator/README.md) for all templates and options.

### Manual Build

Each sample can be built independently:

```bash
# WAR sample (Jetty Maven plugin)
cd chat && mvn clean install && mvn jetty:run

# Spring Boot
cd spring-boot-chat && mvn clean package && java -jar target/*.jar

# Quarkus
cd quarkus-chat && mvn clean package && java -jar target/quarkus-app/quarkus-run.jar

# Embedded Jetty
cd embedded-jetty-websocket-chat && mvn clean install && mvn -Pserver
```

Most samples run on **http://localhost:8080**. The AI samples use different ports to allow running them simultaneously: `spring-boot-langchain4j-chat` on 8081, `spring-boot-embabel-chat` on 8082.

## The Same Handler Everywhere

The core `Chat.java` handler is nearly identical across all samples:

```java
@ManagedService(path = "/chat")
public class Chat {

    @Ready
    public void onReady() { }

    @Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) {
        return message;
    }
}
```

Only packaging and configuration differ — your business logic is portable across Spring Boot, Quarkus, and plain Servlet containers.

## Documentation

- [Full Documentation](../docs/README.md)
- [Getting Started with Spring Boot](../docs/spring-boot.md)
- [Getting Started with Quarkus](../docs/quarkus.md)
- [Core Runtime](../docs/core.md)
