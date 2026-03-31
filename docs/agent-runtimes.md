# AgentRuntime — Capability Matrix

Write your `@Agent` once. The execution engine is determined by the classpath.

## Runtimes

| | Built-in | LangChain4j | Spring AI | Google ADK | Embabel | JetBrains Koog |
|-|----------|-------------|-----------|------------|---------|---------------|
| **Module** | `atmosphere-ai` | `atmosphere-langchain4j` | `atmosphere-spring-ai` | `atmosphere-adk` | `atmosphere-embabel` | `atmosphere-koog` |
| **Native client** | `OpenAiCompatibleClient` | `StreamingChatModel` | `ChatClient` | `Runner` | `AgentPlatform` | `PromptExecutor` |
| **Priority** | 0 (fallback) | 100 | 100 | 100 | 100 | 100 |
| **Language** | Java | Java | Java | Java | Kotlin | Kotlin |

## Abstracted Features

These features work identically across all runtimes. Write the annotation once — the framework bridges it to the native API.

| Feature | Built-in | LangChain4j | Spring AI | ADK | Embabel | Koog |
|---------|----------|-------------|-----------|-----|---------|------|
| **Text streaming** | SSE chunks | `StreamingChatResponseHandler` | `Flux<ChatResponse>` | `Flowable<Event>` | `OutputChannel` | `Flow<StreamFrame>` |
| **`@AiTool` calling** | OpenAI function calling (max 5 rounds) | ReAct loop via `ToolAwareStreamingResponseHandler` | Automatic via `ChatClient` | Automatic via `Runner` | -- | `AIAgent` with `chatAgentStrategy` |
| **Structured output** | `jsonMode(true)` + pipeline `StructuredOutputCapturingSession` | Pipeline-managed | Pipeline-managed | Pipeline-managed | Blackboard extraction | JSON schema in system prompt |
| **System prompt** | First message `role: system` | `SystemMessage.from()` | `.system()` on prompt builder | Agent `instruction` | `ProcessOptions` + context | Koog `prompt { system() }` |
| **Conversation memory** | Atmosphere `AiConversationMemory` | Atmosphere-managed | Atmosphere-managed | Native ADK sessions | Embabel blackboard | Koog agent state |
| **Progress events** | `"Connecting to built-in..."` | `"Connecting to langchain4j..."` | `"Connecting to spring-ai..."` | `"Connecting to google-adk..."` | `"Connecting to embabel..."` | `"Connecting to koog..."` |
| **Usage metadata** | From SSE `usage` object | `ChatResponse.tokenUsage()` | `response.getMetadata().getUsage()` | `event.usageMetadata()` (reflection, 0.9.0+) | -- | -- |
| **Per-request model** | Yes | Yes | Yes (`ChatOptions`) | -- | -- | Yes (`LLModel`) |
| **Error handling** | `session.error()` + `isClosed()` guard | `session.error()` + `isClosed()` guard | `session.error()` + `takeWhile(!closed)` | `session.error()` + `isClosed()` guard | `session.error()` + `isClosed()` guard | `session.error()` in catch |

## Capabilities Declared

Each runtime declares its capabilities via `AiCapability`. The framework uses these for model routing, tool negotiation, and feature discovery.

### Guaranteed by Core

These capabilities are available on **all** runtimes:

| Capability | Description |
|-----------|-------------|
| `TEXT_STREAMING` | Basic text streaming |
| `SYSTEM_PROMPT` | System prompt support |

### Runtime-Dependent

Available on most but not all runtimes:

| Capability | Built-in | LangChain4j | Spring AI | ADK | Embabel | Koog |
|-----------|----------|-------------|-----------|-----|---------|------|
| `TOOL_CALLING` | Y | Y | Y | Y | | Y |
| `STRUCTURED_OUTPUT` | Y | Y | Y | Y | Y | Y |
| `CONVERSATION_MEMORY` | | | | Y | | Y |

### Experimental

Capabilities that are runtime-specific or under active development:

| Capability | Built-in | LangChain4j | Spring AI | ADK | Embabel | Koog |
|-----------|----------|-------------|-----------|-----|---------|------|
| `AGENT_ORCHESTRATION` | | | | Y | Y | Y |
| `TOOL_APPROVAL` | | | | Y | | |
| `VISION` | | | | | | |
| `AUDIO` | | | | | | |

## Common Infrastructure (modules/ai)

These SPIs run in the Atmosphere pipeline, external to any runtime:

| SPI | Purpose |
|-----|---------|
| `AbstractAgentRuntime<C>` | Template method base: classpath detection, lazy init, `assembleMessages()`, progress events |
| `AgentExecutionContext` | Immutable record carrying message, tools, memory, history, model, RAG providers to the runtime |
| `StreamingSession` | Output contract: `send()`, `complete()`, `error()`, `sendMetadata()`, `emit(AiEvent)` |
| `ToolDefinition` / `ToolExecutor` | Framework-agnostic tool model scanned from `@AiTool` |
| `ToolExecutionHelper` | Shared execute-and-format logic used by all tool bridges |
| `ContextProvider` | RAG retrieval with `transformQuery()` and `rerank()` hooks |
| `AiGuardrail` | Pre/post inspection: `Pass`, `Modify`, `Block` |
| `AiConversationMemory` | Sliding-window history per conversation ID |
| `AiCompactionStrategy` | Pluggable history compaction: sliding window (default) or LLM summarization |
| `ArtifactStore` | Binary artifact persistence across agent runs (reports, images, code) |
| `RetryPolicy` | Exponential backoff with jitter (default: 3 retries, 1s base, 30s cap) |
| `ModelRouter` | Multi-backend routing: failover, round-robin, content-based, cost-based, latency-based |
| `StreamingTextBudgetManager` | Per-user token budgets with graceful degradation to fallback model |
| `AiMetrics` / `MicrometerAiMetrics` | Observability fed by `MetricsCapturingSession` |

## Metadata Keys

All runtimes emit these via `session.sendMetadata()` when the underlying API provides them:

| Key | Type | Source |
|-----|------|--------|
| `ai.tokens.input` | `int` | Prompt/input token count |
| `ai.tokens.output` | `int` | Completion/output token count |
| `ai.tokens.total` | `int` | Total token count |
| `ai.model` | `String` | Model name used for the request |

## Orchestration Primitives

These features work across all runtimes — they operate at the pipeline level, not inside the runtime.

| Feature | SPI | Default Behavior |
|---------|-----|-----------------|
| **Agent Handoffs** | `StreamingSession.handoff()` | Transparent routing through target agent's `@Prompt` handler |
| **Approval Gates** | `@RequiresApproval` + `ApprovalGateExecutor` | Parks VT on `CompletableFuture`, `/__approval/` prefix protocol |
| **Conditional Routing** | `AgentFleet.route()` + `RoutingSpec` | First-match evaluation, recorded in `CoordinationJournal` |
| **Long-Term Memory** | `LongTermMemoryInterceptor` + `MemoryExtractionStrategy` | Fact extraction on session close, injected into system prompt |
| **Eval Assertions** | `LlmJudge` + `AiAssertions` | `meetsIntent()`, `isGroundedIn()`, `hasQuality()` with configurable judge runtime |
