# Atmosphere + Google ADK Tools

Real-time AI assistant with **tool calling**, **streaming text budgets**, and **response caching** — powered by
[Google ADK](https://github.com/google/adk-java) and Atmosphere's streaming pipeline.

Ported from the official [adk-java/tutorials/city-time-weather](https://github.com/google/adk-java/tree/main/tutorials/city-time-weather),
augmented with Atmosphere AI features.

## What It Does

The ADK agent has two tools registered via `FunctionTool`:

| Tool | Description |
|------|-------------|
| `getCurrentTime` | Returns the current time in a specific city |
| `getWeather` | Returns a weather report for a city |

Atmosphere AI features active:

- **Streaming Text Budget Manager** — tracks per-user streaming text usage with 10,000 streaming text budget; degrades to `gemini-2.0-flash-lite` at 80% usage
- **Response Caching** — completed AI responses are cached for client reconnection replay
- **Cost Metering** — streaming text counts per session are tracked and logged

## Running

```bash
# Demo mode (no API key needed — uses simulated ADK events)
cd samples/spring-boot-adk-tools
../../mvnw spring-boot:run

# With real Gemini API
GEMINI_API_KEY=your-key ../../mvnw spring-boot:run
```

Open http://localhost:8087 in your browser.

## Try These Prompts

- `What time is it in London?` — triggers the `getCurrentTime` tool
- `What's the weather in Tokyo?` — triggers the `getWeather` tool
- `Tell me about streaming text budgets` — explains the budget system
- `How does caching work?` — explains response caching

## Key Code

| File | Purpose |
|------|---------|
| `AdkToolsChat.java` | `@AiEndpoint` with ADK event bridging |
| `DemoEventProducer.java` | Simulated ADK event stream with tool calls |
| `AiConfig.java` | Configures streaming text budgets, cache inspector, cost filter |

## Architecture

```
Browser ──WebSocket──▶ Atmosphere ──▶ ADK Agent (LlmAgent)
                           │              │
                           │         ┌────┴────────┐
                           │         │ FunctionTool │
                           │         │ getCurrentTime│
                           │         │ getWeather   │
                           │         └──────────────┘
                           │
                    ┌──────┴──────────┐
                    │ Cost Metering   │
                    │ Streaming Text Budget    │
                    │ Response Cache  │
                    └──────┬──────────┘
                           │
                     ◀─────┘ streamed ADK events
```
