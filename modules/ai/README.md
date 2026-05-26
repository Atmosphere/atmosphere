# Atmosphere AI

AI/LLM streaming module for Atmosphere. Provides `@AiEndpoint`, `@Prompt`, `@AiTool`, `StreamingSession`, the `AgentRuntime` SPI for auto-detected AI framework adapters, and a built-in `OpenAiCompatibleClient` that works with Gemini, OpenAI, Ollama, and any OpenAI-compatible API — including tool calling, structured output, and usage metadata tracking.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai</artifactId>
    <version>${project.version}</version>
</dependency>
```

## Minimal Example

```java
@AiEndpoint(path = "/atmosphere/ai-chat",
            systemPrompt = "You are a helpful assistant.")
public class MyAiChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // auto-detects AI framework from classpath
    }
}
```

The `@AiEndpoint` annotation replaces the boilerplate of `@ManagedService` + `@Ready` + `@Disconnect` + `@Message` for AI streaming use cases. The `@Prompt` method runs on a virtual thread, so blocking LLM API calls do not block Atmosphere's thread pool.

`session.stream(message)` auto-detects the best available `AgentRuntime` implementation via `ServiceLoader` — drop an adapter JAR on the classpath and it just works, analogous to `AsyncSupport` for transports.

## AgentRuntime SPI

The `AgentRuntime` interface is the AI-layer equivalent of `AsyncSupport`. Implementations are discovered via `ServiceLoader`, filtered by `isAvailable()`, and the highest `priority()` wins.

| Adapter JAR | `AgentRuntime` implementation | Priority | Capabilities |
|-------------|-------------------------------|----------|-------------|
| `atmosphere-ai` (built-in) | `BuiltInAgentRuntime` (OpenAI-compatible) | 0 | TEXT_STREAMING, TOOL_CALLING, STRUCTURED_OUTPUT, SYSTEM_PROMPT, TOOL_APPROVAL, VISION, AUDIO, MULTI_MODAL, PROMPT_CACHING, PER_REQUEST_RETRY, TOKEN_USAGE, CONVERSATION_MEMORY, TOOL_CALL_DELTA, BUDGET_ENFORCEMENT, CONFIDENCE_SCORES, PASSIVATION |
| `atmosphere-spring-ai` | `SpringAiAgentRuntime` | 100 | TEXT_STREAMING, TOOL_CALLING, STRUCTURED_OUTPUT, SYSTEM_PROMPT, TOOL_APPROVAL, VISION, AUDIO, MULTI_MODAL, PROMPT_CACHING, TOKEN_USAGE, CONVERSATION_MEMORY, PER_REQUEST_RETRY, BUDGET_ENFORCEMENT, CONFIDENCE_SCORES, PASSIVATION |
| `atmosphere-langchain4j` | `LangChain4jAgentRuntime` | 100 | TEXT_STREAMING, TOOL_CALLING, STRUCTURED_OUTPUT, SYSTEM_PROMPT, TOOL_APPROVAL, VISION, AUDIO, MULTI_MODAL, PROMPT_CACHING, TOKEN_USAGE, CONVERSATION_MEMORY, PER_REQUEST_RETRY, BUDGET_ENFORCEMENT, CONFIDENCE_SCORES, PASSIVATION |
| `atmosphere-adk` | `AdkAgentRuntime` | 100 | TEXT_STREAMING, TOOL_CALLING, STRUCTURED_OUTPUT, AGENT_ORCHESTRATION, CONVERSATION_MEMORY, SYSTEM_PROMPT, TOOL_APPROVAL, VISION, AUDIO, MULTI_MODAL, TOKEN_USAGE, PER_REQUEST_RETRY, PROMPT_CACHING, BUDGET_ENFORCEMENT, CONFIDENCE_SCORES, PASSIVATION |
| `atmosphere-embabel` | `EmbabelAgentRuntime` | 100 | TEXT_STREAMING, STRUCTURED_OUTPUT, AGENT_ORCHESTRATION, SYSTEM_PROMPT, CONVERSATION_MEMORY, TOKEN_USAGE, PER_REQUEST_RETRY, TOOL_CALLING, TOOL_APPROVAL, VISION, MULTI_MODAL, BUDGET_ENFORCEMENT, CONFIDENCE_SCORES, PASSIVATION |
| `atmosphere-koog` | `KoogAgentRuntime` | 100 | TEXT_STREAMING, TOOL_CALLING, STRUCTURED_OUTPUT, AGENT_ORCHESTRATION, CONVERSATION_MEMORY, SYSTEM_PROMPT, TOOL_APPROVAL, TOKEN_USAGE, VISION, AUDIO, MULTI_MODAL, PROMPT_CACHING, CANCELLATION, PER_REQUEST_RETRY, BUDGET_ENFORCEMENT, CONFIDENCE_SCORES, PASSIVATION |
| `atmosphere-agentscope` | `AgentScopeAgentRuntime` | 100 | TEXT_STREAMING, SYSTEM_PROMPT, STRUCTURED_OUTPUT, CONVERSATION_MEMORY, TOKEN_USAGE, TOOL_CALLING, TOOL_APPROVAL, CANCELLATION, PER_REQUEST_RETRY, BUDGET_ENFORCEMENT, CONFIDENCE_SCORES, PASSIVATION |
| `atmosphere-spring-ai-alibaba` | `SpringAiAlibabaAgentRuntime` | 100 | TEXT_STREAMING (buffered), SYSTEM_PROMPT, STRUCTURED_OUTPUT, CONVERSATION_MEMORY, TOOL_CALLING, TOOL_APPROVAL, TOKEN_USAGE, PER_REQUEST_RETRY, BUDGET_ENFORCEMENT, CONFIDENCE_SCORES, PASSIVATION *(see runtime caveats below)* |
| `atmosphere-semantic-kernel` | `SemanticKernelAgentRuntime` | 100 | TEXT_STREAMING, SYSTEM_PROMPT, STRUCTURED_OUTPUT, CONVERSATION_MEMORY, TOKEN_USAGE, TOOL_CALLING, TOOL_APPROVAL, PER_REQUEST_RETRY, BUDGET_ENFORCEMENT, CONFIDENCE_SCORES, PASSIVATION |

Every runtime emits `TokenUsage` via `StreamingSession.usage()` when the underlying API provides token counts, feeding `ai.tokens.*` metadata into `MetricsCapturingSession` and `MicrometerAiMetrics`. Capability declarations are pinned in each runtime's contract test (`AbstractAgentRuntimeContractTest.expectedCapabilities()`), so the table above cannot drift from the running code without breaking the build. The aggregate counts ("12 runtimes") and the per-row capability lists are additionally pinned against `.harness/capabilities.snapshot.json` by `CapabilitySnapshotTest` and `scripts/validate-capability-claims.sh` (run from pre-push), so prose claims about the matrix break the build alongside code drift.

Each runtime additionally ships a portable signed manifest at `modules/<X>/SKILLCARD.yaml` (and `SKILLCARD.yaml.sig` after a tagged release). `scripts/regen-skillcards.sh` emits the YAML from the snapshot + module `pom.xml`; `.github/workflows/sign-skillcards.yml` signs every card on tag push via OpenSSF Model Signing (Sigstore keyless OIDC — short-lived Fulcio cert + Rekor transparency-log entry, OIDC identity bound to the workflow path). Both the card and its `.sig` bundle are packaged into each runtime jar at `META-INF/atmosphere/` so a downstream consumer can verify integrity without unpacking the source tree. `SkillCardSnapshotTest` enforces drift detection, shape conformance, and signature verification when a `.sig` is present; verify locally with `./scripts/verify-skillcards.sh --identity https://github.com/Atmosphere/atmosphere/.github/workflows/sign-skillcards.yml@refs/tags/<TAG> --identity-provider https://token.actions.githubusercontent.com`. Cards on `main` between releases are unsigned by design — the workflow runs at tag time.

#### What capability flags do *not* claim

Capability flags advertise a coarse contract: "this runtime cooperates with the named pipeline feature." They deliberately do **not** assert:

- **Implementation parity across runtimes.** Two runtimes that both declare `TOOL_CALLING` may differ on tool-call timeout handling, parallel-tool-call dispatch, max iteration default, and partial-result behaviour on failure. The flag covers the SPI contract, not byte-for-byte behavioural identity.
- **Limit numbers.** `PER_REQUEST_RETRY` (only the Built-in runtime threads the policy into its HTTP client; framework runtimes inherit native retry layers) is the canonical example — the per-row footnotes above carry the specifics.
- **Provider-side guarantees.** `PROMPT_CACHING` means the runtime forwards Anthropic / OpenAI / Gemini cache hints; it does not promise the upstream provider honored them.
- **Production fitness for any specific workload.** A capability flag is necessary but not sufficient — sample apps, integration tests, and your own load tests are the only signals for fitness.

