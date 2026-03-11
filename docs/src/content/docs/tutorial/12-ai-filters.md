---
title: "AI Filters, Routing & Guardrails"
description: "PII redaction, content safety, cost metering, multi-model routing, fan-out streaming, and streaming text budgets"
sidebar:
  order: 12
---

Atmosphere's AI layer provides three categories of infrastructure that sit between LLM responses and browser clients: **filters** that process the text stream, **routing** that directs requests to the right model, and **guardrails** that enforce safety policies before and after LLM calls.

## AI stream filters

AI filters extend the same `BroadcastFilter` mechanism used by Atmosphere's core, specialized for the AI streaming wire protocol. The base class `AiStreamBroadcastFilter` (in `org.atmosphere.ai.filter`) handles `RawMessage` unwrapping, JSON parsing via `AiStreamMessage`, and re-wrapping automatically. Subclasses only implement `filterAiMessage()`.

### AiStreamBroadcastFilter

This abstract class implements `BroadcastFilterLifecycle`. Its `filter()` method:

1. Checks if the message is a `RawMessage` wrapping a JSON string.
2. Parses it into an `AiStreamMessage` (which has fields: `type`, `data`, `sessionId`, `seq`, etc.).
3. Delegates to the abstract `filterAiMessage()` method.
4. Non-AI messages pass through unchanged.

```java
public abstract class AiStreamBroadcastFilter implements BroadcastFilterLifecycle {

    protected abstract BroadcastAction filterAiMessage(
            String broadcasterId, AiStreamMessage msg,
            String originalJson, RawMessage rawMessage);
}
```

Subclasses return one of:

- `new BroadcastAction(rawMessage)` -- pass through unchanged.
- `new BroadcastAction(new RawMessage(modified.toJson()))` -- pass through modified.
- `new BroadcastAction(ACTION.ABORT, rawMessage)` -- drop the message.
- `new BroadcastAction(ACTION.SKIP, rawMessage)` -- stop filter chain, deliver as-is.

### PiiRedactionFilter

Detects and redacts personally identifiable information from AI-generated text streams. Since streaming texts arrive one or a few words at a time, the filter buffers streaming texts per session until a sentence boundary (`.`, `!`, `?`, or newline) is detected. At that point it scans the buffered sentence, redacts matches, and emits the cleaned text.

Default patterns:

| Pattern | What it matches |
|---------|----------------|
| `email` | Standard email addresses |
| `us-phone` | US phone numbers (10+ digits, various formats) |
| `ssn` | US Social Security Numbers (NNN-NN-NNNN) |
| `credit-card` | Credit card numbers (13-19 digits with optional separators) |

Usage:

```java
broadcaster.getBroadcasterConfig().addFilter(new PiiRedactionFilter());
```

With a custom replacement string:

```java
broadcaster.getBroadcasterConfig().addFilter(new PiiRedactionFilter("***"));
```

Adding custom patterns:

```java
var filter = new PiiRedactionFilter();
filter.addPattern("uk-nino", Pattern.compile("[A-Z]{2}\\d{6}[A-Z]"));
broadcaster.getBroadcasterConfig().addFilter(filter);
```

Removing a default pattern:

```java
filter.removePattern("credit-card");
```

On stream completion, any remaining buffered text is flushed with redaction applied. The filter uses a deferred broadcast on a virtual thread to emit the flushed streaming text before the terminal `complete` message.

### ContentSafetyFilter

Scans AI-generated content for harmful patterns and blocks or replaces unsafe content mid-stream. Like `PiiRedactionFilter`, it buffers streaming texts into sentence-sized chunks for context-aware scanning. A pluggable `SafetyChecker` interface allows custom safety logic.

Safety outcomes are modeled as a sealed interface:

```java
public sealed interface SafetyResult
        permits SafetyResult.Safe, SafetyResult.Unsafe, SafetyResult.Redacted {
    record Safe() implements SafetyResult {}
    record Unsafe(String reason) implements SafetyResult {}
    record Redacted(String cleanText) implements SafetyResult {}
}
```

