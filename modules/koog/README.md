# Atmosphere Koog Adapter

`AgentRuntime` implementation backed by JetBrains [Koog](https://github.com/JetBrains/koog/) `PromptExecutor`. When this JAR is on the classpath, `@AiEndpoint` can run Koog agents and stream their output to browser clients in real-time.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-koog</artifactId>
    <version>${project.version}</version>
</dependency>
```

## Quick Start

```java
@AiEndpoint(path = "/ai/chat", systemPrompt = "You are a helpful assistant")
public class MyChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // uses Koog when atmosphere-koog is on classpath
    }
}
```

The bundled `AtmosphereKoogAutoConfiguration` wires a `PromptExecutor` from
the `OPENAI_API_KEY` (or `LLM_API_KEY`) environment variable using Koog 1.0's
`OpenAILLMClient(apiKey)` factory and `MultiLLMPromptExecutor`:

```yaml
atmosphere:
  koog:
    model: gpt-4o          # any OpenAIModels.models id
    api-key: ${OPENAI_API_KEY}
```

For other Koog stable clients (Anthropic, Bedrock, Ollama) or for the beta
Google/Gemini client (`prompt-executor-google-client-jvm:1.0.0-beta-preview7`,
which targets Koog's beta module track), build the `PromptExecutor`
explicitly and inject it via the runtime's companion API:

```kotlin
val client = AnthropicLLMClient(apiKey)
val executor = MultiLLMPromptExecutor(client)
KoogAgentRuntime.setPromptExecutor(executor)
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `KoogAgentRuntime` | `AgentRuntime` SPI implementation (priority 100) |
| `AtmosphereToolBridge` | Translates Atmosphere `ToolDefinition` to Koog `Tool` with HITL approval gating |
| `KoogStrategy` | Per-request bridge for swapping the default `chatAgentStrategy()` with a custom `AIAgentGraphStrategy` |
| `AtmosphereKoogAutoConfiguration` | Spring Boot auto-configuration |

## Per-Request Strategy (`KoogStrategy`)

By default `KoogAgentRuntime` runs prompts through Koog's `chatAgentStrategy()`
graph. To exercise the framework's distinguishing feature — graph-based
orchestration via the `strategy {}` DSL (subgraphs, parallel nodes, ReAct
loops, custom routing) — attach an `AIAgentGraphStrategy` per request:

```kotlin
val planThenWriteStrategy: AIAgentGraphStrategy<String, String> = strategy("plan-then-write") {
    val plan by node<String, String> { input ->
        llm.writeSession { updatePrompt { user("Plan steps to: $input") } }
        llm.execute().asText()
    }
    val write by node<String, String> { plan ->
        llm.writeSession { updatePrompt { user("Execute the plan:\n$plan") } }
        llm.execute().asText()
    }
    edge(nodeStart forwardTo plan)
    edge(plan forwardTo write)
    edge(write forwardTo nodeFinish)
}

val ctx = KoogStrategy.attach(baseContext, planThenWriteStrategy)
runtime.execute(ctx, session)
```

When a strategy is attached, `KoogAgentRuntime` builds an `AIAgent` with the
custom strategy instead of the default chat one (`maxIterations` doubled to
allow multi-step graphs); when absent, the default chat-agent fast path is
unchanged. Feature handlers (`onAfterLLMCall`, `onToolCall`, `onAgentBeforeClosed`,
…) are wired identically in both paths via a shared `wireFeatureHandlers`
extension.

## Per-Request Tool-Loop Cap (`ToolLoopPolicy`)

`KoogAgentRuntime` honors `ToolLoopPolicies.attach(ctx, policy)` per request:
the policy's `maxIterations()` is doubled (Koog counts both LLM rounds and
tool execution steps in its iteration budget) and passed as
`AIAgent.maxIterations`. Without an attached policy, the runtime falls back
to `ToolLoopPolicy.DEFAULT.maxIterations() = 5` rounds = 10 Koog iterations,
preserving the pre-policy behavior bit-identical.

```java
// Strict tool-driven agent — fail-fast on overflow:
var ctx = ToolLoopPolicies.attach(baseContext, ToolLoopPolicy.strict(3));
runtime.execute(ctx, session);

// Or via an interceptor so every request inherits the same cap:
class CodingAgentToolLoop implements AiInterceptor {
    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource r) {
        return request.withMetadata(Map.of(
                ToolLoopPolicies.METADATA_KEY,
                ToolLoopPolicy.maxIterations(8)));
    }
}
```

`OnMaxIterations.FAIL` is honored implicitly: Koog throws on overflow, the
runtime's `executeInternal` try/catch surfaces the failure as
`session.error(...)` — matching the policy's fail-fast semantics.

## Capabilities

See the [capability matrix](../ai/README.md#capability-matrix) for the authoritative cross-runtime view.

- Text streaming (token-by-token via `StreamFrame.TextDelta`)
- Tool calling (via Koog's `@Tool` + `ToolRegistry`)
- Tool approval (`@RequiresApproval` gated through `ToolExecutionHelper.executeWithApproval`)
- Structured output
- Agent orchestration (graph-based, functional, ReAct strategies)
- Conversation memory
- System prompt
- Vision, audio, multi-modal (via Koog's `Content` part conversion)
- Prompt caching (Bedrock cache control)
- Token usage (`TokenUsage` extracted from `LLMCallCompleted` feature events)
- Cancellation (`StreamingSession.isCancelled()` honored before each tool round)
- Per-request retry (via `RetryPolicy.fromOrDefault(ctx)` — bridge-wrapper `executeWithOuterRetry`)
- Lifecycle listeners (`onToolCall`/`onToolResult` from bridge wrappers)
- Per-request tool-loop iteration cap via `ToolLoopPolicy`

## Streaming Bridge

Koog's `Flow<StreamFrame>` is bridged to Atmosphere's `StreamingSession`:

| Koog StreamFrame | Atmosphere AiEvent |
|---|---|
| `TextDelta(text)` | `AiEvent.TextDelta(text)` |
| `TextComplete(text)` | `AiEvent.TextComplete(text)` |
| `ToolCallComplete(name, content)` | `AiEvent.AgentStep(name, description)` |
| `ReasoningDelta(text)` | `AiEvent.Progress(text)` |
| `End(finishReason)` | `session.complete()` |

## Requirements

- Java 21+
- `atmosphere-ai` (transitive)
- Koog 1.0.0+ (stable stream: agents-core, prompt-model, anthropic / bedrock / ollama / openai clients)
- Kotlin runtime
- kotlinx-coroutines-core
