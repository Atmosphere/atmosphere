# Sovereign deployment with Atmosphere

A growing set of open-weights LLMs — [Cohere Command A+
(Apache 2.0)](https://cohere.com/blog/command-a-plus), Llama, Mistral,
GPT-OSS — are positioned for **deploy in your own environment**:
two H100s, a B200, a quantized box on-prem. The deployment story is
not "swap the cloud provider"; it's "your model, your infrastructure,
your wire."

Atmosphere's pitch — long-lived streaming connections, WebSocket /
SSE / long-poll fan-out, real-time fan-out at the framework layer —
pairs naturally with that posture: **your model, your infra, our
streaming layer**.

This guide explains how to point any Atmosphere `AgentRuntime` at a
self-hosted endpoint without touching sample code or carving out
per-runtime samples.

## TL;DR

Every Atmosphere `AgentRuntime` reads its API key and base URL from
two layers, in this precedence:

1. **System property** (`<runtime>.api.key`, `<runtime>.base.url`) — wins outright
2. **`AiConfig.LlmSettings`** (`LLM_API_KEY`, `LLM_BASE_URL` env vars) — fallback

To deploy against your own endpoint, change exactly these env vars:

```bash
export LLM_API_KEY=<key your endpoint accepts>
export LLM_BASE_URL=<your endpoint root, e.g. https://command-a-plus.internal.example.com>
export LLM_MODEL=<model identifier your endpoint serves>
```

No sample-side code changes. No new Maven dependency beyond the
runtime adapter for your model family.

## Recipes

### Cohere Command A+ on 2× H100

```bash
# 1. Add the Cohere runtime to your existing app's dependencies:
#       <dependency>
#           <groupId>org.atmosphere</groupId>
#           <artifactId>atmosphere-cohere</artifactId>
#       </dependency>
#
# 2. Configure the endpoint:
export LLM_API_KEY=$(cat /run/secrets/sovereign-key)
export LLM_BASE_URL=https://command-a-plus.internal.example.com
export LLM_MODEL=command-a-plus-05-2026

# 3. Run any existing sample — the runtime resolver picks Cohere
#    automatically (priority 100) because the jar is on the classpath
#    and an API key is set.
./mvnw spring-boot:run -pl samples/spring-boot-ai-chat
```

Why this works: `CohereAgentRuntime.createNativeClient(settings)`
reads `settings.baseUrl()` from `AiConfig.LlmSettings`. The
spring-boot-ai-chat sample's `LlmConfig` already calls
`AiConfig.configure(mode, model, apiKey, baseUrl)` with the env-derived
`baseUrl`. The flow is end-to-end: env → `LlmSettings` → runtime.

### Anthropic-compatible proxy

Same shape, same env vars:

```bash
export LLM_API_KEY=$(cat /run/secrets/proxy-key)
export LLM_BASE_URL=https://claude-proxy.internal.example.com
./mvnw spring-boot:run -pl samples/spring-boot-ai-chat
```

The `anthropic.base.url` system property still wins outright when set,
which is useful for keeping `LLM_BASE_URL` pointed at one provider while
overriding Anthropic specifically.

### OpenAI-compatible self-host (vLLM, TGI, Ollama, llama.cpp)

The built-in `OpenAiCompatibleClient` honours
`LLM_BASE_URL` directly — no runtime swap needed:

```bash
export LLM_BASE_URL=http://vllm.internal.example.com:8000/v1
export LLM_API_KEY=any-string-your-server-accepts
export LLM_MODEL=meta-llama/Llama-3.1-70B-Instruct
./mvnw spring-boot:run -pl samples/spring-boot-ai-chat
```

## What carries over from the cloud sample

Everything Atmosphere adds on top of the model call is wire-agnostic:

- **Streaming transport** — SSE, WebSocket, WebTransport, HTTP/3 long
  poll. Fan-out across browsers, reconnection, broadcaster-managed
  history.
- **Tool calling + approval gates** — `@AiTool` and
  `@RequiresApproval` work identically on any runtime that declares
  `TOOL_CALLING` — every runtime; all twelve declare it.
- **Token usage + budget enforcement** — `AiBudget` clamps spend per
  call regardless of which runtime is dispatching.
- **Passivation** — durable agents survive process restarts via the
  `CheckpointStore`; the runtime rehydrates conversation history.

## What changes by runtime

| Surface | Built-in (OAI-compat) | Cohere | Anthropic |
|---|---|---|---|
| Wire format | OpenAI Chat Completions | Cohere v2 Chat | Anthropic Messages |
| Tool calling | `tool_calls[]` (OpenAI shape) | `tool-call-*` SSE events | `tool_use` content blocks |
| Streaming events | `chat.completion.chunk` | `content-delta`, `tool-plan-delta`, `citation-*` | `content_block_delta`, `message_delta` |
| Auth header | `Authorization: Bearer <key>` | `Authorization: Bearer <key>` | `x-api-key: <key>` |
| `LLM_BASE_URL` honoured? | ✅ | ✅ | ✅ |

All three honor `LLM_BASE_URL`. The provider-scoped system properties
(`cohere.base.url`, `anthropic.base.url`) still take precedence when set
— useful for keeping a generic `LLM_BASE_URL` pointed at one provider
while overriding another specifically.

## Production hardening checklist

- [ ] **TLS terminator in front of your endpoint** — Cohere and
      Anthropic clients use Java's default `HttpClient`, which
      requires TLS for non-loopback hosts. Provision a real cert; do
      not run the production stream over plaintext HTTP.
- [ ] **Outbound auth on the sovereign endpoint** — your self-hosted
      LLM should validate `Authorization: Bearer <key>`; do not rely
      on network ACLs alone (defense in depth).
- [ ] **Atmosphere auth on the streaming endpoint** —
      `atmosphere.auth.enabled=true` is the fail-closed default. Set
      `ATMOSPHERE_AUTH_TOKEN` per Correctness Invariant #6.
- [ ] **Connection limits + queue caps** — `Broadcaster` capacity and
      `BroadcasterCache` size bounds must match your endpoint's
      throughput envelope. Unbounded queues fed by external input are
      a DoS vector (Correctness Invariant #3).
- [ ] **Budget gates per tenant** — `AiBudget` enforces per-call
      ceilings; the streaming-text budget manager enforces cumulative
      tenant ceilings. Pair them.
- [ ] **Cancellation parity** — every runtime in this matrix declares
      `CANCELLATION`. Confirm your endpoint honors connection close on
      mid-stream cancel; some self-hosted servers keep generating
      after the client disconnects.

## Related reading

- [`modules/cohere/README.md`](../modules/cohere/README.md) — Cohere
  runtime details
- [`modules/anthropic/`](../modules/anthropic/) — Anthropic runtime
- [`docs/audits/vision-parity-2026-05-22.md`](./audits/vision-parity-2026-05-22.md)
  — multi-modal input parity audit; informs which runtimes can carry
  image inputs to a sovereign endpoint today