- **Safe**: pass through unchanged.
- **Unsafe**: abort the entire stream and send an error message to the client.
- **Redacted**: replace the text with a cleaned version and continue streaming.

Built-in checker factories:

```java
// Block the stream entirely when a keyword is found
var checker = ContentSafetyFilter.keywordChecker(Set.of("harmful-term"));
broadcaster.getBroadcasterConfig().addFilter(new ContentSafetyFilter(checker));

// Redact keywords instead of blocking
var checker = ContentSafetyFilter.redactingChecker(
    Set.of("sensitive-term"), "[FILTERED]");
broadcaster.getBroadcasterConfig().addFilter(new ContentSafetyFilter(checker));
```

For external moderation APIs, implement `SafetyChecker` directly:

```java
SafetyChecker apiChecker = text -> {
    var result = moderationApi.check(text);
    if (result.isBlocked()) {
        return new SafetyResult.Unsafe(result.reason());
    }
    return new SafetyResult.Safe();
};
```

### CostMeteringFilter

Tracks streaming text counts per session and per broadcaster, and optionally enforces streaming text budgets by aborting streams that exceed their allocation. This filter does not modify streaming text content.

```java
var metering = new CostMeteringFilter();
metering.setBudget("user-123-broadcaster", 10000); // max 10K streaming texts
broadcaster.getBroadcasterConfig().addFilter(metering);
```

When a budget is exceeded, the filter marks the session as exceeded, drops subsequent streaming texts with `ACTION.ABORT`, and injects a single error message to notify the client.

Querying usage:

```java
long sessionStreamingTexts = metering.getSessionStreamingTextCount("session-id");
long broadcasterStreamingTexts = metering.getBroadcasterStreamingTextCount("broadcaster-id");
metering.resetBroadcasterCount("broadcaster-id"); // rolling window reset
```

Wiring a `StreamingTextBudgetManager` for persistent budget tracking:

```java
metering.setBudgetManager(budgetManager, sessionId -> lookupUserId(sessionId));
```

When `setBudgetManager` is configured, the filter calls `budgetManager.recordUsage(ownerId, streamingTextCount)` on every stream completion.

## Streaming text budget management

The `StreamingTextBudgetManager` (in `org.atmosphere.ai.budget`) manages per-user or per-organization streaming text budgets with graceful degradation.

```java
var budgetManager = new StreamingTextBudgetManager();
budgetManager.setBudget(new StreamingTextBudgetManager.Budget(
    "user-123", 100_000, "gemini-2.5-flash", 0.8));
```

The `Budget` record:

```java
public record Budget(
    String ownerId,
    long maxStreamingTexts,
    String fallbackModel,
    double degradationThreshold
) {}
```

When usage approaches the `degradationThreshold` fraction (e.g., 80%), `recommendedModel()` returns the cheaper fallback model name. When the budget is fully exhausted, it throws `BudgetExceededException`:

```java
try {
    Optional<String> fallback = budgetManager.recommendedModel("user-123");
    // fallback.isPresent() means "switch to the cheaper model"
} catch (BudgetExceededException e) {
    // Budget fully exhausted: e.ownerId(), e.budget(), e.used()
}
```

Other operations:

```java
long remaining = budgetManager.remaining("user-123");
long used = budgetManager.currentUsage("user-123");
budgetManager.resetUsage("user-123"); // new billing period
budgetManager.removeBudget("user-123");
```

## AiGuardrail

The `AiGuardrail` interface (in `org.atmosphere.ai`) provides pre-LLM and post-LLM inspection. Guardrails run in the interceptor chain:

```
Guardrails (pre) -> Rate Limit -> RAG -> [LLM call] -> Guardrails (post) -> Observability
```

