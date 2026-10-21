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
- Spring AI 2.0.0+
