# Atmosphere AgentScope Adapter

`AgentRuntime` implementation backed by [AgentScope Java](https://github.com/agentscope-ai/agentscope-java)
(Tongyi Lab) `ReActAgent`. When this JAR is on the classpath, `@AiEndpoint`
can run AgentScope agents and stream their output to browser clients in
real-time over Atmosphere's transport.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-agentscope</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Or use the CLI to scaffold a working sample:

```bash
atmosphere new my-app --template ai-chat --runtime agentscope --force
```

## Quick Start

The Spring Boot autoconfig auto-builds an `OpenAIChatModel` and `ReActAgent`
when `llm.api-key` is set or `LLM_MODE=local`, so the default `spring-boot-ai-chat`
template runs unchanged (see [LLM settings](#llm-settings-llm_mode-llm_base_url-llm_model-llm_api_key)):

```yaml
llm:
  api-key: ${OPENAI_API_KEY}
  base-url: https://api.openai.com/v1
  model: gpt-4o-mini
```

Override the system prompt with `atmosphere.agentscope.system-prompt`
(falls back to `llm.system-prompt`).

For full control, declare your own `ReActAgent` `@Bean` — the autoconfig
detects it via `@ConditionalOnMissingBean` and skips the default:

```java
@Bean
public ReActAgent reActAgent() {
    return ReActAgent.builder()
            .name("custom-agent")
            .sysPrompt("You are a precise research assistant.")
            .model(DashScopeChatModel.builder()
                    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                    .modelName("qwen-plus")
                    .build())
            .toolkit(myToolkit)
            .memory(myMemory)
            .build();
}
```

## LLM settings (`LLM_MODE`, `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY`)

The shared `LLM_*` variables and their meaning are described in [Atmosphere AI → Configuration](../ai/README.md#configuration); this section lists only what this adapter reads.
With an Atmosphere Spring Boot starter, `atmosphere.ai.mode` / `model` / `api-key` / `base-url` are read too, after `llm.*` and `LLM_*`: see [LLM settings with the Spring Boot starters](../ai/README.md#llm-settings-with-the-spring-boot-starters).

| Setting | Read from (first non-blank wins) | Default |
|---|---|---|
| API key | `llm.api-key` (Spring maps `LLM_API_KEY` onto it) | none: no model is built outside local mode |
| Base URL | `llm.base-url` (`LLM_BASE_URL`) | resolved as below |
| Model | `llm.model` (`LLM_MODEL`) | `gpt-4o-mini` |
| Mode | `llm.mode`, else `LLM_MODE` | remote |

`OPENAI_API_KEY` and `GEMINI_API_KEY` are not read by this adapter; map one onto
`llm.api-key` in the application's configuration. In local mode with no key the model
is built with the placeholder key `not-needed-for-local`. The base URL is handed to
AgentScope's `OpenAIChatModel` verbatim, `/v1` included.

Endpoint resolution is the built-in runtime's (`AiConfig.resolveBaseUrl`):

1. an explicit base URL wins — with a `WARN` at startup when `LLM_MODE=local` is set and
   the URL's host is not this machine;
2. else `LLM_MODE=local` selects the local Ollama endpoint, `http://localhost:11434/v1`;
3. else the model picks the provider: `gpt-*`, `o1*`, `o3*` go to `https://api.openai.com/v1`,
   any other model (`gemini-2.5-flash`, ...) to Gemini's OpenAI-compatible endpoint
   `https://generativelanguage.googleapis.com/v1beta/openai`.

`LLM_MODE` is read trimmed and case-insensitively (`Local`, ` local ` are local mode).

The startup line `Auto-building AgentScope OpenAIChatModel model=… endpoint=… local=…`
names the endpoint in use. A `Model` or `ReActAgent` bean of your own replaces this one.


### API keys over plain http

A key goes over plain `http` only to a loopback host (`localhost`, `127.0.0.0/8`, `::1`,
read from the URL alone, no DNS lookup): with a key and an `http` base URL on any other host the application fails to start, the error logged at `ERROR` naming the endpoint and the opt-in, unless the application sets
`atmosphere.ai.allow-cleartext-api-key=true` (environment variable
`ATMOSPHERE_AI_ALLOW_CLEARTEXT_API_KEY=true`), which logs a `WARN` instead. `https`
endpoints are unaffected, and a keyless call (local mode with no key) goes to any host. The
rule is shared by every runtime: see
[API keys over plain http](../ai/README.md#api-keys-over-plain-http).

## Key Classes

| Class | Purpose |
|-------|---------|
| `AgentScopeAgentRuntime` | `AgentRuntime` SPI implementation (priority 100) |
| `AtmosphereAgentScopeAutoConfiguration` | Spring Boot auto-configuration |

## Capabilities

See the [capability matrix](../ai/README.md#capability-matrix) for the
authoritative cross-runtime view. Honest declared set:

- **Text streaming** — bridges `Flux<Event> ReActAgent.stream(List<Msg>, StreamOptions)`
  through `StreamingSession.send()`. Each `Event.getMessage().getTextContent()`
  delta forwards as a chunk; the terminal `event.isLast()` event closes the
  session.
- **System prompt** — threaded as a `Msg` with `MsgRole.SYSTEM` at the head
  of the dispatched message list (in addition to whatever the agent's own
  builder-time `sysPrompt` was).
- **Structured output** — surfaced via Atmosphere's pipeline-level
  `StructuredOutputCapturingSession` (auto-enabled for every
  `SYSTEM_PROMPT`-capable runtime).
- **Conversation memory** — `assembleMessages` threads `context.history()`
  into the `List<Msg>` the agent receives.
- **Token usage** — captured from `Msg.getChatUsage()` on the terminal
  event and emitted via `session.usage()`.
- **Cancellation** — `StreamingSession.isCancelled()` is checked before
  the dispatch loop and on every event tick; cancellation aborts the
  Reactor subscription cleanly without surfacing as an error.
- **Per-request retry** — `RetryPolicy.fromOrDefault(ctx)` is honored by
  the bridge-wrapper `executeWithOuterRetry` path inherited from
  `AbstractAgentRuntime`.
- **Planning** — native delegation to AgentScope's `PlanNotebook`
  (see [Native Planning](#native-planning-plannotebook-delegation) below).

## Streaming Bridge

AgentScope's `Flux<Event>` is forwarded to `StreamingSession`:

| AgentScope Event | Atmosphere session call |
|---|---|
| `event.getMessage().getTextContent()` (non-empty, non-final) | `session.send(text)` |
| `event.isLast()` with `Msg.getChatUsage()` | `session.usage(TokenUsage)` |
| Reactor `onComplete`                       | `session.complete()` |
| Reactor `onError(Throwable)`               | `session.error(t)` |

## Cancellation

`ExecutionHandle.cancel()` fires both `agent.interrupt()` (cooperative
cancel of the ReAct loop) and `Disposable.dispose()` (drops in-flight
emissions to the session). The `done` `CompletableFuture` resolves with
`CancellationException`, satisfying Correctness Invariant #2 (Terminal
Path Completeness).

## Per-Request Agent Override (`AgentScopeAgent`)

By default the runtime dispatches against the `ReActAgent` installed via
`AgentScopeAgentRuntime.setAgent(...)` (or wired by Spring auto-config).
When a single application needs to route different prompts through
different agent topologies (a `planner` agent vs. a vanilla `quick-lookup`
agent, for example) without re-installing the runtime client globally,
attach a per-request agent via `AgentScopeAgent.attach`:

```java
var planner = ReActAgent.builder()
        .name("planner")
        .tools(planningToolkit)
        .build();

var ctx = AgentScopeAgent.attach(baseContext, planner);
runtime.execute(ctx, session);
```

When attached, the runtime dispatches `planner.stream(messages, options)`
instead of the default; cancellation still calls `interrupt()` on the
active agent. When absent, the runtime falls back to the installed
default — preserving prior behavior.

## Native Planning (`PlanNotebook` delegation)

When an endpoint enables the harness PLANNING primitive
(`Harness.PLANNING`) and `atmosphere.ai.planning` is `AUTO` (default) or
`NATIVE`, this runtime delegates the plan surface to AgentScope's native
`PlanNotebook` instead of the built-in `write_todos` floor — the model
maintains its plan through AgentScope's own `create_plan` /
`update_subtask_state` / `finish_subtask` / `finish_plan` tools, with
per-step hint injection guiding execution. The bridge keeps Atmosphere
authoritative:

- **Persistence** — `AtmospherePlanStorage` implements AgentScope's
  `PlanStorage` SPI backed by Atmosphere's `AgentPlanStore`, keyed
  `agentId × conversationId` (the same slot the built-in floor uses, so
  switching modes keeps reading the same plan).
- **Observability** — a `PlanNotebook.addChangeHook` bridge mirrors every
  plan mutation into the store and emits `AiEvent.PlanUpdate` on the live
  `StreamingSession`; `SubTaskState` maps 1:1 to `PlanStatus`
  (`TODO → PENDING`, `IN_PROGRESS → IN_PROGRESS`, `DONE → COMPLETED`,
  `ABANDONED → ABANDONED`), and `finish_plan` coerces open steps to the
  plan's terminal state.
- **Continuity** — the notebook is provisioned per request; a plan with
  open steps is re-hydrated from the store via
  `recover_historical_plan`, so per-request rebuilds never lose the
  conversation's plan. Finished plans are not resurrected.
- **No duplicate surfaces** — with the native notebook active the
  built-in `write_todos` tool is not registered (`PlanningPreset` reports
  `ACTIVE(native:agentscope)`); in `BUILTIN` mode the notebook is not
  provisioned.
- **Bounds** — the notebook's `maxSubtasks` mirrors
  `FileSystemAgentPlanStore.MAX_STEPS`, so over-limit plans are rejected
  at the model-tool boundary with AgentScope's clear message.

A user-supplied `ReActAgent` bean built with `enablePlan()` /
`planNotebook(...)` keeps its own notebook whenever the harness primitive
is not active — per-request tool rebuilds carry it over instead of
clobbering it.

## Capabilities NOT declared (and why)

`TOOL_CALLING` / `TOOL_APPROVAL` (via `AgentScopeToolBridge` →
`ToolExecutionHelper.executeWithApproval`) and `VISION` / `AUDIO` /
`MULTI_MODAL` (via `ContentBlock` mapping) now ship and are declared. The one
remaining gap:

- `PROMPT_CACHING` — AgentScope does not expose a portable cache-key
  primitive on the `Model` interface as of v1.0.12.

## Requirements

- Java 21+
- `atmosphere-ai` (transitive)
- AgentScope Java 1.0.12+
- Project Reactor (transitive via AgentScope)
- Spring Boot 4.0+ (uses `spring-boot-autoconfigure` 4.x autoconfig
  imports format)