If a per-runtime cell needs a caveat, document it in the row footnote (Spring AI Alibaba already does for buffered streaming) rather than weakening the capability semantics across all rows.

**Spring AI Alibaba runtime — Spring Boot 3 only today.** Spring AI Alibaba `1.1.2.0` is compiled against Spring AI `1.1.2` and `spring-ai-alibaba-graph-core-1.1.2.0` hardcodes references to Spring AI 1.1.2-only types (e.g. `org.springframework.ai.deepseek.DeepSeekAssistantMessage`), so the runtime requires Spring AI 1.1.2 on the classpath. Spring AI 1.1.2 in turn requires Spring Boot 3 (it references the SB3-era FQN `org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration`; Spring Boot 4 has the same class but at the renamed FQN `org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration`). Net result: `atmosphere-spring-ai-alibaba` runs end-to-end on Spring Boot 3 today (verified via chrome-devtools through the bundled Console against Ollama, qwen2.5:0.5b round-trip succeeded); a Spring Boot 4 path will become possible once Alibaba publishes a Spring AI 2.x-aligned `spring-ai-alibaba-agent-framework`. Forcing Spring AI 2.0.0-M6 across the classpath today fails at `ReactAgent` construction with `NoClassDefFoundError`. AgentScope (`atmosphere-agentscope`) is unaffected — it builds its OpenAI-compatible client through `AiConfig` directly and is validated end-to-end on Spring Boot 4.

### AiInterceptor

Cross-cutting concerns go through `AiInterceptor`, not subclassing. Interceptors are declared on `@AiEndpoint` and executed in FIFO order for `preProcess`, LIFO for `postProcess` (matching the `AtmosphereInterceptor` convention):

```java
@AiEndpoint(path = "/ai/chat",
            interceptors = {RagInterceptor.class, GuardrailInterceptor.class})
public class MyChat { ... }

public class RagInterceptor implements AiInterceptor {
    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        String context = vectorStore.search(request.message());
        return request.withMessage(context + "\n\n" + request.message());
    }
}
```

## Conversation Memory

Enable multi-turn conversations with one annotation attribute:

```java
@AiEndpoint(path = "/ai/chat",
            systemPrompt = "You are a helpful assistant",
            conversationMemory = true,
            maxHistoryMessages = 20)
public class MyChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // AiRequest now carries conversation history
    }
}
```

When `conversationMemory = true`, the framework:

1. Captures each user message and the streamed assistant response (via `MemoryCapturingSession`)
2. Stores them as conversation turns per `AtmosphereResource`
3. Injects the full history into every subsequent `AiRequest`
4. Clears the history when the resource disconnects

The default implementation is `InMemoryConversationMemory`, which caps history at `maxHistoryMessages` (default 20). For external storage — Redis, a database, etc. — implement the `AiConversationMemory` SPI:

```java
public interface AiConversationMemory {
    List<ChatMessage> getHistory(String conversationId);
    void addMessage(String conversationId, ChatMessage message);
    void clear(String conversationId);
    int maxMessages();
}
```

## Distributed Memory & Stream Replication

Conversation memory (above) is per-process by default. When the same conversation id can land on different pods between turns — load-balanced WebSocket reconnects, blue/green deploys, autoscaling fan-out — every pod must see the same history or the model loses context mid-conversation.

Atmosphere ships two primitives that close the gap without dragging in a new SPI:

### 1. `streamCache = UUIDBroadcasterCache.class`

```java
@AiEndpoint(path = "/ai/chat",
            streamCache = UUIDBroadcasterCache.class)
public class ResilientChat {
    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

When a mobile client drops mid-stream, `UUIDBroadcasterCache` buffers the cached frames keyed by client UUID. On reconnect with the same UUID (or via the `X-Atmosphere-Run-Id` header `DurableSessionInterceptor` reads), the un-acked frames replay so the client catches up on what it missed. The matching JS hook is `onReconnect` — see the sample wiring under `samples/spring-boot-ai-chat/frontend/`.

### 2. `ClusterBroadcastFilter` for cross-pod memory replication

`ClusterBroadcastFilter` is the SPI Atmosphere has shipped for cluster-wide broadcast replication since 2.x — it fits cleanly under `@AiEndpoint(filters = …)`. The filter sees every broadcast that flows through the endpoint's broadcaster, including the `MemoryCapturingSession`'s memory-update side-effects, so wiring it for AI is purely additive.

```java
public final class RedisMemoryReplicationFilter
        implements ClusterBroadcastFilter {

    private Broadcaster broadcaster;
    private RedisClient redis;

    @Override public void init() {
        // Subscribe to memory-update events from peers and replay locally.
        redis.subscribe("ai:memory:" + broadcaster.getID(), this::onPeerEvent);
    }

    @Override public BroadcastAction filter(String broadcasterId,
                                            AtmosphereResource r,
                                            Object originalMessage,
                                            Object message) {
        // Outbound: publish to peers (only memory-shaped messages).
        if (message instanceof MemoryEvent event) {
            redis.publish("ai:memory:" + broadcasterId, serialize(event));
        }
        return new BroadcastAction(message);
    }

    private void onPeerEvent(byte[] payload) {
        // Inbound: hand off to the local conversation memory.
        var event = deserialize(payload);
        AiConversationMemoryHolder.get().addMessage(event.conversationId(), event.message());
    }

    @Override public void setUri(String uri) { this.redis = RedisClient.create(uri); }
    @Override public void setBroadcaster(Broadcaster bc) { this.broadcaster = bc; }
    @Override public Broadcaster getBroadcaster() { return broadcaster; }
    @Override public void destroy() { redis.close(); }
}

@AiEndpoint(path = "/ai/chat",
            conversationMemory = true,
            filters = {RedisMemoryReplicationFilter.class})
public class ClusteredChat { ... }
```

The same shape works with Hazelcast, JGroups, NATS — anything that lets you fan a tiny event out to peers. Pair this with a clustered `AiConversationMemory` SPI (Redis / Postgres-backed implementations are a single class each) and a sticky-session-free deployment is feasible.

## Key Components

| Class | Description |
|-------|-------------|
| `@AiEndpoint` | Marks a class as an AI chat endpoint with a path, system prompt, and interceptors |
| `@Prompt` | Marks the method that handles user messages |
| `AgentRuntime` | SPI for AI framework backends (ServiceLoader-discovered) |
| `AiRequest` | Framework-agnostic request record (message, systemPrompt, model, hints) |
| `AiInterceptor` | Pre/post processing hooks for RAG, guardrails, logging |
| `AiConversationMemory` | SPI for conversation history storage |
| `InMemoryConversationMemory` | Default in-process memory (capped at `maxHistoryMessages`) |
| `MemoryCapturingSession` | `StreamingSession` decorator that records assistant responses into memory |
| `AiStreamingSession` | `StreamingSession` wrapper that adds `stream(String)` with interceptor chain |
| `StreamingSession` | Delivers streaming texts, progress updates, and metadata to the client |
| `StreamingSessions` | Factory for creating `StreamingSession` instances |
| `OpenAiCompatibleClient` | Built-in HTTP client for OpenAI-compatible APIs (JDK HttpClient, no extra deps) |
| `AiConfig` | Configuration via environment variables or init-params |
| `ChatCompletionRequest` | Builder for chat completion requests |
| `RoutingLlmClient` | Routes prompts to different LLM backends based on content, model, cost, or latency rules |
| `AiResponseCacheListener` | Tracks cached streaming texts per session; supports coalesced aggregate events |
| `MicrometerAiMetrics` | `AiMetrics` implementation backed by Micrometer (counters, timers, gauges) |
| `TracingCapturingSession` | `StreamingSession` decorator that captures timing and reports to `AiMetrics` |

## Configuration

Set environment variables or use Atmosphere init-params:

```bash
# Gemini (default)
export LLM_MODE=remote
export LLM_MODEL=gemini-2.5-flash
export LLM_API_KEY=AIza...

# OpenAI
export LLM_MODEL=gpt-4o-mini
export LLM_BASE_URL=https://api.openai.com/v1
export LLM_API_KEY=sk-...

