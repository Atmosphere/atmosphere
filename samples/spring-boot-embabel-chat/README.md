# Spring Boot Embabel Chat Sample

Real-time AI chat using Atmosphere with [Embabel](https://github.com/embabel/embabel-agent) as the AI runtime. Embabel's `AgentPlatform` provides GOAP (Goal-Oriented Action Planning) over LLMs — Atmosphere streams the resulting tokens to browsers in real-time over WebSocket.

## How It Works

1. `embabel-agent-starter-platform` auto-configures an `AgentPlatform` bean.
2. `atmosphere-embabel` detects Embabel on the classpath via the `AgentRuntime` SPI (priority 100).
3. The `@AiEndpoint` / `@Prompt` handler calls `session.stream(message)`.
4. The Embabel runtime plans an action sequence, executes it, and emits `OutputChannelEvent`s, which the Atmosphere adapter bridges to `AiEvent`s on the wire.

## Spring Boot 3.5 — Why

> Embabel framework targets Spring Boot 3.5 with Spring AI 1.x. It does not yet support Spring Boot 4.0. This sample therefore defaults to the SB 3.5 starter (`atmosphere-spring-boot3-starter`) — there is no `-Pspring-boot3` profile here because SB 3.5 *is* the only supported configuration.

The rest of the Atmosphere reactor builds on Spring Boot 4.0; this sample is the one place where the SB 3.5 starter is the default. When Embabel ships a SB 4.0–compatible release, this sample can drop back to the standard SB 4.0 default like the other samples.

## Configuration

```bash
# OpenAI via Embabel
export LLM_API_KEY=sk-...
export LLM_MODEL=gpt-4o-mini

# Or Gemini
export LLM_API_KEY=AIza...
export LLM_MODEL=gemini-2.5-flash
```

Without an API key, the sample boots in **demo mode** with simulated streaming responses, so the integration always runs end-to-end.

## Build & Run

```bash
# Build the sample (pulls Embabel from repo.embabel.com automatically)
./mvnw install -pl samples/spring-boot-embabel-chat -am -DskipTests

# Run
./mvnw spring-boot:run -pl samples/spring-boot-embabel-chat
```

Open <http://localhost:8099/atmosphere/console/> in your browser.

## Requirements

- Java 21+
- Kotlin 2.1+
- An LLM API key (OpenAI, Gemini, or any provider supported by Embabel + Spring AI 1.x)
- Embabel artifacts from <https://repo.embabel.com/artifactory/libs-release> (declared in this `pom.xml`)

## Troubleshooting

- **`Failed to read artifact descriptor for com.embabel.agent:...`** — ensure your network can reach `repo.embabel.com`. The repository is declared in this sample's POM only; if you build from a parent reactor with a custom mirror, allow the `embabel-releases` repository explicitly.
- **`No qualifying bean of type AgentPlatform`** — make sure `embabel-agent-starter-platform` is on the runtime classpath. The atmosphere-embabel module marks `embabel-agent-api` as `provided`, so a runnable sample must bring the platform starter itself (this POM does).

## Related

- [`modules/embabel/README.md`](../../modules/embabel/README.md) — the runtime adapter
- [`samples/spring-boot-koog-chat`](../spring-boot-koog-chat/) — equivalent sample for JetBrains Koog
- [`samples/spring-boot-ai-chat`](../spring-boot-ai-chat/) — generic AI chat that picks up Embabel (and any other Atmosphere runtime adapter) transparently when its dependency is on the classpath; use `-Pspring-boot3` and add `atmosphere-embabel` + the Embabel platform/provider starters to engage Embabel from this sample.
