# Atmosphere Project Generator

Generate a ready-to-run Atmosphere + Spring Boot project with a single command.

## Prerequisites

- [JBang](https://www.jbang.dev/download/) installed (`sdk install jbang` or `brew install jbang`)
- JDK 21+

## Usage

### Interactive mode

```bash
jbang generator/AtmosphereInit.java
```

Prompts for project name, handler type, and AI framework (if applicable).

### Non-interactive mode

```bash
# Real-time chat
jbang generator/AtmosphereInit.java \
  --name my-chat-app \
  --handler chat \
  --output ./my-chat-app

# AI streaming chat (built-in OpenAI-compatible client)
jbang generator/AtmosphereInit.java \
  --name my-ai-app \
  --handler ai-chat \
  --ai builtin \
  --output ./my-ai-app

# AI chat with Spring AI
jbang generator/AtmosphereInit.java \
  --name my-spring-ai-app \
  --handler ai-chat \
  --ai spring-ai \
  --output ./my-spring-ai-app

# AI chat with LangChain4j
jbang generator/AtmosphereInit.java \
  --name my-langchain4j-app \
  --handler ai-chat \
  --ai langchain4j \
  --output ./my-langchain4j-app

# AI chat with Google ADK
jbang generator/AtmosphereInit.java \
  --name my-adk-app \
  --handler ai-chat \
  --ai adk \
  --output ./my-adk-app

# MCP server with chat
jbang generator/AtmosphereInit.java \
  --name my-mcp-server \
  --handler mcp-server \
  --output ./my-mcp-server
```

## Options

| Flag | Description | Default |
|------|-------------|---------|
| `-n, --name` | Project name | _(prompted)_ |
| `-g, --group` | Maven group ID | `com.example` |
| `--handler` | Handler type: `chat`, `ai-chat`, `mcp-server` | _(prompted)_ |
| `--ai` | AI framework: `builtin`, `spring-ai`, `langchain4j`, `adk`, `embabel` | _(prompted if ai-chat)_ |
| `-o, --output` | Output directory | `./{name}` |

## Handler types

### chat

A real-time chat application using `@ManagedService` with Jackson-based message encoding, heartbeat tracking, and disconnect handling. Includes a pre-built browser UI.

### ai-chat

An AI streaming endpoint using `@AiEndpoint` with `@Prompt` handling. Streams LLM responses token-by-token to connected browsers. Ships with a demo fallback that works without an API key.

Available AI frameworks:
- **builtin** — OpenAI-compatible HTTP client (works with Gemini, Ollama, OpenAI)
- **spring-ai** — Spring AI `ChatClient` integration
- **langchain4j** — LangChain4j `StreamingChatLanguageModel`
- **adk** — Google ADK `Runner` with event streaming
- **embabel** — Embabel `AgentPlatform`

### mcp-server

An MCP (Model Context Protocol) server exposing tools, resources, and prompts to AI agents. Includes a chat handler for browser-based interaction and MCP tools for chat administration.

## Generated project structure

```
my-app/
├── pom.xml
├── mvnw, mvnw.cmd, .mvn/wrapper/
└── src/main/
    ├── java/com/example/myapp/
    │   ├── Application.java
    │   └── ... (handler-specific files)
    └── resources/
        ├── application.yml
        └── static/
            └── index.html
```

## Running the generated project

```bash
cd my-app
./mvnw spring-boot:run
```

Then open http://localhost:8080 in your browser.

For AI chat, set the appropriate API key:
```bash
# Built-in / LangChain4j
LLM_API_KEY=your-key ./mvnw spring-boot:run

# Spring AI / Embabel
OPENAI_API_KEY=your-key ./mvnw spring-boot:run

# ADK
GOOGLE_API_KEY=your-key ./mvnw spring-boot:run
```
