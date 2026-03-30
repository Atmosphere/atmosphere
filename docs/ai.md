# AgentRuntime SPI

`AgentRuntime` is the core abstraction for AI execution in Atmosphere. Implementations are discovered via `ServiceLoader`, filtered by `isAvailable()` (classpath probe), and the highest `priority()` wins. Switching runtimes is one dependency change.

## Implementations

| Adapter JAR | Implementation | Priority | Key Capability |
|-------------|---------------|----------|---------------|
| `atmosphere-ai` | `BuiltInAgentRuntime` | 0 | OpenAI-compatible, zero deps |
| `atmosphere-langchain4j` | `LangChain4jAgentRuntime` | 100 | ReAct tool loop |
| `atmosphere-spring-ai` | `SpringAiAgentRuntime` | 100 | Reactive streaming |
| `atmosphere-adk` | `AdkAgentRuntime` | 100 | Agent orchestration |
| `atmosphere-embabel` | `EmbabelAgentRuntime` | 100 | GOAP planning |
| `atmosphere-koog` | `KoogAgentRuntime` | 100 | Graph orchestration |

## Common Capabilities

All runtimes share a baseline:

- **TEXT_STREAMING** -- streamed text deltas via `StreamingSession.send()`
- **TOOL_CALLING** -- `@AiTool` methods work on all runtimes (except Embabel)
- **STRUCTURED_OUTPUT** -- `responseAs` on `@Agent`/`@AiEndpoint` triggers the pipeline's `StructuredOutputCapturingSession`
- **SYSTEM_PROMPT** -- skill files and system prompts forwarded to the model
- **Progress events** -- `session.progress("Connecting to <runtime>...")` emitted before every `doExecute()`
- **Usage metadata** -- `ai.tokens.input`, `ai.tokens.output`, `ai.tokens.total` reported when the underlying API provides it

## AbstractAgentRuntime

Template method base class for runtimes backed by a native client (`StreamingChatModel`, `ChatClient`, `Runner`, etc.).

```java
public abstract class AbstractAgentRuntime<C> implements AgentRuntime {
    protected abstract String nativeClientClassName();   // classpath probe
    protected abstract C createNativeClient(settings);   // lazy init
    protected abstract void doExecute(C client, ctx, session);

    // Shared helpers:
    protected static List<ChatMessage> assembleMessages(ctx);  // system + history + user
}
```

The `execute()` method handles lazy initialization, classpath detection, and emits the standard progress event before delegating to `doExecute()`.

## Built-in Tool Calling

The `BuiltInAgentRuntime` + `OpenAiCompatibleClient` supports the full OpenAI function calling protocol:

1. Tools from `AgentExecutionContext.tools()` are serialized as OpenAI `tools` array
2. SSE `delta.tool_calls` chunks are accumulated via `ToolCallAccumulator`
3. When `finish_reason=tool_calls`, tools are executed via `ToolExecutionHelper`
4. Results are appended as `role: tool` messages with `tool_call_id`
5. The conversation is re-submitted (max 5 rounds)

This means `@AiTool` works with zero framework dependencies -- just `atmosphere-ai`.

## Usage Metadata

All runtimes emit standardized metadata keys via `session.sendMetadata()`:

| Key | Source |
|-----|--------|
| `ai.tokens.input` | Prompt/input tokens |
| `ai.tokens.output` | Completion/output tokens |
| `ai.tokens.total` | Total tokens |
| `ai.model` | Model name used |

These feed into `MetricsCapturingSession` and `MicrometerAiMetrics` automatically.

## Configuration

Set via environment variables or `AiConfig.configure()`:

| Variable | Purpose |
|----------|---------|
| `LLM_API_KEY` | API key for the LLM provider |
| `LLM_MODEL` | Model name (e.g., `gemini-2.0-flash`) |
| `LLM_BASE_URL` | Custom endpoint URL |
| `LLM_MODE` | `remote` (default), `local` (Ollama), `fake` (testing) |

## Related Docs

- [agent.md](agent.md) -- `@Agent` annotation reference
- [langchain4j.md](langchain4j.md) -- LangChain4j adapter details
- [spring-ai.md](spring-ai.md) -- Spring AI adapter details
- [embabel.md](embabel.md) -- Embabel adapter details
