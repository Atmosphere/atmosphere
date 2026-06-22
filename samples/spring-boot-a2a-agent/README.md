# Spring Boot A2A Agent Sample

An AI-powered agent that speaks the [A2A (Agent-to-Agent)](https://a2a-protocol.org/) protocol. Other agents discover it via its Agent Card, then delegate tasks using JSON-RPC 2.0 over HTTP. Uses Gemini/OpenAI/Ollama when an API key is configured, with built-in demo fallback.

## What It Does

The `WeatherTimeAgent` exposes three skills via `@Agent` (headless A2A mode):

| Skill | ID | Description |
|-------|----|-------------|
| **Ask Assistant** | `ask` | General-purpose AI Q&A (real LLM when configured) |
| **Get Weather** | `get-weather` | AI-generated weather report for any location |
| **Get Time** | `get-time` | Current date/time in any IANA timezone |

The agent auto-publishes an **Agent Card** describing its capabilities, fetched via the A2A `agent/authenticatedExtendedCard` method. Note: that is the standard A2A method name, but **this sample serves it without authentication** (see [Security](#security) below).

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
| `LLM_API_KEY` / `GEMINI_API_KEY` | — | API key for the chosen provider (OpenAI / Gemini / Anthropic / Cohere / Ollama / etc.) |
| `LLM_MODEL` | `gemini-2.5-flash` | Model name |
| `LLM_BASE_URL` | (auto-detected) | Custom API endpoint |

## Security

⚠️ **This sample ships without authentication for local demonstration.** The
`/atmosphere/a2a` endpoint accepts `message/send` from any caller, which drives
the agent (LLM dispatch + tool execution). On startup you will see a `WARN` that
the A2A endpoint is exposed without auth — that is expected for the demo.

**Do not expose this endpoint beyond localhost without adding authentication.**
In production, front the A2A path with Spring Security (or a gateway) and declare
the scheme so the served Agent Card is honest:

```yaml
atmosphere:
  init-params:
    org.atmosphere.a2a.securityScheme: bearer   # advertised on the Agent Card
    org.atmosphere.a2a.suppressAuthWarning: true # once auth is enforced
```

See the [`atmosphere-a2a` module README](../../modules/a2a/README.md#-security-the-a2a-endpoint-is-unauthenticated-by-default)
for the full security model (governance still runs on the A2A path; task ids are
unguessable capability tokens — neither replaces fronting the endpoint with auth).

## Key Code

| File | Purpose |
|------|---------|
| `WeatherTimeAgent.java` | `@Agent` with three `@AgentSkill` methods using `TaskContext` (headless mode) |
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
