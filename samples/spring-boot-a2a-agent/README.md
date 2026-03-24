# Spring Boot A2A Agent Sample

An AI-powered agent that speaks the [A2A (Agent-to-Agent)](https://google.github.io/A2A/) protocol. Other agents discover it via its Agent Card, then delegate tasks using JSON-RPC 2.0 over HTTP. Uses Gemini/OpenAI/Ollama when an API key is configured, with built-in demo fallback.

## What It Does

The `WeatherTimeAgent` exposes three skills via `@Agent` (headless A2A mode):

| Skill | ID | Description |
|-------|----|-------------|
| **Ask Assistant** | `ask` | General-purpose AI Q&A (real LLM when configured) |
| **Get Weather** | `get-weather` | AI-generated weather report for any location |
| **Get Time** | `get-time` | Current date/time in any IANA timezone |

The agent auto-publishes an **Agent Card** describing its capabilities, discoverable via `agent/authenticatedExtendedCard`.

## Running

```bash
# With real AI (recommended)
GEMINI_API_KEY=your-key ./mvnw spring-boot:run -pl samples/spring-boot-a2a-agent

# Demo mode (no API key needed)
./mvnw spring-boot:run -pl samples/spring-boot-a2a-agent
```

Open **http://localhost:8084** for the built-in demo UI, or use curl:

```bash
# Discover the agent
curl -X POST http://localhost:8084/atmosphere/a2a \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"agent/authenticatedExtendedCard"}'

# Ask a question
curl -X POST http://localhost:8084/atmosphere/a2a \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"message/send","params":{
    "message":{"role":"user","parts":[{"type":"text","text":"hello"}],"messageId":"m1","metadata":{"skillId":"ask"}},
    "arguments":{"message":"What is the Atmosphere Framework?"}
  }}'

# Get weather
curl -X POST http://localhost:8084/atmosphere/a2a \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"message/send","params":{
    "message":{"role":"user","parts":[{"type":"text","text":"weather"}],"messageId":"m2","metadata":{"skillId":"get-weather"}},
    "arguments":{"location":"Montreal"}
  }}'
```

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `LLM_API_KEY` / `GEMINI_API_KEY` | — | API key for Gemini, OpenAI, or Ollama |
| `LLM_MODEL` | `gemini-2.5-flash` | Model name |
| `LLM_BASE_URL` | (auto-detected) | Custom API endpoint |

## Key Code

| File | Purpose |
|------|---------|
| `WeatherTimeAgent.java` | `@Agent` with three `@A2aSkill` methods using `TaskContext` (headless mode) |
| `LlmConfig.java` | Bridges Spring properties to `AiConfig` |
| `A2aAgentApplication.java` | Spring Boot entry point |

## Architecture

```
A2A Client (other agent, curl, browser)
    │
    │  POST /atmosphere/a2a  (JSON-RPC 2.0)
    │  {"method":"message/send", "params":{...}}
    ▼
┌─────────────────────────────────┐
│  Atmosphere A2A Handler         │
│  ├─ Agent Card discovery        │
│  ├─ Task lifecycle management   │
│  └─ Skill routing & execution   │
├─────────────────────────────────┤
│  WeatherTimeAgent               │
│  ├─ ask    → Gemini/demo        │
│  ├─ weather → Gemini/demo       │
│  └─ time   → ZonedDateTime     │
└─────────────────────────────────┘
```

## See Also

- [spring-boot-mcp-server](../spring-boot-mcp-server/) — MCP protocol (agent ↔ tools)
- [spring-boot-agui-chat](../spring-boot-agui-chat/) — AG-UI protocol (agent ↔ frontend)
