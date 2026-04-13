# Atmosphere Orchestration Demo — Support Desk

Two `@Agent` classes collaborating over a single Atmosphere streaming session. The support desk agent hands off billing questions to a specialist, dangerous admin tools pause for human approval, and slash commands answer instant FAQs without an LLM round-trip.

## What It Demonstrates

| Primitive | Where | How |
|-----------|-------|-----|
| **Agent handoff** | `SupportAgent.onPrompt()` | Calls `session.handoff("billing", message)` when the user message mentions bill/invoice/payment/charge/refund. `AiStreamingSession` transfers the conversation to `BillingAgent` over the same WebSocket. |
| **Human approval gate** | `SupportAgent.cancelAccount()` | `@RequiresApproval("This will permanently cancel…")` blocks execution until the user confirms. |
| **Slash commands** | `SupportAgent.status() / hours() / purge()` | `@Command("/status")`, `@Command("/hours")`, `@Command("/purge", confirm = "…")` answer without calling the LLM. `/purge` additionally uses the `confirm` attribute to require a second click before running. |
| **LLM tool calling** | `@AiTool` methods on both agents | `lookup_account`, `cancel_account`, `get_invoice`, `process_refund`. Wired via the LangChain4j runtime (`atmosphere-langchain4j` on the classpath). |
| **Demo-mode fallback** | `DemoResponseProducer` | Streams a canned word-by-word response when `LLM_API_KEY` is not set, so the sample runs offline with the same UX as the live path. |

> **Note:** The handoff is a plain intent check inside `@Prompt` (keyword match on the incoming message). This sample does **not** use `@Fleet`, `LlmJudge`, or declarative conditional routing — the only orchestration wiring is `session.handoff()` + `@RequiresApproval` + `@Command`.

## Architecture

```
Browser ──WebSocket──> /atmosphere/ai-chat  (aliased to /atmosphere/agent/support)
                                 │
                                 ▼
                        SupportAgent  @Prompt
                          │         │
                 "bill"? keyword    other
                          │         │
                          ▼         ▼
          session.handoff("billing")   LLM + @AiTool
                          │            (lookup_account,
                          ▼             cancel_account →
                   BillingAgent  @Prompt     @RequiresApproval)
                          │
                          ▼
                     LLM + @AiTool
                    (get_invoice, process_refund)
```

The two agents share one WebSocket. `AiStreamingSession.handoff()` reuses the same `AtmosphereResource` and conversation memory, so the billing agent sees the full prior context without a reconnect.

`ConsoleEndpointAlias` registers `/atmosphere/ai-chat` as a second handler path pointing at the support agent, so the built-in Atmosphere console (which targets `/atmosphere/ai-chat`) lands on the support desk first.

## Running

```bash
# From the repository root — demo mode (no API key)
./mvnw -pl samples/spring-boot-orchestration-demo spring-boot:run

# With a real LLM (Gemini, OpenAI, or any OpenAI-compatible endpoint)
LLM_API_KEY=your-key ./mvnw -pl samples/spring-boot-orchestration-demo spring-boot:run
```

Open http://localhost:8080/atmosphere/console/ in your browser.

## Endpoints

| Path | Served by | Purpose |
|------|-----------|---------|
| `/atmosphere/agent/support` | `SupportAgent` | Primary agent handler (auto-registered from `@Agent(name = "support")`) |
| `/atmosphere/agent/billing` | `BillingAgent` | Handoff target |
| `/atmosphere/ai-chat` | `SupportAgent` (aliased by `ConsoleEndpointAlias`) | Console convention path |
| `/atmosphere/console/` | Atmosphere built-in | Browser UI for the session above |

## Try These

- `/status` → instant FAQ (no LLM round-trip, no tool call)
- `/hours` → instant FAQ
- `/purge` → confirm prompt first (the `confirm = "…"` attribute on `@Command`)
- `How do I reset my password?` → stays with support
- `Can I get a refund on invoice INV-2026-0342?` → keyword match triggers `session.handoff("billing", …)`, then billing takes over
- `Please cancel account acc_42` → LLM calls `cancel_account`, the `@RequiresApproval` gate pauses for a confirm click

## Key Code

| File | Purpose |
|------|---------|
| `OrchestrationDemoApplication.java` | Standard `@SpringBootApplication` entry point |
| `SupportAgent.java` | `@Agent` with `@Prompt`, `session.handoff()`, `@Command` (×3), `@AiTool` (×2), `@RequiresApproval` |
| `BillingAgent.java` | `@Agent` with `@Prompt` and `@AiTool` (×2) — receives handoffs |
| `DemoResponseProducer.java` | Word-by-word streaming fallback when `LLM_API_KEY` is unset |
| `ConsoleEndpointAlias.java` | Registers `/atmosphere/ai-chat` as a second handler path → support agent |
| `application.yml` | Sets `atmosphere.ai.path = /atmosphere/agent/support` as the console default |

The system prompts for each agent live in `atmosphere-skills` and are referenced by id:
`SupportAgent` uses `skillFile = "skill:support-agent"` → `META-INF/skills/support-agent/SKILL.md`
`BillingAgent` uses `skillFile = "skill:billing-agent"` → `META-INF/skills/billing-agent/SKILL.md`

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `LLM_API_KEY` | _(none)_ | API key for Gemini/OpenAI/Ollama. Omit for demo mode. |
