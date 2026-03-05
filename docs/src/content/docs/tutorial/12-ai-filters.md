---
title: "AI Filters, Routing & Guardrails"
description: "PII redaction, content safety, cost metering, model routing, and fan-out streaming"
---

The AI module includes a rich middleware layer between your `@Prompt` method and the LLM. Filters and routers handle PII, safety, cost, and multi-model orchestration without touching your endpoint code.

## Filter Registration

Register filters via annotation:

```java
@AiEndpoint(path = "/ai/chat",
    filters = {PiiRedactionFilter.class, CostMeteringFilter.class})
public class SecureChat {
    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

## PiiRedactionFilter

Buffers streamed tokens to sentence boundaries and redacts personally identifiable information before delivery to the client.

**Detected patterns:**
- Email addresses
- Phone numbers
- Social Security Numbers (SSN)
- Credit card numbers

```java
@AiEndpoint(path = "/ai/chat",
    filters = {PiiRedactionFilter.class})
public class SafeChat { ... }
```

The LLM output `"Contact john@example.com for details"` becomes `"Contact [REDACTED] for details"`.

## ContentSafetyFilter

Pluggable content safety via the `SafetyChecker` SPI:

```java
public interface SafetyChecker {
    SafetyResult check(String content);
}
```

`SafetyResult` can be:
- **PASS** — content is safe, deliver as-is
- **REDACT** — replace unsafe content with a placeholder
- **BLOCK** — drop the entire message

Register a custom checker:

```java
public class CustomSafetyChecker implements SafetyChecker {
    @Override
    public SafetyResult check(String content) {
        if (containsHarmfulContent(content)) {
            return SafetyResult.block("Content policy violation");
        }
        return SafetyResult.pass();
    }
}
```

## CostMeteringFilter

Tracks per-session and per-broadcaster message counts with budget enforcement:

```java
@AiEndpoint(path = "/ai/chat",
    filters = {CostMeteringFilter.class})
public class BudgetedChat { ... }
```

When the budget is exceeded, the filter blocks further requests and sends an error to the client.

## TokenBudgetManager

Fine-grained per-user and per-organization token budgets with graceful degradation:

```java
var budgetManager = new TokenBudgetManager();
budgetManager.setUserBudget("user-123", 10000);  // 10K tokens
budgetManager.setOrgBudget("acme-corp", 1000000); // 1M tokens
```

When a user's budget runs low, the manager can:
- Switch to a cheaper model
- Reduce max response tokens
- Block further requests

## RoutingLlmClient

Routes prompts to different LLM backends based on rules:

```java
var router = RoutingLlmClient.builder(defaultClient, "gemini-2.5-flash")
    .route(RoutingRule.costBased(5.0, List.of(
        new ModelOption(openaiClient, "gpt-4o", 0.01, 200, 10),
        new ModelOption(geminiClient, "gemini-flash", 0.001, 50, 5))))
    .route(RoutingRule.latencyBased(100, List.of(
        new ModelOption(ollamaClient, "llama3.2", 0.0, 30, 3),
        new ModelOption(openaiClient, "gpt-4o-mini", 0.005, 80, 7))))
    .build();
```

### Cost-Based Routing

`RoutingRule.costBased(maxCostPerRequest, models)` selects the cheapest model that fits within the budget.

### Latency-Based Routing

`RoutingRule.latencyBased(maxLatencyMs, models)` selects the fastest model that meets the latency target.

### Content-Based Routing

Route different types of prompts to specialized models:

```java
.route(RoutingRule.contentBased(
    prompt -> prompt.contains("code") ? "codellama" : "gpt-4o",
    models))
```

## FanOutStreamingSession

Send the same prompt to multiple models simultaneously and merge the results:

```java
var fanOut = FanOutStreamingSession.builder()
    .add(openaiSession, "gpt-4o")
    .add(geminiSession, "gemini-pro")
    .strategy(FanOutStrategy.FIRST_COMPLETE)
    .build();
```

**Strategies:**
- `ALL_RESPONSES` — deliver all model responses to the client
- `FIRST_COMPLETE` — deliver only the first model to finish
- `FASTEST_TOKENS` — stream tokens from whichever model is currently fastest

## AiInterceptor

Pre/post-process AI requests for RAG, guardrails, and logging:

```java
public class RagInterceptor implements AiInterceptor {
    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        String context = vectorStore.search(request.message());
        return request.withMessage(context + "\n\n" + request.message());
    }
}

@AiEndpoint(path = "/ai/chat",
    interceptors = {RagInterceptor.class})
public class RagChat { ... }
```

## AiMetrics SPI

`MetricsCapturingSession` records AI-specific metrics:

- First-token latency
- Total token usage (input + output)
- Errors and retries
- Cost per request

Implement the `AiMetrics` interface and register via ServiceLoader:

```java
public interface AiMetrics {
    void recordFirstTokenLatency(Duration latency);
    void recordTokenUsage(int inputTokens, int outputTokens);
    void recordError(String errorType);
    void recordCost(double cost);
}
```

## AiResponseCacheInspector

Controls how AI responses interact with `BroadcasterCache`:

- Cache complete responses for replay to reconnecting clients
- Coalesce missed tokens into a single batch on reconnection
- Configure per-message TTL and max cache size

## Combining Filters

Stack multiple filters for defense in depth:

```java
@AiEndpoint(path = "/ai/secure-chat",
    filters = {PiiRedactionFilter.class, ContentSafetyFilter.class, CostMeteringFilter.class},
    interceptors = {RagInterceptor.class})
public class SecureRagChat {
    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

Execution order: interceptors run first (RAG injection), then the LLM generates, then filters process the output (PII redaction → safety check → cost metering).

## Samples

- [spring-boot-langchain4j-tools](https://github.com/Atmosphere/atmosphere/tree/main/samples/spring-boot-langchain4j-tools/) — PII redaction, cost metering
- [spring-boot-spring-ai-routing](https://github.com/Atmosphere/atmosphere/tree/main/samples/spring-boot-spring-ai-routing/) — cost/latency routing, content safety

## Next Steps

- [Chapter 13: MCP Server](/docs/tutorial/13-mcp/) — expose tools to AI agents
- [Chapter 18: Observability](/docs/tutorial/18-observability/) — AI metrics and tracing
