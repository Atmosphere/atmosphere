# Vision / multi-modal input parity audit — 2026-05-22

Triggered by Cohere Command A+ release (multimodal MoE, Apache 2.0). Cross-checks
whether each `AgentRuntime` actually forwards `AgentExecutionContext.parts` —
`Content.Image`, `Content.Audio`, `Content.File` — into its model request.

Scope: every concrete `AgentRuntime` implementation under `modules/*/src/main/`
that ships today.

## Method

For each runtime:

1. Grep `context.parts()` / `ctx.parts()` and `Content.Image` references in
   `doExecute` / `doExecuteWithHandle` / supporting helpers.
2. Read the runtime's `capabilities()` set to check whether
   `AiCapability.VISION` / `AUDIO` / `MULTI_MODAL` is declared.
3. Trace the request-assembly path to confirm parts actually reach the wire
   (capability declaration alone is not evidence — see
   `feedback_verify_runtime_not_declaration.md`).

## Findings

| Runtime | Module | Declares VISION | Forwards `parts()` to model | Gap |
|---|---|---|---|---|
| Built-in | `modules/ai` | ✅ | ✅ via `OpenAiCompatibleClient` multi-content array | — |
| LangChain4j | `modules/langchain4j` | ✅ | ✅ via `dev.langchain4j.data.message.ImageContent` | — |
| Spring AI | `modules/spring-ai` | ✅ | ✅ via `org.springframework.ai.content.Media` | — |
| ADK (Gemini) | `modules/adk` | ✅ | ✅ via `Part.fromBytes` | — |
| **Anthropic** | `modules/anthropic` | ❌ ("deferred" per source comment) | ❌ no `parts()` handling in `doExecute` | **vision deferred** |
| **Spring AI Alibaba** | `modules/spring-ai-alibaba` | ❌ | ❌ | vision pending |
| **AgentScope** | `modules/agentscope` | ❌ | ❌ | vision pending |
| **Semantic Kernel** | `modules/semantic-kernel` | ❌ | ❌ | vision pending |

Four of eight runtimes are vision-capable; four are not. Every gap is
**honestly declared** — no runtime advertises VISION while failing to forward
image bytes (Correctness Invariant #5 holds).

## Notable: Anthropic

The Anthropic runtime is the most surprising gap because Anthropic Claude is
one of the strongest multimodal providers. The source comment in
`AnthropicAgentRuntime.capabilities()` explicitly calls this out:

> NOT claimed:
>   VISION/AUDIO/MULTI_MODAL — image_block translation deferred

The Messages API supports image blocks via base64 directly — closing this gap
is ~30–50 LOC in `AnthropicMessagesClient.assembleMessages` plus a capability
update plus a contract-test entry. Scoped to a follow-up branch
(`feat/anthropic-vision`) to stay disciplined per
`feedback_no_adjacent_debt_cleanup.md`; this branch documents the gap and
fixes it on Cohere from day 1 instead.

## Action items

- [x] Audit documented (this file)
- [x] `CohereAgentRuntime` ships in this branch (`modules/cohere`)
- [x] End-of-phase vision pass closed all five gap runtimes —
      `2026-05-23` update below.

## Update — 2026-05-23: vision pass closed

The end-of-phase pass landed in the same branch
(`feat/cohere-runtime-and-vision-parity`). All five gap runtimes now
forward `Content.Image` to the model and declare `VISION` + `MULTI_MODAL`
in `capabilities()`. The capability matrix and contract-test pinning
flipped in lockstep — `CapabilitySnapshotTest`,
`validate-capability-claims.sh`, and `regen-skillcards.sh` all green.

Final state (post-pass):

| Runtime | Module | VISION declared + wired |
|---|---|---|
| Built-in | `modules/ai` | ✅ (was ✅) |
| LangChain4j | `modules/langchain4j` | ✅ (was ✅) |
| Spring AI | `modules/spring-ai` | ✅ (was ✅) |
| ADK (Gemini) | `modules/adk` | ✅ (was ✅) |
| Anthropic | `modules/anthropic` | ✅ (was ❌) — native `image_block` (base64 inline) in `userMessageWithParts`; AUDIO not declared (no audio block in Messages API) |
| Spring AI Alibaba | `modules/spring-ai-alibaba` | ✅ (was ❌) — `Media` attached to trailing `UserMessage` via `attachMediaToTrailingUserMessage`; AUDIO declared (Spring AI `Media` carries audio mime types) |
| AgentScope | `modules/agentscope` | ✅ (was ❌) — native `ImageBlock` / `AudioBlock` + `Base64Source` in `attachPartsToTrailingUserMessage`; AUDIO declared |
| Semantic Kernel | `modules/semantic-kernel` | ✅ (was ❌) — `ChatMessageImageContent.withImage(mime, bytes)` appended to `ChatHistory`; AUDIO not declared (SK 1.5.0 has no audio content type) |
| Cohere | `modules/cohere` | ✅ — OpenAI-compatible `image_url` blocks with base64 data URIs |

`AUDIO` parity is intentionally bounded: only runtimes whose upstream
SDK exposes a wire path for audio bytes declare it
(Built-in / Spring AI / LC4j / ADK / Spring AI Alibaba / AgentScope).
Anthropic and Semantic Kernel honestly omit `AUDIO` and drop
`Content.Audio` with a debug log rather than lying about runtime truth
(Correctness Invariant #5).

`PROMPT_CACHING` and `TOOL_CALL_DELTA` remain open on the four runtimes
that lack them — staged for a separate follow-up branch so this pass
stays scoped per `feedback_no_adjacent_debt_cleanup.md`.

## Related invariants

- Correctness Invariant #7 — Mode Parity. Vision is a feature; the SPI
  carries `Content.Image`. Half the runtimes ignoring it is a real parity
  gap, but it's bounded by capability declaration so callers can route
  around it.
- `feedback_primitive_needs_consumer.md` — the SPI primitive
  (`Content.Image`, `AiCapability.VISION`) ships with four production
  consumers today; not zero, so not a paper SPI.
