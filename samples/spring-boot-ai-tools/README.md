# Atmosphere @AiTool Pipeline

Framework-agnostic AI tool calling with **real-time tool events** — tools declared with `@AiTool` annotations are automatically bridged to whichever AI backend is active (Spring AI, LangChain4j, or Google ADK). Tool invocations are streamed as `AiEvent` frames so the frontend shows live tool activity.

## What It Does

The assistant has six tools registered via Atmosphere's `@AiTool` annotation:

| Tool | Description |
|------|-------------|
| `get_current_time` | Returns the current date and time |
| `get_city_time` | Returns the time in a specific city (New York, London, Paris, Tokyo, Sydney) |
| `get_weather` | Returns a weather report for a city |
| `convert_temperature` | Converts between Celsius and Fahrenheit |
| `reset_city_data` | Resets cached weather/time data for a city — a destructive operation gated by `@RequiresApproval` |
| `issue_refund` | **Moves money** — posts a refund to an in-memory ledger, gated by `@RequiresApproval` so it only runs after a human approves |

## Human-in-the-Loop: gating a money-moving tool

`issue_refund` is the canonical "gate a money-moving tool behind a human" example. The
method is annotated `@RequiresApproval("This refund posts immediately. Approve?")`. When the
model decides to call it, Atmosphere's pipeline **pauses** (parking the virtual thread),
emits an approval request to the client, and only invokes the method — which mutates the
refund ledger — once a human approves.

The gate is enforced in `ToolExecutionHelper.executeWithApproval(...)`, the single seam every
runtime bridge routes tool calls through. The refund is **withheld** (the ledger is never
touched) when approval is:

- **denied** — returns `{"status":"cancelled"}`, no money moves;
- **timed out** — returns `{"status":"timeout"}`, no money moves;
- **un-wired** (no `ApprovalStrategy` on the path) — fails closed, no money moves.

Only an explicit **approve** runs `issueRefund(...)` and posts the order amount to the ledger.

`RefundApprovalGateTest` proves this on the **observable side effect** (the ledger balance via
`AssistantTools.refundedCents(orderId)`), not on the mere presence of the annotation: the
ledger stays empty for denied/timed-out/un-wired approvals and gains exactly the order amount
only after an approval.

## Key Features

- **`@AiEndpoint(tools = AssistantTools.class)`** — `@AiTool` methods auto-bridged to whichever AI backend is active
- **`AiEvent` tool events** — `ToolStart` and `ToolResult` events streamed to the frontend in real-time
- **Cost metering** — `CostMeteringInterceptor` tracks tokens, cost, and latency per response
- **Conversation memory** — multi-turn history with configurable window size
- **Demo mode** — works out-of-the-box without an API key (the built-in runtime returns canned LLM responses). Note: the example tools themselves (e.g. `get_weather`) return illustrative data in **every** mode — this sample demonstrates `@AiTool` wiring, not a live weather service

## Running

```bash
# Demo mode (no API key needed — simulated responses with tool events)
atmosphere run spring-boot-ai-tools

# Or from the repository root
./mvnw spring-boot:run -pl samples/spring-boot-ai-tools

# With a real LLM
LLM_API_KEY=your-gemini-key ./mvnw spring-boot:run -pl samples/spring-boot-ai-tools
```

Open http://localhost:8090 in your browser.

## Try These Prompts

- `What time is it in Tokyo?` — triggers `get_city_time` with live tool activity
- `What's the weather in Paris?` — triggers `get_weather` with tool events
- `Convert 100°F to Celsius` — triggers `convert_temperature`
- `What tools do you have?` — lists available `@AiTool` methods

## Key Code

| File | Purpose |
|------|---------|
| `AiToolsChat.java` | `@AiEndpoint` with `tools`, `conversationMemory`, and cost/lifecycle interceptors |
| `AssistantTools.java` | `@AiTool`-annotated methods (portable across backends) |
| `DemoResponseProducer.java` | Fallback with `AiEvent.ToolStart`/`ToolResult` events |
| `CostMeteringInterceptor.java` | `AiInterceptor` for cost/latency tracking |
| `App.tsx` | React frontend with `ToolActivity` component for live events |

## Architecture

```
Browser ──WebSocket──> @AiEndpoint(tools=AssistantTools.class)
                           │
                    ToolRegistry.execute(name, args, session)
                           │         │
                     AiEvent.ToolStart  AiEvent.ToolResult
                           │              │
                     ──────┘──────────────┘──> StreamingSession.emit()
                                                     │
                                              JSON event frames
                                                     │
Browser <──WebSocket──  useStreaming().aiEvents ──────┘
```

## Swapping the AI Backend

To use Spring AI instead of LangChain4j, just change the Maven dependency:

```xml
<!-- Replace this -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-langchain4j</artifactId>
</dependency>

<!-- With this -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-ai</artifactId>
</dependency>
```

No tool code changes needed — `AssistantTools.java` works unchanged.
