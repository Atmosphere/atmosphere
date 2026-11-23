# Atmosphere Spring AI Adapter

`AgentRuntime` implementation backed by Spring AI `ChatClient`. When this JAR is on the classpath, `@AiEndpoint` automatically uses Spring AI for LLM streaming.

Use this adapter when Spring AI is already your model, advisor, memory, tool, or `VectorStore` layer. Atmosphere keeps Spring AI in charge of those native abstractions and adds the service layer around them — real-time client transports (WebSocket, SSE, long-polling, gRPC), `@Agent`/`@AiEndpoint` dispatch, governance and HITL approval, durable sessions and session-tape replay, and MCP/A2A/AG-UI exposure of the same agent. It runs on top of Spring AI; it does not replace it.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-ai</artifactId>
    <version>${project.version}</version>
</dependency>
```

## Quick Start

```java
@AiEndpoint(path = "/ai/chat", systemPrompt = "You are a helpful assistant")
public class MyChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // uses Spring AI ChatClient automatically
    }
}
```

For direct usage:

```java
var session = StreamingSessions.start(resource);
springAiAdapter.stream(chatClient, prompt, session);
```

## LLM settings (`LLM_MODE`, `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY`)

The shared `LLM_*` variables and their meaning are described in [Atmosphere AI → Configuration](../ai/README.md#configuration); this section lists only what this adapter reads.
With an Atmosphere Spring Boot starter, `atmosphere.ai.mode` / `model` / `api-key` / `base-url` are read too, after `llm.*` and `LLM_*`: see [LLM settings with the Spring Boot starters](../ai/README.md#llm-settings-with-the-spring-boot-starters).

When the application declares no `ChatClient`, the adapter builds one:

| Setting | Read from (first non-blank wins) | Default |
|---|---|---|
| API key | `spring.ai.openai.api-key`, `llm.api-key`, `LLM_API_KEY` | none: no `ChatClient` outside local mode |
| Base URL | `spring.ai.openai.base-url`, `llm.base-url`, `LLM_BASE_URL` | resolved as below |
| Model | `spring.ai.openai.chat.options.model`, `llm.model`, `LLM_MODEL` | `gpt-4o-mini` |
| Mode | `llm.mode`, `LLM_MODE` | remote |

`AtmosphereSpringAiEnvironmentPostProcessor` also copies `llm.*` / `LLM_*` into the
`spring.ai.openai.*` defaults Spring AI's own OpenAI beans read, at the lowest
precedence (a `spring.ai.openai.*` value you set always wins): the key from `llm.api-key`,
`LLM_API_KEY`, `OPENAI_API_KEY` or `GEMINI_API_KEY`; the base URL from `llm.base-url` /
`LLM_BASE_URL`, else the local endpoint in local mode, else Gemini's endpoint when the
model named is not OpenAI's; the model from `llm.model` / `LLM_MODEL`. In local mode
with no key, both send the placeholder key `not-needed-for-local`. Spring AI 2.0 hands
the base URL to the OpenAI SDK verbatim, so it keeps its `/v1`.
Registered as Spring Boot 4's `org.springframework.boot.EnvironmentPostProcessor` (the only post-processor type Spring Boot 4.2 loads); on Spring Boot 3, `AtmosphereSpringAiBoot3EnvironmentListener` runs the same mapping.

Endpoint resolution is the built-in runtime's (`AiConfig.resolveBaseUrl`):

1. an explicit base URL wins — with a `WARN` at startup when `LLM_MODE=local` is set and
   the URL's host is not this machine;
2. else `LLM_MODE=local` selects the local Ollama endpoint, `http://localhost:11434/v1`;
3. else the model picks the provider: `gpt-*`, `o1*`, `o3*` go to `https://api.openai.com/v1`,
   any other model (`gemini-2.5-flash`, ...) to Gemini's OpenAI-compatible endpoint
   `https://generativelanguage.googleapis.com/v1beta/openai`.

`LLM_MODE` is read trimmed and case-insensitively (`Local`, ` local ` are local mode).

The startup line `Spring AI ChatClient built: model=…, endpoint=…, local=…` names the
endpoint; when the application binds its own `ChatClient`, a second line says the
auto-configured one is not used.


### API keys over plain http