# Ollama (local)
export LLM_MODE=local
export LLM_MODEL=llama3.2
```

## Tool Loop Policy

When the model emits tool-call requests, the runtime runs an iterative
model→tool→model loop until the model produces a final text response. By
default the loop is capped at **5 iterations** and on overflow the response
is completed with whatever text was emitted last (a warning is logged).

`ToolLoopPolicy` exposes both knobs per request:

```java
// Strict: fail on overflow so callers see the cap was hit instead of
// receiving a silently-truncated stream.
var ctx = ToolLoopPolicies.attach(baseContext, ToolLoopPolicy.strict(3));

// Lenient: raise the cap, keep complete-without-tools overflow.
var ctx = ToolLoopPolicies.attach(baseContext, ToolLoopPolicy.maxIterations(10));

// Or via an interceptor so every request inherits the same policy:
@Component
class ToolLoopInterceptor implements AiInterceptor {
    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        return request.withMetadata(Map.of(
                ToolLoopPolicies.METADATA_KEY, ToolLoopPolicy.strict(3)));
    }
}
```

When the cap is hit with `OnMaxIterations.FAIL`, the runtime calls
`session.error(...)` with a `ToolLoopExhaustedException` carrying the cap
value; the wire protocol surfaces this as a normal error frame
(`{"type":"error","message":"Tool loop exhausted after 3 iterations"}`).

### Per-runtime adoption

Every runtime honors `ToolLoopPolicy.strict(N)` through the cross-runtime
`ToolLoopGuard` — a per-execute `AgentLifecycleListener` that counts
`onModelStart` events and calls `session.error(ToolLoopExhaustedException)`
once the cap is exceeded. The guard is installed by `AbstractAgentRuntime.execute`
(automatic for Java runtimes) and by `KoogAgentRuntime` / `EmbabelAgentRuntime`
explicitly (they implement `AgentRuntime` directly to keep their Kotlin shape).
Beyond that wire-level hard cap, runtimes layer on native upstream
enforcement when the upstream library exposes a mappable knob.

| Runtime | `strict(N)` (FAIL) | `maxIterations(N)` (COMPLETE_WITHOUT_TOOLS) | Native upstream knob |
|---------|---------------------|---------------------------------------------|----------------------|
| Built-in (`BuiltInAgentRuntime`) | ✓ native | ✓ native | `OpenAiCompatibleClient.doStreamWithToolLoop` reads `ChatCompletionRequest.toolLoopPolicy()` every iteration |
| Koog (`KoogAgentRuntime`) | ✓ native + guard | ✓ native | `executeWithAgent` reads `ToolLoopPolicies.fromOrDefault(ctx)` and translates to `AIAgent.maxIterations × 2` (Koog counts LLM rounds + tool steps) |
| LangChain4j (`LangChain4jAgentRuntime`) | ✓ via guard | hint via sidecar (caller-controlled) | When the caller routes through the `LangChain4jAiServices` sidecar, the cap lives on `AiServices.builder().maxSequentialToolsInvocations(int).build()` (set once at construction) |
| Spring AI (`SpringAiAgentRuntime`) | ✓ via guard | hint only — falls through to Spring AI default | Spring AI 2.0.0-M6 exposes `OpenAiChatModel.Builder.toolExecutionEligibilityPredicate(...)` at ChatModel construction time (not per-request); per-request wiring would require Atmosphere to wrap user-supplied ChatModels |
| Spring AI Alibaba | ✓ via guard | hint only | Inherits Spring AI's lack of per-request knob. `ReactAgent` and Alibaba's `RunnableConfig` do not expose an iteration cap |
| ADK (`AdkAgentRuntime`) | ✓ via guard | hint only — native wiring planned | ADK 1.0.0 ships `LlmAgent.Builder.maxSteps(int)` at agent construction. Native per-request wiring (rebuild leaf `LlmAgent` per request, or counting `BeforeModelCallback` reading session state) is tractable but not yet implemented |
| Semantic Kernel (`SemanticKernelAgentRuntime`) | ✓ via guard | hint only | SK 1.4.0 `ToolCallBehavior.getMaximumAutoInvokeAttempts()` is a getter only; the constructor and `allowAllKernelFunctions(...)` factory chain do not accept a max-attempts integer. Subclassing requires reflection on package-private fields |
| AgentScope (`AgentScopeAgentRuntime`) | ✓ via guard | hint only — native wiring planned | AgentScope 1.0.12 ships `ReActAgent.Builder.maxIters(int)` at agent construction. Native per-request wiring (rebuild via builder when policy attached) is tractable but not yet implemented |
| Embabel (`EmbabelAgentRuntime`) | ✓ via guard | hint only | Embabel 0.3.4 `PromptRunner` does not expose a per-request iteration knob. Embabel 0.3.5 adds `withToolLoopInspectors` + `ToolLoopInspector.afterIteration(AfterIterationContext)` which would translate a policy directly to `MaxIterationsExceededException`; native wiring is staged for the 0.3.5 upgrade |

**Honest distinction.** `strict(N)` is honored on every runtime via a hard
wire-level abort: when `onModelStart` count exceeds `N`, the guard calls
`session.error(...)` and the session enters its closed state — subsequent
output from the runtime is dropped at the wire. The runtime may continue
spinning upstream (the guard cannot reach into Spring AI's `ChatModel` or
Embabel's planner to abort their internal flow), but the user-observable
result is a hard cap.

`COMPLETE_WITHOUT_TOOLS` ("stop tool calling but keep running, complete
with whatever text you have") can only be synthesized from inside the
tool loop. Built-in and Koog do this natively; on the other seven runtimes
the upstream's own default cap takes over and the policy serves as a
hint. Use `ToolLoopPolicy.strict(N)` for safety-critical hard caps (works
everywhere); reserve `ToolLoopPolicy.maxIterations(N)` for runtimes that
honor it natively.

`ToolLoopPolicies.from(context)` is the contract: runtimes that gain a
per-request knob in a future framework release upgrade from "via guard"
to "✓ native". Tracking issues live in each module's README.

## Per-Request Retry Architecture

Every runtime that advertises `PER_REQUEST_RETRY` honors
`AgentExecutionContext.retryPolicy()` — but via two different
implementations chosen by where the runtime sits in the dispatch tree:

| Runtime | Where retry happens | Hook |
|---------|---------------------|------|
| Built-in (`BuiltInAgentRuntime`) | HTTP layer — `OpenAiCompatibleClient.sendWithRetry` retries each transport request individually | `ownsPerRequestRetry()` returns `true`; outer wrapper skipped |
| Spring AI / LangChain4j / ADK / Semantic Kernel | Bridge wrapper — `AbstractAgentRuntime.executeWithOuterRetry` retries the whole `doExecute(...)` call on pre-stream `RuntimeException` | Inherits `ownsPerRequestRetry() = false` (default) |
| Koog / Embabel | Same bridge-wrapper semantics, but implemented privately because they implement `AgentRuntime` directly (not `AbstractAgentRuntime`) — Kotlin idiom + per-request agent construction | Private `executeWithOuterRetry(...)` mirroring the Java base class |

**Safety invariant (all three implementations):** retry is only attempted
when `session.hasErrored() == false` — i.e., the bridge threw a
`RuntimeException` *before* calling `StreamingSession.error(...)`, so the
client has not seen any terminal frame and a retry is observably-safe.
Once the session reports an error out-of-band (Spring AI reactive,
ADK async callbacks, LC4j `onError`, Koog error frames), retry is aborted
and the exception propagates because the caller has already observed
terminal state. Matches Correctness Invariant #2 (Terminal Path
Completeness): we never re-emit after an out-of-band terminal.

**Why two implementations not one:** Built-in's HTTP-level retry can
retry mid-stream when the SSE transport drops between bytes, because it
controls the connection. Framework runtimes can only retry whole bridge
calls, because they hand the prompt to a third-party client that owns
the connection. The two-tier model gives Built-in tighter retry without
forcing framework runtimes to lie about that capability.

**All 12 runtimes claim `PER_REQUEST_RETRY` honestly.** Earlier capability
sets for `AgentScope` and `Spring AI Alibaba` omitted the flag, even
though both extend `AbstractAgentRuntime` and inherit
`executeWithOuterRetry` for free — that was an under-claim corrected so
`runtime.capabilities()` matches the runtime's actual behavior
(Correctness Invariant #5). The Anthropic runtime added in
4.0.47-SNAPSHOT and the `CrewAiAgentRuntime` added alongside the
Python sidecar bridge both follow the same posture.

## Per-Request Sidecar Bridges

The `AgentRuntime` SPI keeps the unified surface narrow on purpose —
`message`, `systemPrompt`, `model`, `tools`, `history`, `metadata`,
nothing framework-specific. To let advanced callers reach
framework-native knobs without growing the SPI, every framework runtime
exposes a **sidecar bridge**: a small static helper that reads/writes a
canonical key in `context.metadata()`. The runtime checks for the slot
on every call; when present it dispatches against the user-supplied
override, otherwise it falls back to the runtime's default wiring.

| Runtime | Sidecar | What it overrides | Metadata key |
|---------|---------|-------------------|--------------|
| `SpringAiAgentRuntime` | `SpringAiAdvisors` | List of Spring AI `Advisor`s passed to `promptSpec.advisors(...)` (RAG retrievers, guardrails, observability) | `spring-ai.advisors` |
| `LangChain4jAgentRuntime` | `LangChain4jAiServices` | Caller-built `AiServices` proxy — entire dispatch routes through the proxy's typed interface (gives access to `maxSequentialToolsInvocations`, custom system message provider, etc.) | `langchain4j.aiservice` |
| `AdkAgentRuntime` | `AdkRootAgent` | Per-request `BaseAgent` root — overrides the configured root agent so a single runtime can dispatch against multiple ADK agent topologies | `adk.rootAgent` |
| `KoogAgentRuntime` | `KoogStrategy` | Per-request `AIAgentStrategy` — switch between `chatAgentStrategy()`, `singleRunStrategy()`, or a fully custom graph strategy without re-installing the runtime | `koog.strategy` |
| `SemanticKernelAgentRuntime` | `SemanticKernelInvocation` | Per-request `InvocationContext` — unlocks `KernelHooks`, `withMaxAutoInvokeAttempts`, custom `PromptExecutionSettings` that the runtime's default builder doesn't expose | `semantic-kernel.invocationContext` |
| `EmbabelAgentRuntime` | `EmbabelPromptRunner` | `UnaryOperator<PromptRunner>` customizer applied AFTER the runtime's default wiring (system-prompt+history+tools+images already installed) — stack `withTemperature` / `withModel` / `withGuardrails` on top. Atmosphere-native dispatch path only; the deployed-`@Agent` path bypasses `PromptRunner` | `embabel.promptRunner` |
| `AgentScopeAgentRuntime` | `AgentScopeAgent` | Per-request `ReActAgent` — useful when different prompts route through different agent topologies (planner vs. quick lookup) without re-installing the runtime client | `agentscope.agent` |
| `SpringAiAlibabaAgentRuntime` | `SpringAiAlibabaRunnableConfig` | Per-request `RunnableConfig` — Alibaba's natural per-invocation handle for `threadId` (memory thread continuation), `checkPointId` (resume), `streamMode`, metadata, and store. Runtime dispatches via `agent.call(messages, config)` when attached, no-arg overload otherwise | `spring-ai-alibaba.runnableConfig` |

**Built-in runtime has no sidecar** — it talks raw HTTP to an
OpenAI-compatible endpoint, so every per-request knob is already
expressible through the unified SPI (`metadata` for cache hints, etc.).

**Canonical sidecar contract** (every helper above conforms):

- `from(context)` returns `null` (or empty list, where appropriate)
  when the slot is absent — never throws on missing data.
- `from(context)` throws `IllegalArgumentException` when the slot is
  present but the wrong type — silent drops would mask the override
  never firing, which is exactly the class of bug `SpringAiAdvisors`
  shipped to fix originally.
- `attach(context, override)` returns a new `AgentExecutionContext`
  with the override stored under the canonical key, preserving every
  other metadata entry. Most bridges are exclusive (one override per
  request); `SpringAiAdvisors` is the exception (additive — multiple
  advisors compose into a chain).
- All sidecar types live inside their own runtime module, so
  `modules/ai` carries zero framework-specific dependencies.

Each bridge has a dedicated `*BridgeTest` (or `*BridgeTest.kt`) pinning
all six guarantees: missing slot, wrong-type slot, attach round-trip,
attach replaces, attach preserves unrelated entries, null-arg guards.

## Model-Lifecycle Observation

`AgentLifecycleListener` exposes three model-lifecycle hooks so observability
consumers (Micrometer recorders, audit appenders, structured-log writers) get
a uniform per-call event surface regardless of which `AgentRuntime` ran the
request:

| Hook | Fired by the runtime when... |
|------|------------------------------|
| `onModelStart(model, messageCount, toolCount)` | a model dispatch is about to happen |
| `onModelEnd(model, usage, durationMillis)` | the streaming response has been fully consumed |
| `onModelError(model, error)` | a transport-layer or provider-layer dispatch failure occurred |

The **Built-in runtime** wires all three today: `OpenAiCompatibleClient`
fires `onModelStart` before each `sendWithRetry`, `onModelEnd` once the SSE
stream is consumed (carrying the captured `TokenUsage`), and `onModelError`
when the retry budget is exhausted or a transport exception escapes.

Framework runtimes (LangChain4j, Spring AI, ADK, Koog, Embabel) inherit
their own native observability surfaces (Spring AI `ChatClientObservation`,
LC4j `ChatModelListener`, ADK `BeforeModelCallback`/`AfterModelCallback`,
Koog `PromptExecutorInterceptor`, Embabel `AgentListener`) and should
bridge into these hooks from their adapter module — see the per-module
README for the cross-runtime adoption status. Same posture as
`ToolLoopPolicy`: Built-in honors it natively, and the SPI is in place
for framework-runtime authors to wire up their bridges. (Distinct from
`PER_REQUEST_RETRY`, which all 7 claimants honor today via two distinct
implementations — see "Per-Request Retry Architecture" below.)

`AiEventForwardingListener` is a built-in adapter that translates these
hooks into `AiEvent.Progress` frames on the streaming session, so browser
clients receive uniform observability events on the wire:

```java
var session = StreamingSessions.start("chat", resource);
var listeners = List.of(new AiEventForwardingListener(session));
runtime.execute(context.withListeners(listeners), session);
// → browser receives:
//   {"type":"progress","message":"model:start (gpt-4o, msgs=3, tools=2)"}
//   {"type":"progress","message":"model:end (gpt-4o, in=120, out=85, ms=842)"}
```

## StreamingSession Wire Protocol

The client receives JSON messages over WebSocket/SSE:

- `{"type":"streaming-text","content":"Hello"}` -- a single streaming text
- `{"type":"progress","message":"Thinking..."}` -- status update
- `{"type":"complete"}` -- stream finished
- `{"type":"error","message":"..."}` -- stream failed

## Cache Listener Coalescing

The `AiResponseCacheListener` fires per-streaming-text by default, which can be noisy under load. Coalesced listeners fire **once per session** when it completes or errors, providing aggregate metrics.

```java
var listener = new AiResponseCacheListener();
listener.addCoalescedListener(event -> {
    log.info("Session {} finished: {} streaming texts in {}ms (status: {})",
            event.sessionId(), event.totalStreamingTexts(),
            event.elapsedMs(), event.status());
});
broadcaster.getBroadcasterConfig()
        .getBroadcasterCache()
        .addBroadcasterCacheListener(listener);
