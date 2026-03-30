# Spring Boot Koog Chat Sample

Real-time AI chat using Atmosphere with JetBrains [Koog](https://github.com/JetBrains/koog/) as the AI runtime. Koog's `PromptExecutor` handles streaming, tool calling, and agent orchestration — Atmosphere handles real-time delivery to browsers.

## How It Works

1. `koog-spring-boot-starter` auto-configures a `PromptExecutor` bean
2. `atmosphere-koog` adapter detects Koog on the classpath via the `AgentRuntime` SPI
3. `@AiEndpoint` / `@Prompt` handlers call `session.stream(message)`
4. The Koog runtime streams `StreamFrame` events, bridged to `AiEvent`s on the wire

## Configuration

```bash
# OpenAI via Koog
export LLM_API_KEY=sk-...
export LLM_MODEL=gpt-4o

# Or Gemini
export LLM_API_KEY=AIza...
export LLM_MODEL=gemini-2.5-flash
```

## Build & Run

```bash
./mvnw spring-boot:run -pl samples/spring-boot-koog-chat
```

Open http://localhost:8097/atmosphere/console/ in your browser.

## Requirements

- Java 21+
- An LLM API key (OpenAI, Gemini, etc.)
