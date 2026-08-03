# Spring Boot AI Chat Sample

A real-time AI chat application that streams LLM responses text-by-text to the browser using Atmosphere's built-in `OpenAiCompatibleClient`. Works with **Gemini**, **OpenAI**, **Ollama**, and any OpenAI-compatible endpoint.

## Key Features

- **`@Agent`** — drop `@Agent` on a class with a `@Prompt` method and a `SKILL.md` persona and that class *is* a running, streaming agent. `MultiModalAgent` is exactly that: one `@Agent` class, a `skill:multimodal-assistant` skill file, and a multi-modal chat endpoint at `/atmosphere/agent/multimodal`
- **`@AiEndpoint`** — the lower-level building block `@Agent` desugars to: a declarative AI endpoint with system prompt, capability validation, and conversation memory (used by `AiChat`)
- **Capability requirements** — `requires = {TEXT_STREAMING, SYSTEM_PROMPT}` fails fast if the backend can't deliver
- **Conversation memory** — multi-turn context preserved automatically per client
- **Structured events** — `AiEvent` wire protocol for tool calls, agent steps, and structured output
- **Demo mode** — works out-of-the-box without an API key (simulated streaming)
- **Prompt cache demo** — `PromptCacheDemoChat` at `/atmosphere/ai-chat-with-cache` shows how `@AiEndpoint(promptCache = CONSERVATIVE)` threads a `CacheHint` into every request; the sample routes prompts through a real `AiPipeline` + `InMemoryResponseCache` so the framework emits `ai.cache.hit=false` on the first request and `ai.cache.hit=true` on repeated identical prompts (canonical framework-level wire signal, not a sample shim)
- **Retry policy demo** — `RetryDemoChat` at `/atmosphere/ai-chat-with-retry` echoes the declared `@AiEndpoint(retry = @Retry(...))` attributes and exposes a deterministic `fail-once:<id>` fault-injection path that recovers on a second request
- **Governance as a learning signal** — a soft-preference `Prefer` policy + `GovernanceFeedbackInterceptor` steer the model with an org-specific process the base model can't know (see below)
- **Multi-modal `@Agent` demo** — `MultiModalAgent` (an `@Agent` class whose persona lives in `prompts/multimodal-assistant-skill.md`) at `/atmosphere/agent/multimodal` accepts both vision and audio input:
  - **Vision** — `image:<base64>` prompts are wrapped in a `Content.Image` and streamed back as a binary content frame next to a text acknowledgement.
  - **Audio input** — `audio:<base64>` prompts are wrapped in a `Content.Audio` and forwarded to the resolved AI runtime as a multi-modal **input** part via `session.stream(prompt, parts)`. The runtime encodes it onto the provider wire request (the built-in OpenAI-compatible client emits an `input_audio` content block), so an audio-capable model such as `gpt-4o-audio-preview` receives the clip. With no API key the demo runtime returns a canned reply, but the audio still reaches the runtime context. Override the media type with `audio:audio/<subtype>:<base64>` (default `audio/wav`).
  - The delivery test `MultiModalAudioInputDeliveryTest` proves the audio reaches the runtime by asserting the captured `AgentExecutionContext.parts()` contains the `Content.Audio` with the right media type.
  - A minimal picker page is served at `/multimodal.html`.

## How It Works

### Server — `AiChat.java`

An `@AiEndpoint` at `/atmosphere/ai-chat`:

1. Client connects via WebSocket and sends a prompt
2. The `@Prompt` handler calls `session.stream(message)` which routes through the `AgentRuntime` SPI
3. The framework handles conversation memory, interceptors, guardrails, and streaming automatically
4. Each streaming text is pushed to the client as a JSON frame

