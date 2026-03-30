# LangChain4j Adapter

`atmosphere-langchain4j` bridges LangChain4j's `StreamingChatModel` to Atmosphere's `AgentRuntime` SPI.

## How It Works

`LangChain4jAgentRuntime` extends `AbstractAgentRuntime<StreamingChatModel>` and:

1. Calls `assembleMessages(context)` to build the canonical message list
2. Maps each `ChatMessage` to LangChain4j's native types via `toLangChainMessage()`
3. Bridges `@AiTool` methods to `ToolSpecification` via `LangChain4jToolBridge`
4. Delegates to `ToolAwareStreamingResponseHandler` for the ReAct tool loop

## Tool Calling (ReAct Loop)

`ToolAwareStreamingResponseHandler` implements LangChain4j's `StreamingChatResponseHandler`:

- On `onCompleteResponse()`: checks if `aiMessage.hasToolExecutionRequests()`
- If yes: executes tools via `ToolExecutionHelper`, appends results, re-submits (max 5 rounds)
- If no: calls `session.complete()`

Each tool execution emits `AiEvent.ToolStart` and `AiEvent.ToolResult` events.

## Capabilities

```
TEXT_STREAMING, TOOL_CALLING, STRUCTURED_OUTPUT, SYSTEM_PROMPT
```

## Usage Metadata

Extracted from `ChatResponse.tokenUsage()` in `onCompleteResponse()`:

- `tokenUsage.inputTokenCount()` -> `ai.tokens.input`
- `tokenUsage.outputTokenCount()` -> `ai.tokens.output`
- `tokenUsage.totalTokenCount()` -> `ai.tokens.total`

## Error Handling

- `onPartialResponse()` and `onCompleteResponse()` check `session.isClosed()` before processing
- `onError()` calls `session.error(throwable)` only if the session is still open

## Spring Auto-Configuration

`AtmosphereLangChain4jAutoConfiguration` auto-detects `StreamingChatModel` beans and wires them into the runtime via `LangChain4jAgentRuntime.setModel()`.

## Dependency

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-langchain4j</artifactId>
</dependency>
```
