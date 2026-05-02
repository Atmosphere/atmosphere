# Atmosphere LangChain4j Adapter

`AgentRuntime` implementation backed by LangChain4j `StreamingChatLanguageModel`. When this JAR is on the classpath, `@AiEndpoint` automatically uses LangChain4j for LLM streaming.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-langchain4j</artifactId>
    <version>${project.version}</version>
</dependency>
```

## Quick Start

```java
@AiEndpoint(path = "/ai/chat", systemPrompt = "You are a helpful assistant")
public class MyChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // uses LangChain4j automatically
    }
}
```

For direct usage:

```java
var session = StreamingSessions.start(resource);
model.chat(ChatMessage.userMessage(prompt),
    new AtmosphereStreamingResponseHandler(session));
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `LangChain4jStreamingAdapter` | Bridges LangChain4j models to `StreamingSession` |
| `ToolAwareStreamingResponseHandler` | `StreamingChatResponseHandler` with multi-round tool calling + lifecycle events |
| `LangChain4jAgentRuntime` | `AgentRuntime` SPI implementation (priority 100) |
| `LangChain4jEmbeddingRuntime` | `EmbeddingRuntime` SPI wrapping LC4j `EmbeddingModel` (priority 190) |
| `LangChain4jToolBridge` | Translates Atmosphere `ToolDefinition` to LC4j `ToolSpecification` with HITL approval gating |
| `LangChain4jAiServices` | Per-request bridge for routing prompts through an `AiServices`-backed interface |
| `AtmosphereLangChain4jAutoConfiguration` | Spring Boot auto-configuration |

## Per-Request AiServices (`LangChain4jAiServices`)

LangChain4j's `AiServices` is the framework's declarative API: a Java interface
annotated with `@SystemMessage` / `@UserMessage` that LC4j proxies into a
fully-wired call (system prompt, conversation memory, tools, RAG, output
parsing). Without this bridge a caller had to choose between driving everything
through the Atmosphere pipeline or hand-building an `AiServices` call and
skipping Atmosphere streaming. `LangChain4jAiServices` lets the user keep their
`AiServices` interface as the canonical model and still flow tokens through
`StreamingSession`:

```java
public interface MovieAssistant {
    @SystemMessage("You are a movie expert. Reply concisely.")
    TokenStream chat(@UserMessage String message);
}

@Bean
MovieAssistant assistant(StreamingChatModel model) {
    return AiServices.create(MovieAssistant.class, model);
}

@Component
class AssistantInterceptor implements AiInterceptor {
    private final MovieAssistant assistant;
    AssistantInterceptor(MovieAssistant assistant) { this.assistant = assistant; }

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource r) {
        return request.withMetadata(Map.of(
                LangChain4jAiServices.METADATA_KEY,
                LangChain4jAiServices.of(assistant::chat)));
    }
}
```

When the bridge is present, `LangChain4jAgentRuntime` **bypasses** its own
prompt assembly (no `ChatRequest.builder()`, no system-prompt threading, no
tool-spec wiring, no history replay) — those concerns belong to the user's
`AiServices` interface. The runtime just calls `invoker.invoke(context.message())`
and bridges the returned `TokenStream` into the session
(`onPartialResponse` → `send`, `onCompleteResponse` → `complete` + token usage,
`onError` → `error`). Gateway admission, outer retry, and lifecycle listeners
still wrap the call — the bridge replaces only the dispatch primitive.

## Samples

- [Spring Boot AI Chat](../../samples/spring-boot-ai-chat/) -- swap to `atmosphere-langchain4j` dependency for LangChain4j support

## Full Documentation

See the [atmosphere-ai capability matrix](../ai/README.md#capability-matrix) and
<https://atmosphere.github.io/docs/reference/ai/> for the unified capability matrix
across all runtimes.

## Requirements

- Java 21+
- `atmosphere-ai` (transitive)
- LangChain4j 1.12.2+
