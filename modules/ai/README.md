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
| `atmosphere-ai` (built-in) | `BuiltInAgentRuntime` (OpenAI-compatible) | 0 | TEXT_STREAMING, TOOL_CALLING, STRUCTURED_OUTPUT, SYSTEM_PROMPT, TOOL_APPROVAL, VISION, AUDIO, MULTI_MODAL, PROMPT_CACHING, PER_REQUEST_RETRY, TOKEN_USAGE, CONVERSATION_MEMORY, TOOL_CALL_DELTA |
| `atmosphere-spring-ai` | `SpringAiAgentRuntime` | 100 | TEXT_STREAMING, TOOL_CALLING, STRUCTURED_OUTPUT, SYSTEM_PROMPT, TOOL_APPROVAL, VISION, AUDIO, MULTI_MODAL, PROMPT_CACHING, TOKEN_USAGE, CONVERSATION_MEMORY, PER_REQUEST_RETRY |
| `atmosphere-langchain4j` | `LangChain4jAgentRuntime` | 100 | TEXT_STREAMING, TOOL_CALLING, STRUCTURED_OUTPUT, SYSTEM_PROMPT, TOOL_APPROVAL, VISION, AUDIO, MULTI_MODAL, PROMPT_CACHING, TOKEN_USAGE, CONVERSATION_MEMORY, PER_REQUEST_RETRY |
| `atmosphere-adk` | `AdkAgentRuntime` | 100 | TEXT_STREAMING, TOOL_CALLING, STRUCTURED_OUTPUT, AGENT_ORCHESTRATION, CONVERSATION_MEMORY, SYSTEM_PROMPT, TOOL_APPROVAL, VISION, AUDIO, MULTI_MODAL, TOKEN_USAGE, PER_REQUEST_RETRY, PROMPT_CACHING |
| `atmosphere-embabel` | `EmbabelAgentRuntime` | 100 | TEXT_STREAMING, STRUCTURED_OUTPUT, AGENT_ORCHESTRATION, SYSTEM_PROMPT, CONVERSATION_MEMORY, TOKEN_USAGE, PER_REQUEST_RETRY, TOOL_CALLING, TOOL_APPROVAL, VISION, MULTI_MODAL |
| `atmosphere-koog` | `KoogAgentRuntime` | 100 | TEXT_STREAMING, TOOL_CALLING, STRUCTURED_OUTPUT, AGENT_ORCHESTRATION, CONVERSATION_MEMORY, SYSTEM_PROMPT, TOOL_APPROVAL, TOKEN_USAGE, VISION, AUDIO, MULTI_MODAL, PROMPT_CACHING, PER_REQUEST_RETRY |
| `atmosphere-semantic-kernel` | `SemanticKernelAgentRuntime` | 100 | TEXT_STREAMING, SYSTEM_PROMPT, STRUCTURED_OUTPUT, CONVERSATION_MEMORY, TOKEN_USAGE, TOOL_CALLING, TOOL_APPROVAL, PER_REQUEST_RETRY |

Every runtime emits `TokenUsage` via `StreamingSession.usage()` when the underlying API provides token counts, feeding `ai.tokens.*` metadata into `MetricsCapturingSession` and `MicrometerAiMetrics`. Capability declarations are pinned in each runtime's contract test (`AbstractAgentRuntimeContractTest.expectedCapabilities()`), so the table above cannot drift from the running code without breaking the build.

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

### Governance policy plane (Phase A)

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

Unified view of the seven `AgentRuntime` implementations shipped with Atmosphere, derived
from the pinned `expectedCapabilities()` declarations in each runtime's contract test
(Correctness Invariant #5 — Runtime Truth). `yes` means the capability is declared
**and** verified by a contract assertion; `—` means the framework does not expose the
feature through a path the Atmosphere bridge can honor today.

Legend: TS=TEXT_STREAMING, TC=TOOL_CALLING, SO=STRUCTURED_OUTPUT, SP=SYSTEM_PROMPT,
AO=AGENT_ORCHESTRATION, CM=CONVERSATION_MEMORY, TA=TOOL_APPROVAL, V=VISION, A=AUDIO,
MM=MULTI_MODAL, PC=PROMPT_CACHING, TU=TOKEN_USAGE, PRR=PER_REQUEST_RETRY,
TCD=TOOL_CALL_DELTA.

| Runtime | Priority | TS | TC | SO | SP | AO | CM | TA | V | A | MM | PC | TU | PRR | TCD |
|---------|---------:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:-:|:-:|:--:|:--:|:--:|:--:|:--:|
| `BuiltInAgentRuntime`        |   0 | yes | yes | yes | yes | —   | yes | yes | yes | yes | yes | yes | yes | yes | yes |
| `SpringAiAgentRuntime`       | 100 | yes | yes | yes | yes | —   | yes | yes | yes | yes | yes | yes | yes | yes | —   |
| `LangChain4jAgentRuntime`    | 100 | yes | yes | yes | yes | —   | yes | yes | yes | yes | yes | yes | yes | yes | —   |
| `AdkAgentRuntime`            | 100 | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | —   |
| `EmbabelAgentRuntime`        | 100 | yes | yes | yes | yes | yes | yes | yes | yes | —   | yes | —   | yes | yes | —   |
| `KoogAgentRuntime`           | 100 | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes | —   |
| `SemanticKernelAgentRuntime` | 100 | yes | yes | yes | yes | —   | yes | yes | —   | —   | —   | —   | yes | yes | —   |

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
  to `AgentImage`. `AUDIO` / `PROMPT_CACHING` remain undeclared because
  Embabel's `PromptRunner` surface does not expose audio input or a portable
  cache-control primitive on the direct-LLM path. `TOKEN_USAGE` is honest on the
  deployed-agent path only (`AgentProcess.usage()`) — the native path does not
  surface an aggregated usage record.
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
- **`TOOL_CALL_DELTA`** is declared only by `BuiltInAgentRuntime`. Built-in's
  `OpenAiCompatibleClient` forwards every `delta.tool_calls[].function.arguments`
  fragment through `session.toolCallDelta(acc.id(), argChunk)` on both the
  chat-completions and responses-API streaming paths (see
  `OpenAiCompatibleClient.java` lines ~530 and ~892), so browser UIs receive
  `ai.toolCall.delta.*` metadata frames before the consolidated `AiEvent.ToolStart`
  fires. The six framework bridges (Spring AI, LangChain4j, ADK, Embabel, Koog,
  Semantic Kernel) honor the default `StreamingSession.toolCallDelta()` no-op
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
