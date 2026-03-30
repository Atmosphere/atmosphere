# JetBrains Koog Adapter

`atmosphere-koog` bridges JetBrains Koog's `AIAgent` and `PromptExecutor` to Atmosphere's `AgentRuntime` SPI.

## How It Works

`KoogAgentRuntime` implements `AgentRuntime` directly (not `AbstractAgentRuntime`) and operates in two modes:

1. **Agent mode** (tools present): creates an `AIAgent` with `chatAgentStrategy()`, `toolRegistry`, and event handlers. The agent handles the tool loop automatically (max 5 rounds x 2 iterations).
2. **Executor mode** (no tools): streams directly via `PromptExecutor.executeStreaming()` with a Kotlin `Flow<StreamFrame>`.

Both modes use `runBlocking` to bridge Kotlin coroutines to the Atmosphere virtual thread model.

## Tool Calling

When `context.tools()` is non-empty, `AtmosphereToolBridge.buildRegistry()` converts `@AiTool` definitions to Koog's tool format. The `AIAgent` handles the tool calling loop via `chatAgentStrategy`, with events bridged:

| Koog Event | Atmosphere Event |
|-----------|-----------------|
| `onLLMStreamingFrameReceived` (TextDelta) | `AiEvent.TextDelta` |
| `onLLMStreamingFrameReceived` (ReasoningDelta) | `AiEvent.Progress` |
| `onToolCallStarting` | `AiEvent.ToolStart` |
| `onToolCallCompleted` | `AiEvent.ToolResult` |
| `onToolCallFailed` | `AiEvent.ToolError` |

## RAG Context Injection

Koog uses Atmosphere's `ContextProvider` SPI directly in `buildPrompt()`:

1. Calls `provider.transformQuery()` to rewrite the query
2. Retrieves documents via `provider.retrieve(query, 5)`
3. Reranks via `provider.rerank(query, docs)`
4. Appends to system prompt as "Use the following retrieved context..."

## Capabilities

```
TEXT_STREAMING, TOOL_CALLING, STRUCTURED_OUTPUT, AGENT_ORCHESTRATION, CONVERSATION_MEMORY, SYSTEM_PROMPT
```

## Per-Request Model Override

Supports per-request model override via `context.model()`. Creates a new `LLModel` with the same provider but different model ID.

## Configuration

Requires a `PromptExecutor` bean (via `koog-spring-boot-starter`) or manual setup:

```kotlin
KoogAgentRuntime.setPromptExecutor(executor)
KoogAgentRuntime.setDefaultModel(LLModel(LLMProvider.OpenAI, "gpt-4o"))
```

## Spring Auto-Configuration

`AtmosphereKoogAutoConfiguration` auto-detects `PromptExecutor` beans and wires them into the runtime.

## Dependency

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-koog</artifactId>
</dependency>
```
