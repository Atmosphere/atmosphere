# Atmosphere LangChain4j Adapter

`AgentRuntime` implementation backed by LangChain4j `StreamingChatLanguageModel`. When this JAR is on the classpath, `@AiEndpoint` automatically uses LangChain4j for LLM streaming.

Use this adapter when LangChain4j is already your model, AI-Services, tool, or RAG layer. Atmosphere keeps LangChain4j in charge of those native abstractions and adds the service layer around them — real-time client transports (WebSocket, SSE, long-polling, gRPC), `@Agent`/`@AiEndpoint` dispatch, governance and HITL approval, durable sessions and session-tape replay, and MCP/A2A/AG-UI exposure of the same agent. It runs on top of LangChain4j; it does not replace it.

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

## Out-of-box provider reach

The bundled `AtmosphereLangChain4jAutoConfiguration` auto-builds an
OpenAI-compatible `OpenAiStreamingChatModel` from `llm.api-key` /
`llm.base-url` / `llm.model` / `llm.mode` **only** (gated on
`@ConditionalOnExpression` for a non-empty `llm.api-key` or local mode; see
[LLM settings](#llm-settings-llm_mode-llm_base_url-llm_model-llm_api_key)):

```yaml
llm:
  api-key: ${OPENAI_API_KEY}
  base-url: https://api.openai.com/v1   # any OpenAI-compatible endpoint
  model: gpt-4o-mini
```

For Anthropic, Ollama, Bedrock, or any other non-OpenAI-compatible provider,
declare your own `StreamingChatModel` `@Bean` — it wins via
`@ConditionalOnMissingBean(StreamingChatModel.class)` and the runtime bridges
whichever model bean is present:

```java
@Bean
StreamingChatModel chatModel() {
    return AnthropicStreamingChatModel.builder()
            .apiKey(System.getenv("ANTHROPIC_API_KEY"))
            .modelName("claude-3-5-sonnet-20241022")
            .build();
}
```

There is no multi-provider auto-wiring: the zero-config path is OpenAI-compatible
only, and every other provider is a one-`@Bean` opt-in.

## LLM settings (`LLM_MODE`, `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY`)

The shared `LLM_*` variables and their meaning are described in [Atmosphere AI → Configuration](../ai/README.md#configuration); this section lists only what this adapter reads.
With an Atmosphere Spring Boot starter, `atmosphere.ai.mode` / `model` / `api-key` / `base-url` are read too, after `llm.*` and `LLM_*`: see [LLM settings with the Spring Boot starters](../ai/README.md#llm-settings-with-the-spring-boot-starters).

| Setting | Read from (first non-blank wins) | Default |
|---|---|---|
| API key | `llm.api-key` (Spring maps `LLM_API_KEY` onto it) | none: no model is built outside local mode |
| Base URL | `llm.base-url` (`LLM_BASE_URL`) | resolved as below |
| Model | `llm.model` (`LLM_MODEL`) | `gpt-4o-mini` |
| Mode | `llm.mode`, or `LLM_MODE` | remote |

`OPENAI_API_KEY` and `GEMINI_API_KEY` are not read by this adapter; map one onto
`llm.api-key` in the application's configuration (`api-key: ${LLM_API_KEY:${GEMINI_API_KEY:}}`).
In local mode with no key the model is built with the placeholder key
`not-needed-for-local`, which a local server ignores. The base URL is handed to
LangChain4j verbatim, `/v1` included.

Endpoint resolution is the built-in runtime's (`AiConfig.resolveBaseUrl`):

1. an explicit base URL wins — with a `WARN` at startup when `LLM_MODE=local` is set and
   the URL's host is not this machine;
2. else `LLM_MODE=local` selects the local Ollama endpoint, `http://localhost:11434/v1`;
3. else the model picks the provider: `gpt-*`, `o1*`, `o3*` go to `https://api.openai.com/v1`,
   any other model (`gemini-2.5-flash`, ...) to Gemini's OpenAI-compatible endpoint
   `https://generativelanguage.googleapis.com/v1beta/openai`.

`LLM_MODE` is read trimmed and case-insensitively (`Local`, ` local ` are local mode).

The startup line `Auto-building LC4j OpenAiStreamingChatModel model=… endpoint=… local=…`
names the endpoint in use. A `StreamingChatModel` bean of your own replaces this one.


### API keys over plain http

A key goes over plain `http` only to a loopback host (`localhost`, `127.0.0.0/8`, `::1`,
read from the URL alone, no DNS lookup): with a key and an `http` base URL on any other host the application fails to start, the error logged at `ERROR` naming the endpoint and the opt-in, unless the application sets
`atmosphere.ai.allow-cleartext-api-key=true` (environment variable
`ATMOSPHERE_AI_ALLOW_CLEARTEXT_API_KEY=true`), which logs a `WARN` instead. `https`
endpoints are unaffected, and a keyless call (local mode with no key) goes to any host. The
rule is shared by every runtime: see
[API keys over plain http](../ai/README.md#api-keys-over-plain-http).

### A stream that goes silent

LangChain4j's JDK HTTP client applies its `timeout` only until the response headers arrive:
a provider that stops sending in the middle of a stream is never timed out by it. The turn's
`StreamIdleWatchdog` ends such a turn once the model has sent nothing for
`atmosphere.ai.stream.idle-timeout` (env `LLM_STREAM_IDLE_TIMEOUT`, default `5m`, `0` turns
it off), with one `TimeoutException`, and cancels it; a caller's cancel does the same. Every
stream callback (text, thinking, partial and complete tool calls, raw events) is activity, and
the cancel aborts the stream through the `StreamingHandle` LangChain4j hands over with its
events. Tools and approvals between rounds do not count as idle time.

The `OpenAiStreamingChatModel` Atmosphere builds (`createNativeClient`, and the Spring
auto-configuration's bean) runs on an `AtmosphereLc4jHttpClient`, a decorator of LangChain4j's
`HttpClient` that wraps its four streaming entry points (`execute(request, listener)`,
`execute(request, parser, listener)`, `stream(request)`, `stream(request, parser)`). Each round
of a turn is sent with the turn bound to the calling thread for the synchronous `model.chat`
call only; the client reads it once, when the request enters it, and closes over it (the JDK
client parses the body on one of its own threads). So every byte the provider sends is activity,
SSE keep-alive comments included, and the response body is registered with the turn when the
headers arrive: the cancel closes it, before the stream's first event too. Proved on a loopback
socket by `LangChain4jRuntimeContractTest` (the TCK's stream idle cases, both dispatch modes) and
`LangChain4jHttpClientReleaseTest` (headers, then nothing). Residuals: on the handler path a
request that never gets its headers is not aborted (the JDK client keeps its `sendAsync` future
inside `JdkHttpClient`), and waits for LangChain4j's own HTTP timeout; a model the application
builds itself has no such client, so there only the stream's callbacks count and a stream that
handed over no `StreamingHandle` is left to the provider.

On the `AiServices` path (below) the turn registers the `TokenStream`'s `*WithContext` partial,
thinking and tool-call callbacks: each is activity and hands over the stream's
`StreamingHandle`, which the cancel uses (the `TokenStream` has no cancel of its own). A tool
`AiServices` runs itself runs inside a pause of the clock, from `beforeToolExecution` to
`onToolExecuted` (keyed by the tool request's id; any later stream callback closes it), so a slow
tool is not idle time (`LangChain4jAiServicesIdleTimeoutTest`). A `TokenStream` that does not
implement those callbacks gets the plain `onPartialResponse`: there only its text counts and the
call is not aborted. See
[Stream idle timeout](../ai/README.md#stream-idle-timeout).

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

## Observability — native `ChatModelListener`s

LangChain4j's own observability contract is the `ChatModelListener` SPI, and
modules like `langchain4j-opentelemetry` (`OpenTelemetryChatModelListener`) and
`langchain4j-micrometer-metrics` are implemented as listeners. They instrument
any model **at build time**.

- **You build the model** (`LangChain4jAgentRuntime.setModel(...)`): attach your
  listeners on the model builder as usual — they run unchanged.
- **The runtime auto-builds the model** (zero-config path from `AiConfig`):
  register listeners so they are attached to that model:

  ```java
  LangChain4jAgentRuntime.registerChatModelListener(
          OpenTelemetryChatModelListener.create(openTelemetry));
  ```

  Registrations apply to the next auto-build; an already-built model keeps the
  snapshot of listeners present at its build time.

This is orthogonal to Atmosphere's own observability: the framework still emits
the `gen_ai.*` / `atmosphere.ai.*` metrics (see
[atmosphere-ai metrics](../ai/README.md#metrics-recorded)) and fires
`AgentLifecycleListener` events for every runtime. The `ChatModelListener` path
adds LangChain4j-native instrumentation on top, so a `gen_ai`-aware backend sees
both Atmosphere's transport/pipeline spans and LangChain4j's model spans.

## Samples

- [Spring Boot AI Chat](../../samples/spring-boot-ai-chat/) -- swap to `atmosphere-langchain4j` dependency for LangChain4j support

## Full Documentation

See the [atmosphere-ai capability matrix](../ai/README.md#capability-matrix) and
<https://atmosphere.github.io/docs/reference/ai/> for the unified capability matrix
across all runtimes.

## Requirements

- Java 21+
- `atmosphere-ai` (transitive)
- LangChain4j 1.21.0+
