# Atmosphere + LangChain4j Tools

Real-time AI assistant with **tool calling**, **PII redaction**, and **cost metering** — powered by
[LangChain4j](https://docs.langchain4j.dev/) and Atmosphere's streaming pipeline.

Ported from the official [langchain4j-examples/spring-boot-example](https://github.com/langchain4j/langchain4j-examples/tree/main/spring-boot-example),
augmented with Atmosphere AI features.

## What It Does

The assistant has three tools registered via LangChain4j's `@Tool` annotation:

| Tool | Description |
|------|-------------|
| `currentTime` | Returns the current date and time |
| `cityTime` | Returns the time in a specific city (New York, London, Paris, Tokyo, Sydney) |
| `weather` | Returns a simulated weather report for a city |

All responses pass through Atmosphere's broadcast filter pipeline:

- **PII Redaction** — emails, phone numbers, and SSNs are automatically replaced with `[REDACTED]`
- **Cost Metering** — streaming text counts per session are tracked and logged

## Running

```bash
# Demo mode (no API key needed — uses simulated responses)
cd samples/spring-boot-langchain4j-tools
../../mvnw spring-boot:run

# With a real LLM
LLM_API_KEY=your-api-key ../../mvnw spring-boot:run
```

Open http://localhost:8086 in your browser.

## Try These Prompts

- `What time is it in Tokyo?` — triggers the `cityTime` tool
- `What's the weather in Paris?` — triggers the `weather` tool  
- `My email is john@example.com` — see PII redaction in action
- `What tools do you have?` — lists available tools

## Key Code

| File | Purpose |
|------|---------|
| `LangChain4jToolsChat.java` | `@AiEndpoint` with `@Prompt` handler |
| `AssistantTools.java` | `@Tool`-annotated methods discovered by LangChain4j |
| `LlmConfig.java` | Configures LLM client + PII/cost filters as Spring beans |
| `DemoResponseProducer.java` | Fallback when no API key is set |

## Architecture

```
Browser ──WebSocket──▶ Atmosphere ──▶ LangChain4j AI Service
                           │              │
                           │         ┌────┴────┐
                           │         │ @Tool   │
                           │         │ methods │
                           │         └─────────┘
                           │
                    ┌──────┴──────┐
                    │ PII Filter  │
                    │ Cost Filter │
                    └──────┬──────┘
                           │
                     ◀─────┘ streamed texts
```