```java
public interface AiGuardrail {

    default GuardrailResult inspectRequest(AiRequest request) {
        return GuardrailResult.pass();
    }

    default GuardrailResult inspectResponse(String accumulatedResponse) {
        return GuardrailResult.pass();
    }
}
```

`GuardrailResult` is a sealed interface with three variants:

```java
sealed interface GuardrailResult {
    record Pass() implements GuardrailResult {}
    record Modify(AiRequest modifiedRequest) implements GuardrailResult {}
    record Block(String reason) implements GuardrailResult {}

    static GuardrailResult pass() { return new Pass(); }
    static GuardrailResult modify(AiRequest req) { return new Modify(req); }
    static GuardrailResult block(String reason) { return new Block(reason); }
}
```

Example guardrail:

```java
public class PiiGuardrail implements AiGuardrail {
    @Override
    public GuardrailResult inspectRequest(AiRequest request) {
        if (containsPii(request.message())) {
            return GuardrailResult.block("PII detected in request");
        }
        return GuardrailResult.pass();
    }
}
```

Register guardrails on an endpoint:

```java
@AiEndpoint(path = "/chat", guardrails = {PiiGuardrail.class})
```

## Model routing

The `ModelRouter` interface (in `org.atmosphere.ai`) mirrors Atmosphere's transport failover pattern (WebSocket -> SSE -> long-polling) applied to the AI layer (GPT-4 -> Claude -> Gemini).

```java
public interface ModelRouter {
    Optional<AiSupport> route(
        AiRequest request,
        List<AiSupport> availableBackends,
        Set<AiCapability> requiredCapabilities);

    void reportFailure(AiSupport backend, Throwable error);
    void reportSuccess(AiSupport backend);
}
```

### FallbackStrategy

The `ModelRouter.FallbackStrategy` enum defines four strategies:

| Strategy | Behavior |
|----------|----------|
| `NONE` | Use the primary model only |
| `FAILOVER` | On failure, try the next backend in priority order |
| `ROUND_ROBIN` | Distribute requests across backends |
| `CONTENT_BASED` | Route based on request characteristics (model hint, tool requirements) |

### DefaultModelRouter

The default implementation uses a circuit breaker pattern for health tracking:

- Consecutive failures increment a failure counter.
- After `maxConsecutiveFailures` (default 3), the backend is marked unhealthy.
- After a cooldown period (default 1 minute), the backend is eligible again.
- A success resets the failure counter.

```java
var router = new DefaultModelRouter(FallbackStrategy.FAILOVER);
// or with custom thresholds:
var router = new DefaultModelRouter(FallbackStrategy.ROUND_ROBIN, 5, Duration.ofMinutes(2));
```

For `CONTENT_BASED` routing, the router checks `request.model()` for a model hint and `request.tools()` for tool requirements, preferring backends with `AiCapability.TOOL_CALLING`.

### RoutingAiSupport

Wraps a `ModelRouter` and a list of backends into a single `AiSupport` instance. On failure, it attempts one retry with the next backend:

```java
var routing = new RoutingAiSupport(router, List.of(
    springAiSupport,
    langChain4jSupport,
    adkSupport
));
```

The `name()` returns a descriptive string like `routing(spring-ai,langchain4j,google-adk)`. Its `capabilities()` is the union of all backends' capabilities.

### RoutingLlmClient

For lower-level control, `RoutingLlmClient` (in `org.atmosphere.ai.routing`) routes at the `LlmClient` level with configurable rules:

```java
var router = RoutingLlmClient.builder(defaultClient, "gemini-2.5-flash")
    .route(RoutingRule.contentBased(
        prompt -> prompt.contains("code"),
        openaiClient, "gpt-4o"))
    .route(RoutingRule.contentBased(
        prompt -> prompt.contains("translate"),
        claudeClient, "claude-3-haiku"))
    .build();

router.streamChatCompletion(request, session);
```

Rules are evaluated in order. The first matching rule determines the target client and model. If no rule matches, the default client is used.