```java
@AiEndpoint(path = "/atmosphere/ai-chat",
        systemPromptResource = "skill:ai-assistant",
        requires = {AiCapability.TEXT_STREAMING, AiCapability.SYSTEM_PROMPT},
        conversationMemory = true)
public class AiChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

### Client — the bundled Atmosphere Console

Uses the `useChat` hook from `atmosphere.js/react`:

- Connects to `/atmosphere/ai-chat` over WebSocket
- Parses streaming JSON messages and `AiEvent` frames
- Keeps optimistic user and assistant message state in one hook
- Renders streaming texts as they arrive with markdown support
- Shows model name, cost, and latency badges

## Governance as a learning signal

Governance decisions usually flow one way — into an audit log the agent never sees. This sample
closes the loop (the idea from Jason Stanley's *[Governance as a Learning Signal](https://jasonstanley.substack.com/p/governance-as-a-learning-signal)*):
a governance decision is fed back into the model's context, with no retraining.

Two pieces cooperate (`GovernanceFeedbackConfig.java` + the `interceptors` on `AiChat`):

1. **Produce** — `productionReleaseAdvisor`, a native `PreferencePolicy`, matches a
   *"deploy … to production"* question and returns a soft **`Prefer`** advisory — it admits the
   turn but records that Example Corp's release runbook (`release-bot` / `#prod-releases`, CHG
   ticket, second approver) is the preferred path. It is *soft* governance: no hard `Deny`.
2. **Carry** — `GovernanceFeedbackInterceptor` re-injects that advisory into the request's system
   prompt, so the assistant answers with the org process.

On the streaming `@AiEndpoint` path the policy plane runs *before* the interceptor, so a `Prefer`
steers the **same** turn that triggered it (a hard `Deny` terminates its turn, so a denial is
surfaced on the **next** turn from the decision-log ring buffer instead).

**Try it (needs a real LLM — demo mode bypasses the pipeline):**

```bash
LLM_MODE=local LLM_MODEL=qwen2.5:3b LLM_BASE_URL=http://localhost:11434/v1 \
  LLM_API_KEY=ollama ./mvnw spring-boot:run -pl samples/spring-boot-ai-chat
```

Ask **"How do I deploy the billing service to production?"** The answer names the Example Corp
`release-bot` / `#prod-releases` process — tokens the base model cannot know, so they appear
*only* because the advisory was injected. The console's **Decisions** tab shows a `PREFER` from
`production-release-advisor`. The end-to-end proof is `e2e/tests/governance-feedback-chat.spec.ts`.

> Durable recall (opt-in): set `atmosphere.ai.governance.memory.enabled=true` with a
> `LongTermMemory` bean to persist deny/prefer guidance (provenance-tagged, expiry-gated) so it
> survives restarts — off by default, which keeps the loop ephemeral and never persists lessons.

## Session tape (record → train)

This sample turns the **session tape** on (`atmosphere.ai.tape.*` in `application.yml`, with the
`atmosphere-checkpoint` + `sqlite-jdbc` dependencies). Every AI turn is recorded to a durable
SQLite file as an ordered, typed step stream — the input prompt (`input` step), the streamed
text, tool calls, structured output, and the terminal — so each run is a self-contained
`(prompt → completion)` record.

That makes the tape a training set. Extract chat-format JSONL from it with the shipped CLI:

```bash
java -cp <classpath> org.atmosphere.checkpoint.TapeDatasetCli \
  "${TMPDIR}/atmosphere-ai-chat-tape.db" train.jsonl
# -> one {"messages":[{system},{user},{assistant}]} line per COMPLETED run;
#    non-terminal / input-less / output-less runs are skipped and COUNTED (never dropped silently)
```

The JSONL feeds any chat fine-tuner (e.g. MLX-LM `lora`, HuggingFace TRL) to distill a small
local student from a larger teacher's tapes, then serve the student back through the same
`AgentRuntime` SPI by pointing `LLM_BASE_URL` at it. The tape is off by default framework-wide;
this sample opts in to demonstrate it.

## Response cache: exact vs semantic

`PromptCacheDemoChat` (`/atmosphere/ai-chat-with-cache`) routes prompts through a real
`AiPipeline`, whose cache gate serves a stored response instead of calling the runtime. By
default the cache is **exact** — it keys on the request hash, so only a byte-identical prompt
hits.

Setting the framework init-param `org.atmosphere.ai.cache.semantic=true` swaps in a
`SemanticResponseCache`, which also serves a *reworded* prompt whose embedding is within the
cosine threshold (default `0.92`) of a stored one — "what's the weather in Paris?" hits a
response stored for "tell me the Paris weather". Tunable with
`org.atmosphere.ai.cache.semantic.threshold`, `.max-entries`, and
`org.atmosphere.ai.cache.ttl-minutes`.