A key goes over plain `http` only to a loopback host (`localhost`, `127.0.0.0/8`, `::1`,
read from the URL alone, no DNS lookup): when Atmosphere builds the `ChatClient`, or maps the key or the endpoint into `spring.ai.openai.*`, a key bound for an `http` endpoint on any other host fails the start, the error logged at `ERROR` naming the endpoint and the opt-in, unless the application sets
`atmosphere.ai.allow-cleartext-api-key=true` (environment variable
`ATMOSPHERE_AI_ALLOW_CLEARTEXT_API_KEY=true`), which logs a `WARN` instead. `https`
endpoints are unaffected, and a keyless call (local mode with no key) goes to any host. The
rule is shared by every runtime: see
[API keys over plain http](../ai/README.md#api-keys-over-plain-http).

## Key Classes

| Class | Purpose |
|-------|---------|
| `SpringAiStreamingAdapter` | Bridges Spring AI `ChatClient` to `StreamingSession` |
| `SpringAiAgentRuntime` | `AgentRuntime` SPI implementation (priority 100) |
| `SpringAiEmbeddingRuntime` | `EmbeddingRuntime` SPI wrapping Spring AI `EmbeddingModel` (priority 200) |
| `SpringAiToolBridge` | Translates Atmosphere `ToolDefinition` to Spring AI `ToolCallback` with HITL approval gating |
| `SpringAiAdvisors` | Per-request bridge for the Spring AI `Advisor` chain — RAG / memory / guardrails / observability |
| `SpringAiDecisionModel` | Atmosphere `DecisionModel` over a Spring AI 2.1 `org.springframework.ai.decision.DecisionModel` (priority 200) |
| `AtmosphereSpringAiAutoConfiguration` | Spring Boot auto-configuration |

## Per-Request Advisors (`SpringAiAdvisors`)

Spring AI's advisor chain (`QuestionAnswerAdvisor`, `MessageChatMemoryAdvisor`,
`SafeGuardAdvisor`, `SimpleLoggerAdvisor`, …) is the framework's main extension
point. `ChatClient.Builder.defaultAdvisors(...)` covers the static case, but
some advisors are scoped to a single request (per-user audit, scoped guardrails,
session-bound memory) and must not mutate the shared builder. `SpringAiAdvisors`
is the per-request slot:

```java
var safeGuard = SafeGuardAdvisor.builder()
        .sensitiveWords(List.of("badword"))
        .failureResponse("I cannot answer that.")
        .build();

var ctx = SpringAiAdvisors.attach(baseContext, safeGuard, new SimpleLoggerAdvisor());
runtime.execute(ctx, session);
```

`SpringAiAgentRuntime.execute` reads the list via `SpringAiAdvisors.from(context)`
and appends it to the prompt spec via `promptSpec.advisors(perRequestAdvisors)`
before `.stream()`. The list rides on `AgentExecutionContext.metadata()` under
`SpringAiAdvisors.METADATA_KEY` so the `modules/ai` core stays free of any
`spring-ai-client-chat` dependency.

## Samples

- [Spring Boot AI Chat](../../samples/spring-boot-ai-chat/) -- swap to `atmosphere-spring-ai` dependency for Spring AI support

## Full Documentation

See the [atmosphere-ai capability matrix](../ai/README.md#capability-matrix) and
<https://atmosphere.github.io/docs/reference/ai/> for the unified capability matrix
across all runtimes.

## Requirements

- Java 21+
- `atmosphere-ai` (transitive)
- Spring AI 2.1.0 (the version the build compiles and tests against)

## Decision models

Spring AI 2.1 adds `org.springframework.ai.decision`, typed yes/no, choice and
score questions answered with probabilities. When the application context holds
one Spring AI `DecisionModel` bean (or several, one of them `@Primary`) and an
`AiEnvironment`, the auto-configuration wraps it in `SpringAiDecisionModel` and
registers it with `DecisionModelResolver` for that application, once every bean
exists. The governance tiers' `LLM_CLASSIFIER` and intent routing then ask it
instead of the `RuntimeDecisionModel` fallback. Its priority, 200, is above the
TypeSafe module's 100: a model the application defined as a bean outranks one
its classpath brought. The registration ends when the context closes; the bean
stays the context's, and nothing in Atmosphere closes it. With several decision
model beans and none `@Primary`, nothing is registered and an INFO line says so.

**This is on by default.** Spring AI 2.1's OpenAI and Ollama model starters
create a decision model bean unless `spring.ai.model.decision` names another
provider, so an application that adds one of them next to
`atmosphere-spring-ai` moves its safety questions from the LLM-chat fallback to
that provider's decisions API. A provider that has no such API (an
OpenAI-compatible local server, say) then fails those questions as `ERROR`.
To keep the fallback, define no Spring AI decision model bean; with the
starters, set `spring.ai.model.decision=none`, a value no starter matches.