```

| Class | Description |
|-------|-------------|
| `CoalescedCacheEvent` | Record: `sessionId`, `broadcasterId`, `totalStreamingTexts`, `status`, `elapsedMs` |
| `CoalescedCacheEventListener` | `@FunctionalInterface` — receives one event per completed session |

Per-streaming-text tracking is unchanged; coalesced events are purely additive. Listener exceptions are isolated — a failing listener does not prevent others from firing.

## Observability with Micrometer

`MicrometerAiMetrics` provides production-grade observability by implementing the `AiMetrics` SPI with [Micrometer](https://micrometer.io). Add `micrometer-core` to your classpath (it's an optional/provided dependency):

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
</dependency>
```

Wire it up:

```java
var metrics = new MicrometerAiMetrics(meterRegistry, "spring-ai");
```

### Metrics Recorded

| Metric | Type | Description |
|--------|------|-------------|
| `atmosphere.ai.prompts.total` | Counter | Total prompt requests |
| `atmosphere.ai.streaming_texts.total` | Counter | Total streaming text chunks |
| `atmosphere.ai.errors.total` | Counter | Errors by type (`timeout`, `rate_limit`, `server_error`, `unknown`) |
| `atmosphere.ai.prompt.duration` | Timer | Time from prompt to first streaming text (TTFT) |
| `atmosphere.ai.response.duration` | Timer | Full response wall-clock time |
| `atmosphere.ai.tool.duration` | Timer | Tool call execution time |
| `atmosphere.ai.active_sessions` | Gauge | Currently active streaming sessions |
| `atmosphere.ai.cost` | Summary | Cost per request |

All metrics are tagged with `model` and `provider`.