The semantic cache needs a real embedding backend. With no `EmbeddingRuntime` resolvable the
framework seam declines rather than installing a cache that could never hit, and this endpoint
keeps the exact cache. Either way it reports the resolved choice on the wire as
`prompt.cache.kind` (`exact` | `semantic`) — runtime state, not configured intent.

**Reachability:** the response cache is an `AiPipeline`-layer feature. It covers the pipeline
dispatch paths — `@Coordinator` A2A / AG-UI / channel bridges, and endpoints like this one that
route their `@Prompt` method through a pipeline. It is **not** wired into the plain
`@AiEndpoint` websocket path, which has no response-cache gate.

## Dev inspector (what did the model just see?)

The tape above is the durable, replayable record. The **dev inspector** is its inner-loop
counterpart: a bounded in-memory ring of the last N turns you read while iterating on a prompt.
This sample turns it on (`atmosphere.ai.dev-inspector.enabled: true`, capacity 100).

**What you'll see** — send any message, then read the turn back:

```bash
curl -s -H "X-Atmosphere-Auth: demo-token" \
  'http://localhost:8080/api/admin/ai/dev/inspector?limit=5' | jq
# -> [{ "at": ..., "sessionId": ..., "model": ...,
#       "promptPreview": "...", "responsePreview": "...",
#       "toolCalls": [...], "tokensIn": 0, "tokensOut": 0, "status": "OK", "error": "" }]
```

One entry per completed turn, newest first, capped at 2000 characters per preview. Capture is
installed by the shared dispatch decorator chain, so `@AiEndpoint` streaming turns and
`AiPipeline` turns (channels, A2A, coordinator) are both recorded. `DELETE` the same path with a
write-authorized principal to clear the ring.

**Posture** — entries hold prompt *and* response text, so the read is authenticated like every
other recorded-content admin surface (`/tape/runs`, `/governance/decisions`): anonymous callers
get `401`. A startup `WARN` fires while it is enabled. It is off by default framework-wide and
should stay off in production — this sample opts in to demonstrate it.

## Configuration

Set environment variables before running:

```bash
# Gemini (default)
export LLM_API_KEY=AIza...

# OpenAI
export LLM_MODEL=gpt-4o-mini
export LLM_BASE_URL=https://api.openai.com/v1
export LLM_API_KEY=sk-...

# Ollama (local)
export LLM_MODE=local
export LLM_MODEL=llama3.2
```

## Build & Run

```bash
# From the repository root
./mvnw spring-boot:run -pl samples/spring-boot-ai-chat

# Or via the CLI
atmosphere run spring-boot-ai-chat
```

Open http://localhost:8080 in your browser. The AI Console UI is bundled at
`/atmosphere/console/` (the root path redirects there).

## Authentication

Token-based authentication is **disabled by default** in this sample
(`atmosphere.auth.enabled=false` in `application.properties`) so the bundled
AI Console connects out-of-the-box. The framework default is fail-closed
per Correctness Invariant #6 — the sample-level override is explicit.

To demo the bundled `AuthConfig` token flow, run with auth enabled:

```bash
./mvnw spring-boot:run -pl samples/spring-boot-ai-chat \
    -Dspring-boot.run.arguments="--atmosphere.auth.enabled=true"
```

Then mint a token and use it on the handshake:

```bash
# 1. Mint a demo token
curl -s -X POST http://localhost:8080/api/auth/login \
     -H 'Content-Type: application/json' -d '{"user":"demo"}'
# -> {"token":"demo-token"}

# 2. Use it as a header
curl -i -H 'X-Atmosphere-Auth: demo-token' http://localhost:8080/atmosphere/ai-chat

# Or as a query parameter (works for WebSocket too)
curl -i 'http://localhost:8080/atmosphere/ai-chat?X-Atmosphere-Auth=demo-token'
```

Without `X-Atmosphere-Auth` (and with auth enabled), the handshake returns
`HTTP 401 X-Atmosphere-error: No authentication token provided`.

## OpenAI-compatible endpoint

