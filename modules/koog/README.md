# Atmosphere Koog Adapter

`AgentRuntime` implementation backed by JetBrains [Koog](https://github.com/JetBrains/koog/) `PromptExecutor`. When this JAR is on the classpath, `@AiEndpoint` can run Koog agents and stream their output to browser clients in real-time.

Use this adapter when JetBrains Koog is already your agent and prompt-execution layer. Atmosphere keeps Koog in charge of its native agent orchestration and adds the service layer around it — real-time client transports (WebSocket, SSE, long-polling, gRPC), `@Agent`/`@AiEndpoint` dispatch, governance and HITL approval, durable sessions and session-tape replay, and MCP/A2A/AG-UI exposure of the same agent. It runs on top of Koog; it does not replace it.

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
KoogAgentRuntime.setPromptExecutor(aiEnvironment, executor)
```

`aiEnvironment` is the application's `AiEnvironment` (the `AiEnvironment` bean the
Atmosphere starter registers, or `AiEnvironment.of(framework.getAtmosphereConfig())`).
An executor (and a model, `KoogAgentRuntime.setDefaultModel(aiEnvironment, model)`)
set this way wins over the one the auto-configuration builds: with the Atmosphere
starter it only offers its executor and model to the context's `AiEnvironment`.
The one-argument `setPromptExecutor(executor)` and `setDefaultModel(model)` still
win too, for every application in the JVM, and are deprecated.

## LLM settings (`LLM_MODE`, `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY`)

The shared `LLM_*` variables and their meaning are described in [Atmosphere AI → Configuration](../ai/README.md#configuration); this section lists only what this adapter reads.
With an Atmosphere Spring Boot starter, `atmosphere.ai.mode` / `model` / `api-key` / `base-url` are read too, after `llm.*` and `LLM_*`: see [LLM settings with the Spring Boot starters](../ai/README.md#llm-settings-with-the-spring-boot-starters).

| Setting | Read from (first non-blank wins) | Default |
|---|---|---|
| API key | `atmosphere.koog.api-key`, `llm.api-key`, `LLM_API_KEY`, `OPENAI_API_KEY` | none: no executor outside local mode (startup `WARN`) |
| Base URL | `atmosphere.koog.base-url`, `llm.base-url`, `LLM_BASE_URL` | resolved as below |
| Model | `atmosphere.koog.model`, `llm.model`, `LLM_MODEL` | `gpt-4o` |
| Mode | `llm.mode`, `LLM_MODE` | remote |

In local mode with no key the client sends the placeholder key `not-needed-for-local`.
With no base URL, resolution is the built-in runtime's (`AiConfig.resolveBaseUrl`):
local mode selects `http://localhost:11434/v1`, a model that is not OpenAI's selects
Gemini's OpenAI-compatible endpoint, and an OpenAI model keeps Koog's own OpenAI client
(`api.openai.com`, with the `OpenAIModels` catalogue). An explicit base URL wins, with a
`WARN` when `LLM_MODE=local` is set and its host is not this machine. The startup line
`Koog runtime configured: model '…' via OpenAILLMClient (…)` names the endpoint.


### API keys over plain http

A key goes over plain `http` only to a loopback host (`localhost`, `127.0.0.0/8`, `::1`,
read from the URL alone, no DNS lookup): with a key and an `http` endpoint on any other host the application fails to start, the error logged at `ERROR` naming the endpoint and the opt-in, unless the application sets
`atmosphere.ai.allow-cleartext-api-key=true` (environment variable
`ATMOSPHERE_AI_ALLOW_CLEARTEXT_API_KEY=true`), which logs a `WARN` instead. `https`
endpoints are unaffected, and a keyless call (local mode with no key) goes to any host. The
rule is shared by every runtime: see
[API keys over plain http](../ai/README.md#api-keys-over-plain-http).

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

## Stream idle timeout

The turn's `StreamIdleWatchdog` (`atmosphere.ai.stream.idle-timeout`, default `5m`, `0` off)
is opened by this runtime in both `execute` and `executeWithHandle`; `execute` now runs the turn
on the same worker, with the outer retry, and waits for it interruptibly. Every streamed frame
and every LLM call's start and end count as activity; with the agent strategy the bound covers
each whole model call. Past the bound the turn ends with one `TimeoutException` and is cancelled
(`agentRun.cancelCalls()`, `Job.cancel()`, an interrupt).

`AtmosphereOpenAILLMClient` (the client the auto-configuration builds) has Atmosphere's own
transport, `AtmosphereKoogHttpClient` over a CIO Ktor `HttpClient` that the client creates and its
`close()` closes; the auto-configuration closes the executors it built when its context closes.
Koog's own transport streamed through Ktor's `sse {}`, which sends the request from the
`HttpClient`'s scope rather than the caller's, so a cancel before the response headers never
reached it, and its default engine (Apache 5) kept the connection after a cancel past the
headers. Atmosphere's transport streams with `preparePost(...).execute {}` on the turn's coroutine,
so the turn's cancel and its bound close the socket before and after the headers; it reads the SSE
lines itself, so a keep-alive comment (`: keep-alive`) is activity; and each call of a turn gets
the bound plus 10 s as its socket timeout (none when the bound is off), the transport's backstop.

The executor streaming path (a turn with no tools) reports its model call to lifecycle listeners
as the agent path does: one `onModelStart`, then `onModelEnd` with the end frame's usage, or one
`onModelError` on a provider failure, the idle timeout or a cancel (`KoogStreamingLifecycleTest`).
Every turn fires `onStart`, then `onError` once (the timeout, a failure) or `onCompletion`.

`KoogRuntimeContractTest` runs the TCK's stream idle cases against a loopback socket through
`AtmosphereOpenAILLMClient`, in both dispatch modes, with no residual declared: the turn ends once
on the bound and on a cancel, the connection closes before and after the headers, keep-alives
keep a turn alive, and listeners see `onError` once (`KoogTransportTest` covers the socket timeout
and the ownership). What remains: with the agent strategy the bound covers each whole model call,
and that path's cancel (`agentRun.cancelCalls()`) is not run on a socket; a `PromptExecutor` or
`LLMClient` the application builds itself keeps Koog's transport, whose connection stays open
after the turn ends and whose keep-alives are not seen.
See [Stream idle timeout](../ai/README.md#stream-idle-timeout).
