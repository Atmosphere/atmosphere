# Atmosphere Quarkus LangChain4j Bridge

Quarkus extension that auto-wires the LangChain4j `StreamingChatModel` produced by
[`quarkus-langchain4j`](https://docs.quarkiverse.io/quarkus-langchain4j/dev/) into
Atmosphere's `LangChain4jAgentRuntime`. With this extension on the classpath,
`@AiEndpoint` handlers in a Quarkus app get the same configured chat model that
`@RegisterAiService` users get — no manual wiring, no duplicate config.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-quarkus-langchain4j</artifactId>
    <version>${project.version}</version>
</dependency>
```

You typically also pull in a Quarkus LangChain4j provider extension (OpenAI,
Ollama, Hugging Face, etc.):

```xml
<dependency>
    <groupId>io.quarkiverse.langchain4j</groupId>
    <artifactId>quarkus-langchain4j-openai</artifactId>
</dependency>
```

## How It Works

1. `quarkus-langchain4j-core` produces a CDI synthetic `StreamingChatModel` bean
   from `quarkus.langchain4j.*` config properties.
2. The deployment processor declares a `RequestChatModelBeanBuildItem` so the
   default model is materialized even without `@RegisterAiService` injection
   points.
3. At runtime, `AtmosphereQuarkusLangChain4jBridge` (`@ApplicationScoped`,
   `@Startup(PLATFORM_BEFORE)`) looks up that bean via Arc and calls
   `LangChain4jAgentRuntime.setModel(...)` before the Atmosphere framework
   starts dispatching prompts.

## Quick Start

```java
@AiEndpoint(path = "/atmosphere/ai-chat",
            requires = {AiCapability.TEXT_STREAMING})
@Singleton
public class AiChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // streams via Quarkus L4j chat model
    }
}
```

```properties
quarkus.langchain4j.openai.api-key=${OPENAI_API_KEY}
quarkus.langchain4j.openai.chat-model.model-name=gpt-4o-mini
quarkus.atmosphere.packages=com.example
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `AtmosphereQuarkusLangChain4jBridge` | Looks up the CDI `StreamingChatModel` and installs it on `LangChain4jAgentRuntime` at startup |
| `AtmosphereQuarkusLangChain4jProcessor` | Build steps: indexes `atmosphere-ai`, requests the default chat-model bean, registers the runtime SPI for native image |

## Samples

- [Quarkus AI Chat](../../samples/quarkus-ai-chat/) — minimal `@AiEndpoint`
  served over WebSocket, streaming token-by-token to a vanilla HTML client via
  atmosphere.js.

## Requirements

- Java 21+
- Quarkus 3.21+ (tested against 3.31.3)
- `quarkus-langchain4j` 1.9.x (Quarkiverse)
- `atmosphere-quarkus-extension` (transitive — provides the Atmosphere servlet)

## Notes

- The bridge is OpenAI/Ollama/Hugging Face-agnostic — anything that produces a
  CDI `StreamingChatModel` works.
- For OpenAI-compatible endpoints that reject `frequency_penalty` /
  `presence_penalty` (e.g. Gemini's `/v1beta/openai/`), implement a
  `ModelBuilderCustomizer<OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder>`
  bean that nulls those fields. See the sample.
- `atmosphere-quarkus-langchain4j` only auto-wires the **default** chat model.
  Apps that use multiple named models can call
  `LangChain4jAgentRuntime.setModel(...)` manually from a `@PostConstruct`
  bean of their own.
