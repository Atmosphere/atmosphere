# Atmosphere + Spring AI Routing

Intelligent **prompt routing** with **content safety** and **cost metering** — powered by
[Spring AI](https://spring.io/projects/spring-ai) and Atmosphere's streaming pipeline.

Ported from the official [spring-ai-examples/agentic-patterns/routing-workflow](https://github.com/spring-projects/spring-ai-examples/tree/main/agentic-patterns/routing-workflow),
augmented with Atmosphere AI features.

## What It Does

The routing system classifies incoming prompts and routes them to specialized models:

| Category | Keywords | Target Model |
|----------|----------|-------------|
| Code | code, function, program, debug, java, python | gpt-4o (code-specialized) |
| Creative | write, story, poem, creative, imagine | claude-3.5-sonnet (creative) |
| Math | math, calculate, equation, solve, formula | o1-mini (reasoning) |
| General | everything else | gemini-2.5-flash (default) |

Atmosphere AI features active:

- **Content Safety Filter** — blocks harmful content before it reaches users
- **Cost Metering Filter** — tracks streaming text usage per session and per model

## Running

```bash
# Demo mode (no API key needed — shows routing decisions)
cd samples/spring-boot-spring-ai-routing
../../mvnw spring-boot:run

# With a real LLM
OPENAI_API_KEY=sk-... ../../mvnw spring-boot:run
```

Open http://localhost:8088 in your browser.

## Try These Prompts

- `Write a function to sort a list in Java` — routes to code model
- `Write me a short poem about the ocean` — routes to creative model
- `Solve x^2 + 3x - 4 = 0` — routes to reasoning model
- `Hello, how are you?` — routes to default model

## Key Code

| File | Purpose |
|------|---------|
| `SpringAiRoutingChat.java` | `@AiEndpoint` with routing-aware prompt handling |
| `DemoResponseProducer.java` | Simulates routing decisions with prompt classification |
| `AiFilterConfig.java` | Configures content safety + cost metering filters |

## Architecture

```
Browser ──WebSocket──▶ Atmosphere ──▶ Spring AI ChatClient
                           │              │
                           │         ┌────┴────────────┐
                           │         │ RoutingLlmClient │
                           │         ├─────────────────┤
                           │         │ Code → gpt-4o   │
                           │         │ Creative → Claude│
                           │         │ Math → o1-mini  │
                           │         │ General → Flash  │
                           │         └─────────────────┘
                           │
                    ┌──────┴──────────┐
                    │ Content Safety  │
                    │ Cost Metering   │
                    └──────┬──────────┘
                           │
                     ◀─────┘ streamed texts
```