## Fan-out streaming

Fan-out sends the same prompt to multiple models simultaneously, with each model streaming texts through its own child session. The `FanOutStreamingSession` (in `org.atmosphere.ai.fanout`) orchestrates this.

### FanOutStrategy

A sealed interface with three variants:

```java
public sealed interface FanOutStrategy {
    record AllResponses() implements FanOutStrategy {}
    record FirstComplete() implements FanOutStrategy {}
    record FastestStreamingTexts(int streamingTextThreshold) implements FanOutStrategy {}
}
```

| Strategy | Behavior |
|----------|----------|
| `AllResponses` | All models stream to completion. The client receives interleaved text streams distinguishable by session ID. |
| `FirstComplete` | First model to finish wins. All other in-flight calls are cancelled. |
| `FastestStreamingTexts(n)` | Observe streaming text production speed for `n` initial streaming texts, then keep the fastest model and cancel the rest. |

### ModelEndpoint

Describes one model to fan out to:

```java
public record ModelEndpoint(String id, LlmClient client, String model) {}
```

### FanOutResult

Available after fan-out completes:

```java
public record FanOutResult(
    String modelId,
    String fullResponse,
    long timeToFirstStreamingTextMs,
    long totalTimeMs,
    int streamingTextCount
) {}
```

### Usage

```java
var endpoints = List.of(
    new ModelEndpoint("gemini", geminiClient, "gemini-2.5-flash"),
    new ModelEndpoint("gpt4", openaiClient, "gpt-4o")
);

try (var fanOut = new FanOutStreamingSession(session, endpoints,
        new FanOutStrategy.AllResponses(), resource)) {
    fanOut.fanOut(ChatCompletionRequest.of("ignored", userPrompt));

    // After completion, inspect results
    Map<String, FanOutResult> results = fanOut.getResults();
    var geminiResult = results.get("gemini");
    logger.info("Gemini TTFT: {}ms, total: {}ms, streaming texts: {}",
        geminiResult.timeToFirstStreamingTextMs(),
        geminiResult.totalTimeMs(),
        geminiResult.streamingTextCount());
}
```

Child sessions use IDs of the form `parentSessionId + "-" + endpointId`, so the client can distinguish which model produced each streaming text. The parent session receives metadata events: `fanout.models` (list of model IDs at start) and `fanout.complete` (boolean at end).

## Putting it all together

A typical production setup combines filters, routing, and budget management:

```java
// 1. Set up budget management
var budgetManager = new StreamingTextBudgetManager();
budgetManager.setBudget(new StreamingTextBudgetManager.Budget(
    "org-acme", 500_000, "gemini-2.5-flash", 0.8));

// 2. Set up routing with failover
var router = new DefaultModelRouter(FallbackStrategy.FAILOVER);
var routing = new RoutingAiSupport(router, List.of(
    springAiSupport, langChain4jSupport));

// 3. Add filters to the broadcaster
var metering = new CostMeteringFilter();
metering.setBudgetManager(budgetManager, sid -> lookupOrgId(sid));

broadcaster.getBroadcasterConfig().addFilter(new PiiRedactionFilter());
broadcaster.getBroadcasterConfig().addFilter(
    new ContentSafetyFilter(ContentSafetyFilter.keywordChecker(blockedTerms)));
broadcaster.getBroadcasterConfig().addFilter(metering);
```

The filter chain processes every streaming text in order: PII redaction first, then content safety, then cost metering. If PII redaction buffers a streaming text (waiting for a sentence boundary), it is not visible to downstream filters until the sentence is complete.

## Samples

- **`samples/spring-boot-ai-tools/`** -- demonstrates the `CostMeteringInterceptor` that tracks streaming text usage and sends routing metadata to the client.
- **`samples/spring-boot-spring-ai-routing/`** -- demonstrates multi-model routing with `RoutingAiSupport` and `DefaultModelRouter` using Spring AI backends.
