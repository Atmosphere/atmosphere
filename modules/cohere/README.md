# atmosphere-cohere

Native [Cohere v2 Chat API](https://docs.cohere.com/v2/reference/chat) runtime
for Atmosphere. Posts directly to `POST /v2/chat` via `java.net.http.HttpClient`
— no SDK dependency, no OpenAI-compatible translation layer in between.

## When to use this module

Pick this runtime over the LangChain4j Cohere bridge or the built-in
OpenAI-compatible client when:

- You want Cohere's native SSE event types (`content-delta`,
  `tool-plan-delta`, `tool-call-start`, `tool-call-delta`,
  `citation-start`) so streaming "thinking" tokens and RAG citations can
  render on the client without lossy translation through an
  OpenAI-shaped proxy.
- You are deploying [Command A+](https://cohere.com/blog/command-a-plus)
  (or any Cohere model) on customer infrastructure — 2× H100, 1× B200,
  or any environment that speaks the Cohere v2 wire protocol — and want
  to point the runtime at a sovereign endpoint by overriding
  `cohere.base.url`.
- You want the same SPI promise every Atmosphere runtime keeps: swap
  one Maven dependency, the same `@Agent` sample works.

## Quickstart

Add the dependency to any Atmosphere-based application:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-cohere</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
```

Set the API key via environment variable or system property:

```bash
export LLM_API_KEY=your-cohere-key
# or, equivalently:
java -Dcohere.api.key=your-cohere-key -jar app.jar
```

The runtime registers itself via `ServiceLoader` at priority 100. The
framework picks it up automatically when the jar is on the classpath
alongside a configured key.

## Sovereign / self-hosted endpoint

Command A+ is positioned for self-hosted deployment. Atmosphere honours
two override knobs:

1. **`LLM_BASE_URL` env var** — recognised by every sample (e.g.
   `samples/spring-boot-ai-chat`) and threaded through to the runtime
   via `AiConfig.LlmSettings.baseUrl()`.
2. **`cohere.base.url` system property** — wins outright if both are
   set. Useful when you want to keep `LLM_BASE_URL` pointed at one
   provider and still test a Cohere-specific override.

Example: routing the existing `samples/spring-boot-ai-chat` at a
self-hosted Command A+ deployment:

```bash
export LLM_API_KEY=<your sovereign key, or any value the local
                    endpoint accepts>
export LLM_BASE_URL=https://command-a-plus.internal.example.com
export LLM_MODEL=command-a-plus-05-2026
./mvnw spring-boot:run -pl samples/spring-boot-ai-chat
```

No sample-side code changes — the BYO-endpoint promise is configuration
only, per `feedback_no_per_runtime_samples.md`.

## Capability inventory

Declared via `CohereAgentRuntime.capabilities()`. The list is the
**honest floor** — every entry corresponds to a code path
`CohereChatClient` actually exercises. Capabilities that are
unimplemented are explicitly NOT claimed (Correctness Invariant #5,
Runtime Truth):

| Capability | Status | Notes |
|---|---|---|
| `TEXT_STREAMING` | ✅ | `content-delta` → `session.send()` |
| `SYSTEM_PROMPT` | ✅ | system role threaded into `messages[]` head |
| `STRUCTURED_OUTPUT` | ✅ | pipeline-layer schema injection (system-prompt cue) |
| `TOOL_CALLING` | ✅ | full `tool-call-start` → `tool-call-end` round-trip with `ToolExecutionHelper.executeWithApproval` |
| `TOOL_APPROVAL` | ✅ | every tool dispatch routes through the approval gate |
| `TOKEN_USAGE` | ✅ | `message-end.delta.usage.tokens` → `session.usage()` |
| `CONVERSATION_MEMORY` | ✅ | `assembleMessages(context)` threads history |
| `BUDGET_ENFORCEMENT` | ✅ | pipeline-level decorator taps `session.usage()` |
| `CONFIDENCE_SCORES` | ✅ | pipeline-level cue via SYSTEM_PROMPT |
| `PASSIVATION` | ✅ | history-based; checkpoint module rehydrates `context.history()` |
| `PER_REQUEST_RETRY` | ✅ | `AbstractAgentRuntime.executeWithOuterRetry` wraps `doExecute` |
| `VISION` | ✅ | `Content.Image` translates to an OpenAI-compatible `image_url` block with a base64 data URI (Command A+ / Command A Vision honor this shape) |
| `MULTI_MODAL` | ✅ | Same code path — a single user message interleaves text + image_url blocks |
| `TOOL_CALL_DELTA` | ✅ | `tool-call-delta.delta.message.tool_calls.function.arguments` fragments forward to `session.toolCallDelta(toolCallId, chunk)` so browser UIs render partial tool-argument JSON before the consolidated `AiEvent.ToolStart` frame fires |
| `AUDIO` | ❌ | Cohere v2 chat content array has no audio block; `Content.Audio` is dropped with a debug log |
| `PROMPT_CACHING` | ❌ | The [Cohere v2 Chat API](https://docs.cohere.com/v2/reference/chat) does not document a prompt-caching wire shape — no `cache_control`, no ephemeral block, no top-level TTL field on `messages[]` / `tools[]` / the request root. Declaring `PROMPT_CACHING` against a synthesized wire field would violate Correctness Invariant #5 (Runtime Truth). The capability stays off until Cohere ships an API surface that [`CacheHint`](../ai/src/main/java/org/atmosphere/ai/llm/CacheHint.java) can drive |

See [`docs/audits/vision-parity-2026-05-22.md`](../../docs/audits/vision-parity-2026-05-22.md)
for the cross-runtime parity audit that drove the staging decision.

## Configuration reference

| System property | Env var equivalent | Default | Description |
|---|---|---|---|
| `cohere.api.key` | `LLM_API_KEY` | — | Bearer token for `Authorization` header |
| `cohere.base.url` | `LLM_BASE_URL` | `https://api.cohere.com` | Override for sovereign / self-hosted deployments |
| `cohere.max.tokens` | — | `4096` | `max_tokens` request field |

The `LLM_*` env vars come from `AiConfig.LlmSettings` and are read by
the framework's sample-level bridge (`samples/spring-boot-ai-chat`).
System properties override env-derived values.

## What's NOT in this module

Per [`feedback_no_per_runtime_samples.md`](../../.claude/memory/feedback_no_per_runtime_samples.md):

- **No `spring-boot-cohere-chat` sample.** The existing
  `samples/spring-boot-ai-chat` works against any provider when you set
  `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL`. Cohere is no exception.
- **No `quarkus-cohere-chat` extension.** Same reasoning — runtime
  resolution happens via `ServiceLoader` regardless of the host
  framework.

If you need framework-specific extensions, document them here in a
follow-up README section rather than carving out a dedicated sample
directory.
