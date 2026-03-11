# Atmosphere + Embabel Horoscope Agent

Multi-step **horoscope agent** with **step progress streaming** and **content safety** — powered by
[Embabel](https://embabel.com/) and Atmosphere's real-time pipeline.

Ported from the official [embabel-agent-examples/horoscope](https://github.com/embabel/embabel-agent-examples/tree/main/examples-java/horoscope),
augmented with Atmosphere AI features.

## What It Does

The agent performs a 3-step workflow streamed to the browser in real-time:

| Step | Action | Description |
|------|--------|-------------|
| 1 | Extract Sign | Identifies the zodiac sign from user input |
| 2 | Find Events | Discovers relevant celestial events and alignments |
| 3 | Generate Horoscope | Creates a personalized horoscope writeup |

Each step sends progress updates to the browser via WebSocket, so users see
the agent working through its pipeline in real-time.

Atmosphere AI features active:

- **Content Safety Filter** — blocks harmful content before reaching users
- **Step Progress Streaming** — real-time feedback as each agent action executes

## Running

```bash
# Demo mode (no API key needed — uses simulated horoscopes)
cd samples/spring-boot-embabel-horoscope
../../mvnw spring-boot:run

# With a real LLM
OPENAI_API_KEY=sk-... ../../mvnw spring-boot:run
```

Open http://localhost:8089 in your browser.

## Try These Prompts

- `What's my horoscope for Leo?` — full 3-step workflow
- `Pisces horoscope today` — another sign
- `Horoscope for Scorpio` — deep emotional insights
- `Tell me about Sagittarius` — adventure-themed horoscope

## Key Code

| File | Purpose |
|------|---------|
| `EmbabelHoroscopeChat.java` | `@AiEndpoint` with multi-step agent |
| `HoroscopeAgent.java` | `@Agent` with `@Action` steps |
| `DemoResponseProducer.java` | Simulates multi-step workflow with progress |
| `AiFilterConfig.java` | Content safety filter |

## Architecture

```
Browser ──WebSocket──▶ Atmosphere ──▶ Embabel AgentPlatform
                           │              │
                           │         ┌────┴──────────────┐
                           │         │ 1. extractSign    │
                           │         │ 2. findEvents     │
                           │         │ 3. generateHoroscope│
                           │         └──────────────────┘
                           │
                    ┌──────┴──────────┐
                    │ Content Safety  │
                    │ Step Progress   │
                    └──────┬──────────┘
                           │
                     ◀─────┘ progress + streamed texts
```
