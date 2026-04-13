# Spring Boot Semantic Kernel Chat Sample

Real-time AI chat using Atmosphere with [Microsoft Semantic Kernel for Java](https://github.com/microsoft/semantic-kernel-java) as the `AgentRuntime`. SK's `ChatCompletionService` handles streaming and (via `atmosphere-semantic-kernel`'s `SemanticKernelToolBridge`) tool calling — Atmosphere handles real-time delivery to browsers.

## How It Works

1. `SkConfig` builds a SK `OpenAIAsyncClient` and `OpenAIChatCompletion` from `llm.*` properties.
2. `AtmosphereSemanticKernelAutoConfiguration` (from `atmosphere-semantic-kernel`) detects the `ChatCompletionService` bean and wires it into `SemanticKernelAgentRuntime.setChatCompletionService(...)`.
3. The `AgentRuntime` SPI (`ServiceLoader`) picks up `SemanticKernelAgentRuntime` on the classpath so `@AiEndpoint` can route prompts through it.
4. `@Prompt onPrompt(String, StreamingSession)` calls `session.stream(message)` which flows through the SK runtime → SK's `Flux<StreamingChatContent<?>>` → Atmosphere `AiEvent`s on the wire.

## Configuration

```bash
# OpenAI
export LLM_API_KEY=sk-...
export LLM_MODEL=gpt-4o-mini

# Or an Azure / OpenAI-compatible endpoint
export LLM_API_KEY=...
export LLM_BASE_URL=https://<resource>.openai.azure.com/
export LLM_MODEL=gpt-4o-mini
```

Without `LLM_API_KEY`, the sample falls through to `DemoResponseProducer` so it still runs end-to-end for smoke testing.

## Build & Run

```bash
./mvnw spring-boot:run -pl samples/spring-boot-semantic-kernel-chat
```

Open http://localhost:8098/atmosphere/console/ in your browser.

## Key Files

| File | Purpose |
|------|---------|
| `SkChatApplication.java` | Spring Boot entry point |
| `SkChat.java` | `@AiEndpoint` + `@Prompt` handler |
| `SkConfig.java` | Builds the real SK `ChatCompletionService` (`OpenAIChatCompletion.builder().withOpenAIAsyncClient(...).withModelId(...).build()`) |
| `DemoResponseProducer.java` | Demo-mode fallback when no API key is set |
| `application.yml` | Port 8098, env-var-driven `llm.*` properties |

## Requirements

- Java 21+
- An OpenAI or Azure OpenAI API key (optional — demo mode without)

## Related

- [`modules/semantic-kernel`](../../modules/semantic-kernel/) — the `AgentRuntime` adapter this sample exercises.
- [`samples/spring-boot-koog-chat`](../spring-boot-koog-chat/) — parallel sample wired against JetBrains Koog.