The consumers that resolve a model on every check (the scope and moderation
`LLM_CLASSIFIER` tiers built without an explicit model, and intent routing)
ask the registered model from the next check on. The `LLM_CLASSIFIER` injection
tier builds its classifier once: it asks the registered model only when it is
built after the registration, and the model it kept before stays open until
`DecisionModelResolver.reset()` or the application closes.

The mapping:

| Atmosphere `Question` | Spring AI question | Answer |
|---|---|---|
| `Noul` (`whenTrue`/`whenFalse`) | `YesNoQuestion` (`whenYes`/`whenNo`) | `p(yes)` → `probabilityTrue`, value `p >= 0.5`, confidence `\|2p - 1\|` |
| `Choice` (key → description) | `ChoiceQuestion` (same keys and descriptions) | the provider's probabilities and confidence, unchanged |
| `Score` (level texts, lowest first) | `ScoreQuestion`, level `i` labelled `"i"` | the per-level probability list → level → probability |

Every confidence is tagged `PROVIDER_DISTRIBUTION`. A provider that returns no
distribution leaves the probability map empty, and no confidence leaves it
unknown: neither is inferred. A `RefusalAnswer` fails its question (`ERROR`,
the provider's reason in the detail). Spring AI's `DecisionResponse` builder
already refuses a response that skips a question or answers outside it; such a
call fails every question as `ERROR` with Spring AI's message.

`decide` returns within the request's timeout: the call runs on a virtual
thread interrupted at the deadline (`TIMEOUT`), at most 8 calls are in flight
(`CAPACITY` when no slot frees before the deadline), and a call that throws is
`ERROR`. `isAvailable()` is `true` for a registered bean: Spring AI has no probe
that does not make a billed call, so a backend that is down shows up as `ERROR`
answers, not as an unavailable model.

## Stream idle timeout

The turn's `StreamIdleWatchdog` ends a turn whose `ChatClient` stream sends
nothing for `atmosphere.ai.stream.idle-timeout` (default `5m`, `0` off) with one
`TimeoutException`, then disposes the subscription. Every element counts as activity,
before anything filters it; tools and approvals are not idle time.

The transport timeout of the `ChatClient` Atmosphere builds (`atmosphereChatClient`, when the
application declares none): Spring AI's `AbstractOpenAiOptions.DEFAULT_TIMEOUT` (60 s), which
it used to pass to `OpenAiSetup`, reaches the OpenAI SDK as a *request* timeout
(`Timeout.request`, OkHttp's `callTimeout`: the whole call), so every stream was cut after
60 s however healthy (`SpringAiTransportTimeoutTest` showed it cut at 61.9 s). The client now has
no request timeout; an OkHttp interceptor registered through the `OpenAiHttpClientBuilderCustomizer`
gives each call a read timeout instead, the application's bound plus 10 s between two reads (none
when the bound is `0`). `SpringAiTransportTimeoutTest` proves a 66 s healthy stream completes and
that a silent one is cut, and its connection closed, after the bound plus 10 s.

Residuals, proved against a loopback socket by `SpringAiRuntimeContractTest` (the TCK's stream
idle cases, both dispatch modes) and declared by its fixture: Spring AI 2.0's `OpenAiChatModel`
streams through a `Flux.create` that registers no `onDispose`, so disposing the subscription does
not close the OpenAI SDK's stream: a turn's connection stays open after its cancel or its timeout
until the provider ends it or the read timeout above fires. The SDK's SSE parser drops keep-alive
comments before Spring AI emits an element, so a provider that sends only keep-alives for longer
than the bound times the turn out. A `ChatClient` the application builds itself keeps its own
timeouts. See
[Stream idle timeout](../ai/README.md#stream-idle-timeout).