This sample opts into Atmosphere's OpenAI-compatible serving surface
(`atmosphere.ai.openai.enabled=true` in `application.yml`), so any tool that
speaks the OpenAI wire format — Open WebUI, LibreChat, the OpenAI SDKs,
LangChain's OpenAI client — can call the `ai-chat` endpoint as a drop-in
model named `atmosphere-ai-chat`:

- `POST http://localhost:8080/atmosphere/v1/chat/completions` — non-streaming
  and SSE streaming (`"stream": true`) chat completions
- `GET http://localhost:8080/atmosphere/v1/models` — model discovery (this is
  what Open WebUI uses to populate its model dropdown)

Every request dispatches through the same governed `AiPipeline` as the
WebSocket / channel / A2A surfaces, so guardrails, governance policies,
budgets, and cost accounting apply unchanged. Without an `LLM_API_KEY` the
demo runtime answers with canned text — the wire format works end-to-end
either way.

```bash
# Non-streaming
curl -s http://localhost:8080/atmosphere/v1/chat/completions \
     -H 'Content-Type: application/json' \
     -d '{"model":"atmosphere-ai-chat","messages":[{"role":"user","content":"Hello"}]}'

# Streaming (SSE chat.completion.chunk frames, terminated by data: [DONE])
curl -sN http://localhost:8080/atmosphere/v1/chat/completions \
     -H 'Content-Type: application/json' \
     -d '{"model":"atmosphere-ai-chat","stream":true,"messages":[{"role":"user","content":"Hello"}]}'
```

Or with the OpenAI Python SDK — point `base_url` at `/atmosphere/v1`:

```python
from openai import OpenAI

client = OpenAI(base_url="http://localhost:8080/atmosphere/v1", api_key="unused")
resp = client.chat.completions.create(
    model="atmosphere-ai-chat",
    messages=[{"role": "user", "content": "Hello"}],
)
print(resp.choices[0].message.content)
```

**Scope**: tool/function-calling passthrough is deliberately not supported —
`tools`, `functions`, and tool-role messages are rejected with an
`unsupported_parameter` error (register tools on the agent instead). Sampling
parameters (`temperature`, `top_p`, `max_tokens`) are accepted and ignored;
generation settings are controlled server-side. Client-sent history is
threaded through the endpoint's conversation memory per request; client
`system` messages ride along as history and never replace the agent's own
system prompt.

**Auth posture (honest version)**: in this sample the endpoint is
**unauthenticated out of the box**, because the sample disables token auth by
default (see [Authentication](#authentication)) and no
`atmosphere.ai.openai.api-key` is set — fine for localhost demos, not for
anything reachable from a network you don't trust. Two independent knobs
harden it:

1. `--atmosphere.auth.enabled=true` — the framework `AuthInterceptor` then
   gates this endpoint like every other handler; clients must send
   `X-Atmosphere-Auth: demo-token` (OpenAI SDKs: pass it via
   `default_headers`).
2. `atmosphere.ai.openai.api-key=<key>` — the endpoint then requires standard
   `Authorization: Bearer <key>`, which OpenAI SDKs send natively as their
   `api_key`.

## Project Structure

```
spring-boot-ai-chat/
├── pom.xml
│   └── src/
│       ├── App.tsx                  # Chat UI with useChat hook
│       └── main.tsx                 # AtmosphereProvider wrapper
└── src/main/
    ├── java/.../aichat/
    │   ├── AiChatApplication.java   # Spring Boot entry point
    │   ├── AiChat.java             # @AiEndpoint with capability validation
    │   ├── MultiModalAgent.java    # @Agent — vision + audio input, skill-file persona
    │   ├── AuthConfig.java         # Token-based authentication
    │   └── LlmConfig.java          # Spring properties → AiConfig bridge
    └── resources/
        ├── application.yml          # LLM config (model, mode, API key)
        ├── prompts/                 # @Agent skill files (multimodal-assistant-skill.md)
        └── static/                  # / redirect + multimodal.html demo
```

## See Also

- [AI Tools sample](../spring-boot-ai-tools/) — framework-agnostic tool calling with real-time tool events
- [Dentist agent](../spring-boot-dentist-agent/) — full `@Agent` with commands, tools, and multi-channel
- [Multi-agent startup team](../spring-boot-multi-agent-startup-team/) — 5 agents collaborating via A2A