### TracingCapturingSession

`TracingCapturingSession` is a `StreamingSession` decorator that automatically captures timing and reports to any `AiMetrics` implementation:

- **Time to first streaming text (TTFT)** — latency from session start to first `send()` call
- **Total duration** — wall-clock time from start to `complete()` or `error()`
- **Streaming text count** — number of `send()` calls
- **Error classification** — categorizes errors as `timeout`, `rate_limit`, `server_error`, or `unknown`
- **Active session tracking** — calls `sessionStarted()`/`sessionEnded()` for gauge updates

```java
var session = new TracingCapturingSession(delegate, metrics, "gpt-4");
session.send("Hello");    // captures first-token time, increments count
session.send(" world");   // increments count
session.complete();        // reports TTFT, total duration, token usage, ends session
```

## Cost and Latency Routing

`RoutingLlmClient` supports cost-based and latency-based routing rules alongside the existing content-based and model-based rules. Each rule uses `ModelOption` records that carry cost, latency, and capability metadata.

```java
var router = RoutingLlmClient.builder(defaultClient, "gemini-2.5-flash")
        // Route expensive requests to the cheapest model that fits the budget
        .route(RoutingRule.costBased(5.0, List.of(
                new ModelOption(openaiClient, "gpt-4o", 0.01, 200, 10),
                new ModelOption(geminiClient, "gemini-flash", 0.001, 50, 5))))
        // Route latency-sensitive requests to the fastest capable model
        .route(RoutingRule.latencyBased(100, List.of(
                new ModelOption(ollamaClient, "llama3.2", 0.0, 30, 3),
                new ModelOption(openaiClient, "gpt-4o-mini", 0.005, 80, 7))))
        .build();
```

**CostBased** filters models where `costPerStreamingText * request.maxStreamingTexts() <= maxCost`, then picks the highest-capability model. Sends `routing.model` and `routing.cost` metadata.

**LatencyBased** filters models where `averageLatencyMs <= maxLatencyMs`, then picks the highest-capability model. Sends `routing.model` and `routing.latency` metadata.

Rules are evaluated in order; first match wins. If no model fits the constraint, the rule is skipped and the next rule is tried.

## Observability, grounded facts, and guardrails

Three SPIs close the "generative model ↔ deterministic business layer"
loop Dynatrace's 2026 agentic-AI report called out: tag agent calls
with business-outcome attributes, ground every turn in verifiable
facts, and inspect requests/responses for PII or drift. Each uses the
same framework-scoped resolution as `CoordinationJournal` /
`AsyncSupport` / `BroadcasterFactory` — framework property bridge
first, then `ServiceLoader`, then a safe built-in default.

Packages: `org.atmosphere.ai.business`, `org.atmosphere.ai.facts`,
`org.atmosphere.ai.guardrails`.

### Two resolution patterns — when to pick which

The codebase ships two SPI-resolution patterns; both are valid, each fits
a specific call-site shape. The distinction was surfaced in the v0.9
review and is pinned here so new primitives don't reinvent the wheel.

| Pattern | Use when | Examples |
|---|---|---|
| **Framework-scoped property** (property bridge → `ServiceLoader` → default) | The primitive is consulted on the *request path* inside an `AtmosphereHandler` / `AiEndpointHandler` and the framework is the natural owner. | `FactResolver`, `CoordinationJournal`, `AsyncSupport`, `BroadcasterFactory` |
| **Process-wide holder** (static `Holder.install(...)` / `.get()`) | The primitive is consulted on the *session-decorator path* (`StreamingSession.send / usage / …`) where the dependency has to reach a call-site the framework doesn't inject into. | `AiGateway` (runtime admit), `CostAccountant` (session-decorator), `RunRegistry` (run lifecycle), `StructuredOutputParser` |

Rule of thumb: if the call site receives an `AtmosphereResource` or
`AtmosphereConfig`, go framework-property (it scales with the framework's
life cycle and is per-instance-isolated). If the call site only has a
`StreamingSession` or is deep inside an `AgentRuntime` bridge that
doesn't extend `AbstractAgentRuntime`, go process-wide holder — Spring
Boot / Quarkus starters install the concrete implementation at startup
and reset to no-op on shutdown via `DisposableBean` / `@PreDestroy`.

### BusinessMetadata — business-outcome correlation

```java
session.stream(request.withMetadata(Map.of(
    BusinessMetadata.CUSTOMER_ID, "cust-42",
    BusinessMetadata.SESSION_REVENUE, 12.50,
    BusinessMetadata.EVENT_KIND,
        BusinessMetadata.EventKind.PURCHASE.wireName())));
```

Keys named `business.*` land on SLF4J MDC for every turn (applied on
the virtual-thread dispatcher, cleared in `finally`). Observability
backends (Dynatrace, Datadog, OTel log exporters) propagate MDC onto
the active span so an agent call can be joined to its business outcome.

### FactResolver — grounded-fact injection

```java
public final class UserProfileFactResolver implements FactResolver {
    public FactBundle resolve(FactRequest req) {
        return new FactBundle(Map.of(
                FactKeys.USER_NAME, profileService.lookup(req.userId()).name(),
                FactKeys.USER_LOCALE, profileService.lookup(req.userId()).locale()));
    }
}
```

`AiEndpointHandler` calls the resolver on every `@Prompt` turn and
prepends the bundle to the system prompt via
`FactBundle.asSystemPromptBlock()`. Newline / tab / control characters
in values are escaped so fact values cannot reshape the instruction
context.

Resolution order inside the handler:
1. Spring-bridged bean at `FactResolver.FACT_RESOLVER_PROPERTY`
2. `ServiceLoader.load(FactResolver.class)` — plain servlet / Quarkus
3. `DefaultFactResolver` — supplies `time.now` + `time.timezone` only

### Guardrails — PII redaction, drift, cost ceiling

Three zero-dep implementations ship in-tree:

- `PiiRedactionGuardrail` — regex-based redaction of email / phone /
  credit card / US SSN / IPv4 in requests AND responses. Default mode
  Blocks on a response hit (the SPI cannot rewrite an already-emitted
  stream, so log-only was security theatre).
- `OutputLengthZScoreGuardrail` — rolling-window z-score on response
  length. Blocks outliers beyond N standard deviations. Catches
  runaway prompts and injection payloads that balloon responses
  without a specific signature. Windows partition by the
  `business.tenant.id` MDC tag so one noisy tenant cannot poison
  another's baseline.
- `CostCeilingGuardrail` — per-tenant dollar budget; Blocks the next
  outbound `@Prompt` when cumulative cost crosses the ceiling. Tenant
  scoping keys on `business.tenant.id`; turns without a tenant land in
  a shared `__default__` bucket. Automatically fed via
  `CostAccountingSession` → `CostCeilingAccountant` → `addCost` whenever
  a runtime reports `TokenUsage` (see Cost accounting wire below).

All three opt in via Spring property
(`atmosphere.ai.guardrails.{pii,drift,cost}.enabled=true`) or
ServiceLoader. `AiEndpointProcessor` merges annotation-declared,
ServiceLoader, and framework-property guardrails so user-defined
`@AiEndpoint` paths get the same wiring as the default endpoint.

### Governance policy plane

A declarative layer over the guardrail SPI. Policies carry stable
identity (`name` / `source` / `version`) for audit-trail pinning and
use the `admit` / `transform` / `deny` vocabulary from OPA/Rego and
MS Agent OS.

Drop `atmosphere-policies.yaml` on the classpath:

```yaml
version: "1.0"
policies:
  - name: customer-pii-guard
    type: pii-redaction
    version: "1.0"
    config:
      mode: redact            # redact | block

  - name: drift-watcher
    type: output-length-zscore
    config:
      window-size: 50
      z-threshold: 3.0
      min-samples: 10

  - name: tenant-budget
    type: cost-ceiling
    config:
      budget-usd: 100.00
```

Load and publish:

```java
@Configuration
public class PoliciesConfig {
    @Bean
    Object atmospherePolicyPlaneLoader(AtmosphereFramework framework) throws IOException {
        try (var in = new ClassPathResource("atmosphere-policies.yaml").getInputStream()) {
            var policies = new YamlPolicyParser().parse("classpath:atmosphere-policies.yaml", in);
            framework.getAtmosphereConfig().properties()
                    .put(GovernancePolicy.POLICIES_PROPERTY, policies);
            return policies;
        }
    }
}
```

That's it — `AiEndpointProcessor` picks them up through
`POLICIES_PROPERTY` and installs them on every `@AiEndpoint` in the
app. Spring-managed `GovernancePolicy` beans are also bridged
automatically by `AtmosphereAiAutoConfiguration`.

