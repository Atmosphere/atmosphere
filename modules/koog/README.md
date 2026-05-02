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

Add `koog-spring-boot-starter` to auto-configure the `PromptExecutor`:

```xml
<dependency>
    <groupId>ai.koog</groupId>
    <artifactId>koog-spring-boot-starter</artifactId>
    <version>4.0.42</version>
</dependency>
```

Configure your LLM provider in `application.yml`:

```yaml
ai:
  koog:
    openai:
      enabled: true
      api-key: ${OPENAI_API_KEY}
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

## Capabilities

See the [capability matrix](../ai/README.md#capability-matrix) for the authoritative cross-runtime view.

- Text streaming (token-by-token via `StreamFrame.TextDelta`)
- Tool calling (via Koog's `@Tool` + `ToolRegistry`)
- Tool approval (`@RequiresApproval` gated through `ToolExecutionHelper.executeWithApproval`)
- Structured output
- Agent orchestration (graph-based, functional, ReAct strategies)
- Conversation memory
- System prompt
- Lifecycle listeners (`onToolCall`/`onToolResult` from bridge wrappers)

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
- Koog 0.7.3+
- Kotlin runtime
- kotlinx-coroutines-core
