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
environment variables using Koog 1.0's `OpenAILLMClient` factory and
`MultiLLMPromptExecutor`. Two modes:

**OpenAI** (default — no base URL):

```yaml
atmosphere:
  koog:
    model: gpt-4o          # any OpenAIModels.models id
    api-key: ${OPENAI_API_KEY}
```

**OpenAI-compatible** (`base-url` set) — the supported path for Gemini, since
Koog 1.0's native Google client ships only on the beta track
(`prompt-executor-google-client-jvm:1.0.0-beta-preview7`, whose `1.0.0-preview7`
transitives conflict with stable `1.0.0`). The requested `model` id is used
verbatim with GPT-4o's capability profile:

```yaml
atmosphere:
  koog:
    model: gemini-2.5-flash
    base-url: https://generativelanguage.googleapis.com/v1beta/openai
    api-key: ${GEMINI_API_KEY}
```

For non-OpenAI-compatible Koog stable clients (Anthropic, Bedrock, Ollama),
build the `PromptExecutor` explicitly and inject it via the companion API:

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
| `KoogPlanner` | Per-request bridge for dispatching through a caller-supplied `AIAgentPlanner` (Koog's `PlannerAIAgent`) |
| `KoogPlanBridge` | Read-only observation feature mirroring planner lifecycle events into `AiEvent.PlanUpdate` frames |
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

## Per-Request Planner (`KoogPlanner`)

Koog 1.0 GA (`agents-core`) ships the *abstract* planner surface —
`AIAgentPlanner` / `JavaAIAgentPlanner`, the `PlannerAIAgent` runner, and the
plan lifecycle events — while the concrete planners (`SimpleLLMPlanner`,
`GOAPPlanner`) are beta-only in the separate `agents-planner` artifact, which
is **not** a dependency of this adapter. A planner is therefore always
caller-supplied: subclass `AIAgentPlanner` and attach it per request:

```kotlin
class ResearchPlanner : AIAgentPlanner<String, String, MyState, List<String>>() {
    override fun initializeState(input: String) = MyState(input)
    override fun provideOutput(state: MyState) = state.render()
    override suspend fun buildPlan(context: AIAgentPlannerContext, state: MyState, plan: List<String>?) =
        state.remainingSteps()
    override suspend fun executeStep(context: AIAgentPlannerContext, state: MyState, plan: List<String>) =
        state.execute(plan.first())
    override suspend fun isPlanCompleted(context: AIAgentPlannerContext, state: MyState, plan: List<String>) =
        state.done()
}

val ctx = KoogPlanner.attach(baseContext, ResearchPlanner())
runtime.execute(ctx, session)
```

The runtime builds a per-request `PlannerAIAgent` around the planner (same
tool registry, system prompt, and iteration cap as the tool-loop path, with
LLM/tool/usage events wired through the shared `EventHandler` handlers), and
— unless the built-in `write_todos` floor already owns the request's plan
surface, or `atmosphere.ai.planning=builtin` — installs `KoogPlanBridge`,
which mirrors every plan the planner maintains into `AiEvent.PlanUpdate`
frames (`pending` / `in_progress` / `completed` / `abandoned` statuses,
scope-keyed like the floor's store writes).

> **Why `AiCapability.PLANNING` is deliberately not declared:** the harness
> consumes the flag at endpoint registration and suppresses the portable
> `write_todos` floor when a runtime declares it (AUTO mode, native wins).
> Koog's native plan machinery only exists on requests that attach a
> caller-supplied planner — the default chat dispatch has none — so a static
> declaration would leave planner-less dispatches with no plan surface at
> all (Runtime Truth). Planner-attached requests still get the full native
> observation surface described above, pinned end-to-end by
> `KoogPlanBridgeTest`.

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