**Built-in types**: `pii-redaction`, `cost-ceiling`,
`output-length-zscore`. Register a custom type in code:

```java
PolicyRegistry registry = new PolicyRegistry();
registry.register("my-domain-policy",
        descriptor -> new MyDomainPolicy(descriptor.name(),
                descriptor.source(), descriptor.version(),
                descriptor.config()));
```

**Additional formats** (Rego, Cedar) plug in by shipping another
`PolicyParser` implementation and a
`META-INF/services/org.atmosphere.ai.governance.PolicyParser` entry.

**Interop with Microsoft Agent Governance Toolkit** (verified against
the April 2026 public source):

- **SPI shape** lines up at the evaluate-decision level:
  `GovernancePolicy.evaluate(PolicyContext) → PolicyDecision` mirrors
  MS's `PolicyEvaluator.evaluate(context: dict) → PolicyDecision`.
  Both carry identity metadata (matched policy name, version) and an
  admit/deny decision.
- **YAML artifact parity.** `YamlPolicyParser` auto-detects the MS
  schema — documents with a top-level `rules:` sequence produce a
  single `MsAgentOsPolicy` that preserves MS's first-match-by-priority
  rule-evaluation semantic. Operators (`eq`, `ne`, `gt`, `lt`, `gte`,
  `lte`, `in`, `contains`, `matches`) and actions (`allow`, `deny`,
  `audit`, `block`) are all honored. Drop-in example (copied verbatim
  from MS's `docs/tutorials/policy-as-code/examples/01_first_policy.yaml`):

  ```yaml
  version: "1.0"
  name: my-first-policy
  description: A simple policy that blocks dangerous agent actions
  rules:
    - name: block-delete-database
      condition: { field: tool_name, operator: eq, value: delete_database }
      action: deny
      priority: 100
      message: "Deleting databases is not allowed"
  defaults: { action: allow }
  ```

  Atmosphere's native `policies:` schema lives alongside the MS schema
  — the two are mutually exclusive per document. `MsAgentOsYamlConformanceTest`
  pins the interop against MS's unmodified example YAMLs so upstream
  schema drift surfaces as a test failure here.
- **Context map bridge**: rule `field:` references map to `AiRequest`
  properties (`message`, `system_prompt`, `model`, `user_id`,
  `session_id`, `agent_id`, `conversation_id`), the context phase
  (`phase` → `pre_admission` / `post_response`), and every
  `AiRequest.metadata()` entry by its exact key.
- **HTTP surface**: MS's `PolicyProviderHandler` is an ASGI app
  (`/check`, `/policies`, `/health`). Atmosphere exposes the same
  three endpoints at `/api/admin/governance/check`,
  `/api/admin/governance/policies`, and `/api/admin/governance/summary`.
  The `POST /check` endpoint accepts MS's `{agent_id, action,
  context}` payload and returns `{allowed, decision, reason,
  matched_policy, matched_source, evaluation_ms}` — drop-in wire
  compatibility so external gateways (Envoy, Kong, Azure APIM) that
  already speak to MS's ASGI app can use Atmosphere as the decision
  service without code changes.

**Admin introspection**: `/api/admin/governance/policies` lists
the live policy set; `/api/admin/governance/summary` returns counts
and distinct source URIs. Reports runtime-confirmed state (Correctness
Invariant #5) — not what the YAML intended.

**Interop with `AiGuardrail`**: `GuardrailAsPolicy` wraps any existing
guardrail as a policy; `PolicyAsGuardrail` goes the other way. Both
vocabularies land at the same `AiPipeline` admission seam so the
declarative layer is strictly additive — existing guardrail wiring
keeps working.

### Cost accounting wire — observability → enforcement

Every runtime calls `StreamingSession.usage(TokenUsage)` at completion.
`AiStreamingSession.dispatch` wraps the outgoing session in a
`CostAccountingSession` whenever a `CostAccountant` is installed in the
process-wide `CostAccountantHolder`, so those usage events feed a
pricing layer automatically:

```
runtime → session.usage(TokenUsage)
        → CostAccountingSession.usage (MDC: business.tenant.id)
        → CostAccountant.record(tenantId, usage, model)
        → (built-in) CostCeilingAccountant
        → TokenPricing.costUsd(usage, model)
        → CostCeilingGuardrail.addCost(tenantId, cost)
```

The next request-side `inspectRequest` on that guardrail sees the
accumulated spend and Blocks. Observability (`TokenUsage`) becomes the
input to enforcement (`CostCeilingGuardrail`) — a dashboard becomes a
control plane.

The Spring Boot starter wires this automatically when both a
`CostCeilingGuardrail` and `TokenPricing` bean are present; operators
with custom attribution (Micrometer, external ledger) publish their
own `CostAccountant` bean and the starter uses it instead. With neither
path set the holder stays at no-op and `CostAccountingSession` doesn't
wrap — zero overhead for deployments that don't need cost tracking.

## Samples

- [Spring Boot AI Chat](../../samples/spring-boot-ai-chat/) -- works with all backends (swap one Maven dependency)
- [Spring Boot AI Tools](../../samples/spring-boot-ai-tools/) -- framework-agnostic tool calling
- [Dentist Agent](../../samples/spring-boot-dentist-agent/) -- full `@Agent` with commands, tools, and multi-channel
- [Personal Assistant](../../samples/spring-boot-personal-assistant/) -- `AgentState`, `AgentWorkspace`, `AgentIdentity`, `ToolExtensibilityPoint`, `AiGateway`, `ProtocolBridge` exercised end-to-end through `@Coordinator` + three `@Agent` crew members
- [Coding Agent](../../samples/spring-boot-coding-agent/) -- `Sandbox` + `AgentResumeHandle`; clones a repo into Docker, reads files, proposes a patch
- [Quarkus AI Chat](../../samples/quarkus-ai-chat/) -- `@AiEndpoint` over WebSocket on Quarkus; `atmosphere-quarkus-langchain4j` bridges Quarkus LangChain4j's CDI `StreamingChatModel` into `LangChain4jAgentRuntime`

## AI-MCP Bridge

When used together with `atmosphere-mcp`, MCP tool methods can receive a `StreamingSession` backed by a `Broadcaster` — enabling AI agents to stream texts to browser clients without needing a direct WebSocket connection.

```java
@McpTool(name = "ask_ai", description = "Ask the AI and stream to a topic")
public String askAi(
        @McpParam(name = "question") String question,
        @McpParam(name = "topic") String topic,
        StreamingSession session) {
    // session broadcasts to all clients on the topic
    session.send("Thinking...", StreamingSession.MessageType.PROGRESS);
    settings.client().streamChatCompletion(request, session);
    return "streaming";
}
```

The `BroadcasterStreamingSession` class wraps a `Broadcaster` and emits the same wire format as `DefaultStreamingSession` — the browser client sees identical JSON messages regardless of whether streaming texts originate from a direct WebSocket connection or an MCP tool call.

See [atmosphere-mcp README](../mcp/README.md) for injectable parameter details.

## Capability Matrix

Unified view of the twelve `AgentRuntime` implementations shipped with Atmosphere, derived
from the pinned `expectedCapabilities()` declarations in each runtime's contract test
(Correctness Invariant #5 — Runtime Truth). `yes` means the capability is declared
**and** verified by a contract assertion; `—` means the framework does not expose the
feature through a path the Atmosphere bridge can honor today.

Legend: TS=TEXT_STREAMING, TC=TOOL_CALLING, SO=STRUCTURED_OUTPUT, SP=SYSTEM_PROMPT,
AO=AGENT_ORCHESTRATION, CM=CONVERSATION_MEMORY, TA=TOOL_APPROVAL, V=VISION, A=AUDIO,
MM=MULTI_MODAL, PC=PROMPT_CACHING, TU=TOKEN_USAGE, PRR=PER_REQUEST_RETRY,
TCD=TOOL_CALL_DELTA, BE=BUDGET_ENFORCEMENT, CS=CONFIDENCE_SCORES, PSV=PASSIVATION.

| Runtime | Priority | TS | TC | SO | SP | AO | CM | TA | V | A | MM | PC | TU | PRR | TCD | BE | CS | PSV |
|---------|---------:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:-:|:-:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| `BuiltInAgentRuntime`        |   0 | yes | yes | yes | yes | —   | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes |
| `SpringAiAgentRuntime`       | 100 | yes | yes | yes | yes | —   | yes | yes | yes | yes | yes | yes | yes | yes | —   | yes | yes | yes |
| `LangChain4jAgentRuntime`    | 100 | yes | yes | yes | yes | —   | yes | yes | yes | yes | yes | yes | yes | yes | —   | yes | yes | yes |
| `AdkAgentRuntime`            | 100 | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | —   | yes | yes | yes |
| `EmbabelAgentRuntime`        | 100 | yes | yes | yes | yes | yes | yes | yes | yes | —   | yes | —   | yes | yes | —   | yes | yes | yes |
| `KoogAgentRuntime`           | 100 | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | —   | yes | yes | yes |
| `AgentScopeAgentRuntime`     | 100 | yes | yes | yes | yes | —   | yes | yes | yes | yes | yes | —   | yes | yes | —   | yes | yes | yes |
| `SpringAiAlibabaAgentRuntime`| 100 | yes¹| yes | yes | yes | —   | yes | yes | yes | yes | yes | —   | yes | yes | —   | yes | yes | yes |
| `SemanticKernelAgentRuntime` | 100 | yes | yes | yes | yes | —   | yes | yes | yes | —   | yes | —   | yes | yes | —   | yes | yes | yes |
| `AnthropicAgentRuntime`      | 100 | yes | yes | yes | yes | —   | yes | yes | yes | —   | yes | —   | yes | yes | —   | yes | yes | yes |
| `CohereAgentRuntime`         | 100 | yes | yes | yes | yes | —   | yes | yes | yes | —   | yes | —   | yes | yes | yes | yes | yes | yes |
| `CrewAiAgentRuntime`³        |  50 | yes | yes | yes | yes | yes | —   | yes | —   | —   | —   | —   | yes | yes | —   | —   | —   | —   |

¹ `SpringAiAlibabaAgentRuntime` declares `TEXT_STREAMING` honestly because the
final reply ships as a single `session.send()` chunk and Atmosphere's transport
still streams that chunk to the client over WebSocket / SSE / long-poll. The
limitation is that the LLM round-trip itself is **buffered** — Spring AI Alibaba's
`ReactAgent.call()` is synchronous as of v1.1.2.0, so there are no incremental
token deltas from the LLM. Callers who need token-by-token streaming should drive
Spring AI's `StreamingChatModel` directly via `atmosphere-spring-ai`.

² (Removed in 4.0.46-SNAPSHOT.) `SpringAiAlibabaAgentRuntime` previously declared
`BUDGET_ENFORCEMENT` with wall-clock-only honesty because `ReactAgent.call()`
returns an `AssistantMessage` with no usage surface. Token / step budgets now
trip uniformly because `AtmosphereSpringAiAlibabaAutoConfiguration` wraps the
Spring AI `ChatModel` bean in `UsageCapturingChatModel`, which accumulates
`ChatResponseMetadata.getUsage()` across every step of the ReAct graph into a
per-thread collector that the runtime emits via `session.usage(...)` after
each dispatch — see the `TOKEN_USAGE` row above.

³ `CrewAiAgentRuntime` is the only out-of-process runtime: the Java
side is HTTP+SSE only, and the multi-agent crew runs in a companion
Python sidecar (`atmosphere-crewai-bridge`, under `modules/crewai/sidecar`).
`isAvailable()` is config-gated on `ATMOSPHERE_CREWAI_SIDECAR_URL`
pointing at a sidecar whose `GET /health` responds OK — the runtime
never advertises availability based on classpath alone (Correctness
Invariant #5). Java `@AiTool` methods materialise as `crewai.tools.BaseTool`
subclasses inside the sidecar and round-trip back through the
loopback `ToolCallbackServer`. See `modules/crewai/README.md` for
the wire protocol and the full capability inventory.

### Runtime selection for feature parity

Start with the feature your agent must have, then choose an adapter whose row
declares it. The flags above are runtime truth, not roadmap intent.

| Need | Prefer today | Avoid when this is mandatory |
|------|--------------|------------------------------|
| Portable `@AiTool` execution with HITL approval | All twelve runtimes — every adapter ships a tool bridge that routes through `ToolExecutionHelper.executeWithApproval` (CrewAI via the loopback `ToolCallbackServer` against its Python sidecar) | — |
| Token-by-token UI deltas | Built-in, Spring AI, LangChain4j, ADK, Embabel, Koog, Semantic Kernel, AgentScope, Anthropic, Cohere, CrewAI | Spring AI Alibaba, whose `ReactAgent.call()` is buffered |
| Embeddings through Atmosphere's `EmbeddingRuntime` SPI | Built-in, Spring AI, LangChain4j, Embabel, Semantic Kernel, Koog, Spring AI Alibaba | ADK, AgentScope (no embedding-runtime impl yet) |
| Tool-call argument deltas before consolidated `ToolStart` | Built-in, Cohere | Framework adapters whose upstream APIs expose consolidated tool calls only |

When a feature is missing, keep the adapter on the classpath only for the
capabilities it declares and compose the missing piece through Atmosphere's
portable SPI (`@AiTool`, `EmbeddingRuntime`, `ContextProvider`) rather than
claiming parity in docs or code.

### Predictable-AI primitives (`BUDGET_ENFORCEMENT`, `CONFIDENCE_SCORES`, `PASSIVATION`)

Three framework-level capabilities added in 4.0.44 that close gaps Bonér's
"Herding LLMs" deck flagged for distributed-system reliability — death-spiral
prevention, dynamic routing, and long-pause human-in-the-loop:

- **`BUDGET_ENFORCEMENT`** — `AiPipeline` installs a `BudgetCapturingSession`
  decorator when an `AiBudget` (token / step / wall-clock) is in scope. On
  breach the decorator routes an `AiBudgetExceededException` through
  `session.error(...)` and short-circuits the remaining stream. Wire-level
  contract: a single error frame, never a flurry. Set the budget either as a
  pipeline default via `pipeline.setDefaultBudget(AiBudget.ofTokens(20_000))` or
  per-request via the `ai.budget` metadata key (caller wins on collision).
  Wall-clock applies on every runtime; token / step limits apply where
  `TOKEN_USAGE` is honored — currently every runtime (Spring AI Alibaba
  closes the gap via the `UsageCapturingChatModel` decorator wired by
  `AtmosphereSpringAiAlibabaAutoConfiguration`).

- **`CONFIDENCE_SCORES`** — `AiPipeline` augments the system prompt with an
  `AiConfidenceElicitation` cue when one is configured, then installs a
  `ConfidenceCapturingSession` decorator that parses the model-emitted
  `{"confidence": 0.x}` field on stream completion and fires
  `session.confidence(AiConfidence)` ahead of the terminal frame. Three sources
  documented in `AiConfidence.Source`: `LOGPROBS_NATIVE` (native token logprobs
  from runtimes that override and call `session.confidence()` directly with
  richer signal — none ship today), `MODEL_REPORTED_FIELD` (the framework's
  universal-fallback path), `HEURISTIC` (caller-computed). The decorator is
  skipped when structured-output mode is in play because the schema parser owns
  the response shape — callers add a `confidence` field to their record schema
  in that mode.

- **`PASSIVATION`** — `org.atmosphere.checkpoint.AgentPassivation` captures the
  persistable subset of `AgentExecutionContext` into an `AgentSnapshot` and
  writes it via `CheckpointStore`; `resume()` reads the snapshot back, merges
  it onto a caller-supplied base context (which carries the runtime references
  — tools, memory, listeners — that don't survive a JVM restart), and re-runs
  `runtime.execute(...)`. The helper lives in `modules/checkpoint` rather than
  on `AgentRuntime` itself because `modules/ai → modules/checkpoint` introduces
  a dependency cycle (`ai → checkpoint → coordinator → ai`); the reverse
  direction is acyclic. Capability flag declared on every runtime because
  every runtime threads `context.history()` through its dispatch path — a
  resumed call observes the same conversation the paused call saw.

**Per-runtime gaps, honestly described:**

- **Built-in** `AGENT_ORCHESTRATION` is not declared because Built-in is a
  single-LLM runtime by design — it owns neither multi-step planning nor a
  multi-agent handoff surface.
- **Built-in / Spring AI / LangChain4j** `AGENT_ORCHESTRATION` — not declared
  because these adapters wrap a single LLM call rather than a planner.
- **`PER_REQUEST_RETRY`** is honored on all runtimes via two paths: Built-in uses
  `OpenAiCompatibleClient.sendWithRetry` as its native retry layer (opting out of
  the base-class wrapper via `ownsPerRequestRetry() = true`); every framework
  runtime inherits the outer retry wrapper in
  `AbstractAgentRuntime.executeWithOuterRetry` (Koog and Embabel, which do not
  extend `AbstractAgentRuntime`, duplicate the same retry loop in their own
  `execute()` methods). The outer wrapper retries pre-stream transient failures
  up to `policy.maxRetries()` on top of whatever retries the framework's own
  HTTP client performs — strictly "at least N retries", never fewer.
- **Embabel** routes through two dispatch paths: (a) when `context.agentId()`
  matches a deployed Embabel `@Agent`, it uses `runAgentFrom` (preserving
  Embabel's goal-planner value prop — the deployed agent owns its own prompt,
  history, and tools); (b) otherwise it falls back to
  `Ai.withDefaultLlm()` via `InfrastructureInjectionConfiguration.aiFactory(platform)`,
  and `EmbabelToolBridge` translates Atmosphere `ToolDefinition`s into Embabel
  `com.embabel.agent.api.tool.Tool` instances (routing through
  `ToolExecutionHelper.executeWithApproval`), with `Content.Image` parts mapped
  to `AgentImage`. `TEXT_STREAMING` on the Atmosphere-native path uses
  `StreamingPromptRunnerBuilder(runner).streaming().withPrompt(message).generateStream()`
  — Embabel's Reactor `Flux<String>` surface from
  `com.embabel.agent.spi.streaming.StreamingLlmOperations` — and forwards each
  chunk to `session.send()`. When the configured model service does not implement
  `StreamingLlmOperations`, the path falls back to the blocking `generateText()`
  call so dispatch still completes (no silent capability drop).
  `AUDIO` / `PROMPT_CACHING` remain undeclared because Embabel's `PromptRunner`
  surface does not expose audio input or a portable cache-control primitive on
  the direct-LLM path. `TOKEN_USAGE` is honest on the deployed-agent path only
  (`AgentProcess.usage()`) — the native path does not surface an aggregated
  usage record.
- **Koog** `PROMPT_CACHING` is honored via `CacheControl.Bedrock.{FiveMinutes,OneHour}`
  attached to `Message.User` when the request carries a `CacheHint`. Bedrock-backed
  Koog models observe the cache control on the wire; non-Bedrock providers silently
  drop it — the same "honored on one provider, no-op elsewhere" shape Spring AI /
  LangChain4j take for the OpenAI `prompt_cache_key` field. Multi-modal + tool
  calling in the same request: Koog's `AIAgent.run(String)` tool-loop surface only
  accepts a plain text message, so the bridge logs a WARN and the tool-calling
  path wins when both tools and multi-modal parts are present.
- **ADK** `PROMPT_CACHING` is honored via `resolveCacheConfig(context)` which
  reads `CacheHint.from(context)` and builds a per-request `ContextCacheConfig`
  with the hint's TTL. Since ADK's caching wires at `App.Builder` level, the
  runtime forces a fresh per-request `Runner` whenever a cache hint is present —
  working around ADK's App-scoped caching limitation without touching the upstream
  ADK API.
- **Semantic Kernel** `TOOL_CALLING` / `TOOL_APPROVAL` are implemented via
  `SemanticKernelToolBridge` — a direct `KernelFunction<String>` subclass (one per
  Atmosphere tool) whose overridden `invokeAsync` routes through
  `ToolExecutionHelper.executeWithApproval`. Earlier SK releases claimed tool calling
  needed a compile-time annotation processor or bytecode synthesis; that claim was
  wrong — `KernelFunction`'s protected constructor and abstract `invokeAsync` are
  designed exactly for this use case. `VISION` / `AUDIO` / `MULTI_MODAL` /
  `PROMPT_CACHING` / `AGENT_ORCHESTRATION` remain undeclared because SK 1.4.0's
  `ChatCompletionService.getStreamingChatMessageContentsAsync` does not expose
  those surfaces on the Atmosphere bridge path.
- **AgentScope Java** declares the minimum honest set: `TEXT_STREAMING` rides
  `Flux<Event> ReActAgent.stream(List<Msg>, StreamOptions)` (Reactor — each
  `Event.getMessage().getTextContent()` flows through `session.send()`),
  `SYSTEM_PROMPT` is threaded as a `Msg` with `MsgRole.SYSTEM` in the
  `assembleMessages` list, `CONVERSATION_MEMORY` is honored because the same
  list carries `context.history()`, and `TOKEN_USAGE` is captured from
  `Msg.getChatUsage()` on the terminal `event.isLast()` event. `TOOL_CALLING` is
  intentionally not declared in this first cut — bridging AgentScope's
  `Toolkit` into Atmosphere's `ToolDefinition` surface is a follow-up; declaring
  it without that bridge would violate Correctness Invariant #5. `VISION` /
  `AUDIO` / `MULTI_MODAL` / `PROMPT_CACHING` similarly await translation
  surfaces. `PER_REQUEST_RETRY` is honored via the inherited
  `AbstractAgentRuntime.executeWithOuterRetry` wrapper. Cancellation rides
  `Disposable.dispose()` + `agent.interrupt()`.
- **Spring AI Alibaba** is buffered (see footnote ¹ above). `SYSTEM_PROMPT` is
  honored two ways defensively — `ReactAgent.setSystemPrompt(context.systemPrompt())`
  is called per request, and `assembleMessages` also threads a
  `SystemMessage` into the `List<Message>` dispatched to `call(...)`.
  `CONVERSATION_MEMORY` is honored because the same message list carries
  `context.history()`. `TOKEN_USAGE` is **not** declared because
  `ReactAgent.call()` returns `org.springframework.ai.chat.messages.AssistantMessage`
  which has no surface for the `ChatResponse` usage metadata as of v1.1.2.0;
  the agent framework's `CompiledGraph` captures usage internally but does not
  return it through the `call(...)` API. `TOOL_CALLING` is not declared because
  Spring AI Alibaba's tool surface bridges Spring AI `FunctionCallback`s, which
  would need a separate `SpringAiAlibabaToolBridge` to satisfy
  `TOOL_APPROVAL`. **Spring Boot 3.5 only**: Spring AI Alibaba 1.1.2.0
  transitively pulls Spring AI 1.1.2 which references Spring Boot 3.x
  autoconfigure classes (e.g. `RestClientAutoConfiguration`) that don't exist
  in Spring Boot 4 — the CLI overlay must be applied with `-Pspring-boot3`,
  same situation as Embabel.
- **`TOOL_CALL_DELTA`** is declared only by `BuiltInAgentRuntime`. Built-in's
  `OpenAiCompatibleClient` forwards every `delta.tool_calls[].function.arguments`
  fragment through `session.toolCallDelta(acc.id(), argChunk)` on both the
  chat-completions and responses-API streaming paths (see
  `OpenAiCompatibleClient.java` lines ~530 and ~892), so browser UIs receive
  `ai.toolCall.delta.*` metadata frames before the consolidated `AiEvent.ToolStart`
  fires. The tool-capable framework bridges (Spring AI, LangChain4j, ADK,
  Embabel, Koog, Semantic Kernel) honor the default `StreamingSession.toolCallDelta()` no-op
  contract but do not emit chunks from their streaming loops — their high-level
  APIs surface only consolidated tool calls, and the negative assertion in
  `modules/integration-tests/e2e/ai-tool-call-delta.spec.ts` pins the gap
  (Correctness Invariant #5 — Runtime Truth).

### EmbeddingRuntime SPI

Five `EmbeddingRuntime` implementations are registered via `ServiceLoader`. The
`EmbeddingRuntimeResolver` selects the highest-priority available runtime.

| Runtime | Module | Priority | Notes |
|---------|--------|----------|-------|
| `SpringAiEmbeddingRuntime` | `atmosphere-spring-ai` | 200 | Wraps Spring AI `EmbeddingModel` |
| `LangChain4jEmbeddingRuntime` | `atmosphere-langchain4j` | 190 | Wraps LC4j `EmbeddingModel`; unwraps `Response<Embedding>` |
| `SemanticKernelEmbeddingRuntime` | `atmosphere-semantic-kernel` | 180 | Wraps SK `TextEmbeddingGenerationService`; `Mono.block()` sync boundary |
| `EmbabelEmbeddingRuntime` | `atmosphere-embabel` | 170 | Wraps Embabel `EmbeddingService` (1:1 SPI map) |
| `BuiltInEmbeddingRuntime` | `atmosphere-ai` | 50 | HTTP POST to `/v1/embeddings`; zero-dep fallback |

See <https://atmosphere.github.io/docs/reference/ai/> for the Astro reference page (maintained in the `atmosphere.github.io` repo).

## Requirements

- Java 21+
- `atmosphere-runtime` (transitive)
