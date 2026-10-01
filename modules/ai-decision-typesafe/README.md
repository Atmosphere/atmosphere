# atmosphere-ai-decision-typesafe

A `DecisionModel` (`org.atmosphere.ai.decision`) over the
[TypeSafe](https://docs.typesafe.ai) System One API: a provider whose models
answer typed questions (choice, score, yes/no) with a probability distribution
instead of writing text. The adapter turns a `DecisionRequest` into one
`POST /v1/systemone` call and each answer into the matching typed `Answer`.

## What was verified, and how

**No call with a valid TypeSafe API key has been made from this repository.**
None was available when the module was written. Everything about successful
answers is verified only against fixtures copied from the published
documentation. Here is the split:

| Behaviour | Verified against | When |
|-----------|------------------|------|
| Request body for noul, choice and score (`state`, `model`, `questions.<id>.{type, instructions, criteria}`) | The request examples in `docs.typesafe.ai/api.md`, copied verbatim into `src/test/resources/fixtures/request-*.json` | Docs fetched 2026-10-01 |
| Answer shapes: noul (`noul`), choice (`choice`, `probabilities`, `confidence`), score (`score`, `legend`, `probabilities`, `confidence`), `usage` | The response examples in `api.md`, copied into `fixtures/response-*.json` | Docs fetched 2026-10-01 |
| `GET /v1/models` shape (`{"models":[{"name","description","release_date"}]}`) | The field list in `docs.typesafe.ai/models.md`. `fixtures/models.json` is in that shape, but its values are made up | Docs fetched 2026-10-01 |
| **401** for an invalid key (on both `/v1/models` and `/v1/systemone`), with the body `{"detail":{"error_type":"authentication_error","message":...}}` and an `x-typesafe-request-id` header | **Live**: `TypesafeLiveTest#theRealApiRejectsAnInvalidKey` against `api.typesafe.ai` (`-Dtypesafe.live=true`), plus `curl` | 2026-10-01 |
| **403** when no key is sent, same envelope | **Live**, with `curl` | 2026-10-01 |
| 422, 429, 529 handling | Status codes from `api.md`. The **bodies** in `fixtures/error-422.json`, `error-429.json` and `error-529.json` are **assumed**: none was observed. The adapter reads only the status, the retry headers and a best-effort message, so a different body changes the logged detail and nothing else | not observed live |
| `retry-after-ms` / `Retry-After` header names | The SDK retry reference (`docs.typesafe.ai/sdk/python/api/retries.md`); not observed live | Docs fetched 2026-10-01 |

The live lane `.github/workflows/typesafe-decision-live.yml` runs both
`TypesafeLiveTest` tests: every question type with a real key, and the
invalid-key path. It is **manual dispatch only**. It needs the
`TYPESAFE_API_KEY` repository secret and fails, rather than passing, when the
secret is missing. Until someone dispatches it with a key, the successful-answer
path is fixture-verified only.

## Install

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai-decision-typesafe</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
```

The module is registered in
`META-INF/services/org.atmosphere.ai.decision.DecisionModel`. When its
`isAvailable()` is true, `DecisionModelResolver.resolve()` selects it ahead of
the `RuntimeDecisionModel` fallback over your `AgentRuntime`. That makes it the
model behind the `LLM_CLASSIFIER` injection, scope and moderation tiers (see
`modules/ai/README.md`, *Decision models*).

If it is unavailable when a resolution runs (no key yet, the endpoint down at
boot, a probe timeout) and a real `AgentRuntime` is configured, the resolver
returns the `RuntimeDecisionModel` fallback **provisionally**: one caller
rescans every 30 s (`DecisionModelResolver.FALLBACK_RECHECK_INTERVAL`) and
switches to this model once it answers. Until then the safety tiers ask the
general LLM. The `LLM_CLASSIFIER` injection tier is the exception: it builds
its classifier once with the model it resolved, and keeps it until
`InjectionClassifierResolver.reset()`. A scope or moderation tier built without
an explicit model (as `ScopeGuardrailResolver` and the Spring Boot starters'
`LlmModerationDetector` build them) resolves on every check, so it picks up the
switch. `DecisionModelResolverTest` pins
the recheck.

## Configuration

A JVM system property wins over the environment variable. The environment
variable names are the ones the TypeSafe SDKs read.

| Setting | System property | Environment | Default |
|---------|-----------------|-------------|---------|
| API key | `org.atmosphere.ai.decision.typesafe.api-key` | `TYPESAFE_API_KEY` | none (unavailable) |
| Base URL | `org.atmosphere.ai.decision.typesafe.base-url` | `TYPESAFE_BASE_URL` | `https://api.typesafe.ai` |
| Model | `org.atmosphere.ai.decision.typesafe.model` | `TYPESAFE_DEFAULT_MODEL` | `jev-1.13.0` |

- **Pinned model by default.** `jev-latest` and `jev-preview` are moving
  aliases: TypeSafe documents that the answers behind them can change without a
  change on your side. They are accepted, with an INFO log. Each
  `DecisionResult.model()` reports the versioned id the API says answered.
- **Base URL.** Scheme and host only, because the adapter appends `/v1/...`.
  Plain `http` is accepted only for a loopback host, so the key never crosses a
  network unencrypted.
- **Bad configuration** (a malformed URL or model id, a key with spaces) does
  not throw from the ServiceLoader constructor. The instance is unavailable, logs
  why once, and fails every question with `ERROR`. `TypesafeDecisionModel.builder()`
  configures an instance in code and throws instead. It also sets `priority`
  (default 100), `maxRetries` (default 2, 0..10), `maxConcurrency` (default 8),
  the connect timeout and the availability TTLs.

## Availability

`isAvailable()` is `true` only after `GET /v1/models` returned `200` with the
documented `{"models":[...]}` body. A key being set is not enough. With no key
there is no probe and the answer is `false`. The verdict is cached for 300 s when
reachable and 30 s when not. It is shared by every instance with the same base
URL, model and key (held as a SHA-256 digest, at most 64 configurations), and
only one probe per configuration runs at a time. That matters because each
`DecisionModelResolver` scan's `ServiceLoader` builds a fresh instance: while
nothing is selected, a scope or moderation tier built without an explicit
model resolves on every check, and
with a rejected key or a dead endpoint they would otherwise send one probe per
check. With the shared verdict they send one per 30 s
(`TypesafeDiscoveryTest#aRejectedKeyIsProbedOncePerTtlNotOncePerResolution`). A
`401` or `403` from `decide` marks the configuration unavailable at once.

`DecisionModelResolver` caches the model it selects. A model that later becomes
unreachable is still the one consumers call. Its questions then fail
(`ERROR`/`TIMEOUT`/`CAPACITY`), and every safety tier treats a failed answer as
uncertain, which fails closed by default. When the resolver instantiates a
registration and does not select it, or a higher-priority one displaces it, it
closes that registration.

## Mapping

| Question | Sent as | Answer |
|----------|---------|--------|
| `Question.Noul(instructions, whenTrue, whenFalse)` | `{"type":"noul","instructions",...,"criteria":{"true","false"}}`, sending only the criteria you set | `Answer.Noul(value = p >= 0.5, probabilityTrue = p, confidence = \|2p − 1\|)` |
| `Question.Choice(instructions, options)` | `{"type":"choice","criteria":{option: description or null}}` | `Answer.Choice(choice, probabilities, confidence)` as returned |
| `Question.Score(instructions, levels)` | `{"type":"score","criteria":[levels]}` | `Answer.Score(score, probabilities by level, confidence)` as returned |

Every confidence has source `AiConfidence.Source.PROVIDER_DISTRIBUTION`. For a
noul, the API returns only `p = P(yes)` and no confidence. The adapter derives
`|2p − 1|`, the normalised margin of a two-value distribution. That is the same
quantity `DecisionDistribution.marginOf` gives the value `p >= 0.5` selects. For
choice and score, the number is TypeSafe's own `confidence`. TypeSafe does not
document its exact formula, and its docs illustrate it with the normalised
margin. TypeSafe describes Jev as trained to return calibrated decisions.
Nothing in this repository checks that claim against observed outcomes, which is
why the source is named for where the number came from rather than for its
quality.

**Strict decoding.** These cases are `UNPARSEABLE`:
- a field `api.md` marks required is missing or has the wrong JSON type. For the
  whole reply that is `model` (a non-blank string), `answers` and `usage` (with
  non-negative integer `input_tokens` and `output_tokens`), and every question
  fails. For one answer it is `type` and that type's fields: `noul`; `choice`,
  `probabilities`, `confidence`; `score`, `legend`, `probabilities`,
  `confidence`. Only that question fails;
- a `200` body is not JSON;
- the body is over 4 MiB, which is never buffered past the limit.

These are `INVALID_ANSWER`:
- the answer is out of range: a choice that is not an option, a probability or
  noul outside `[0, 1]`, a score outside `[0, levels − 1]`;
- the distribution does not name exactly the options or levels, or does not sum
  to 1 ± 0.02;
- the `choice` is not the most likely option;
- the `score` differs from `Σ i·p(i)` by more than 0.05;
- the answer `type` does not match the question;
- the score `legend` does not name exactly the levels `"0".."n-1"`. Its
  descriptions are not compared with the ones sent, because the docs do not say
  they come back byte for byte.

A question missing from `answers` fails alone, and the others keep their
answers.

**Bounds.** The question-type limits in `Question` match the API's: 2..255
options per choice and 2..10 levels per score. `DecisionRequest` adds 64
questions and 262,144 characters of state. The API's token budget (64k tokens
per request, 32k for the state plus the longest question) is enforced only by
the server, because the adapter cannot count Jev tokens. An over-budget request
comes back as a `422`, which is `ERROR` and is not retried.

## Failures and retries

All questions in a request share one HTTP exchange, so a failed exchange fails
every question the same way, before `DecisionRequest.timeout()`:

| Outcome | Retried? | `Answer.Failed.Reason` |
|---------|----------|------------------------|
| 408, 429, 5xx (including 529), connection error | Yes, up to `maxRetries`. The wait is `retry-after-ms`, else `Retry-After` (seconds or an HTTP date), else 500 ms doubling to 5 s with up to 25% jitter. A wait that would reach the deadline is not taken | final 429/529: `CAPACITY`; others: `ERROR` |
| 401, 403 | No (also marks the model unavailable) | `ERROR` |
| 422 and other 4xx | No | `ERROR` |
| Deadline passed (in-flight request cancelled) | No | `TIMEOUT` |
| All `maxConcurrency` slots busy until the deadline | No | `CAPACITY` |

Each `detail` names the HTTP status, the provider's message and its
`x-typesafe-request-id`. The API key is never logged.

## Lifecycle

`TypesafeDecisionModel` creates its `java.net.http.HttpClient` on its first
request, owns it and closes it in `close()`. An instance that only reads a cached
verdict never creates one. `close()` is idempotent. A closed model is
unavailable and fails every question with `ERROR`.

The instance `DecisionModelResolver` selects is closed by
`DecisionModelResolver.reset()` (which `InjectionClassifierResolver.reset()`
calls). Nothing else closes it: no framework shutdown hook does, so it lives
until a reset or the end of the JVM. An instance you build yourself is yours to
close. The module adds no third-party runtime
dependency: the DecisionModel SPI, Jackson 3 and SLF4J come from
`atmosphere-ai`.

## As an injection backend

`InjectionClassifierResolver` builds the `LLM_CLASSIFIER` injection tier as
`CompositeInjectionClassifier(RuleBasedInjectionClassifier, LlmClassifierInjectionClassifier(model))`,
so the rule-based floor runs first. A canonical injection ("ignore all previous
instructions…") is dropped without the provider being asked, even if the
provider would have cleared it. The provider is asked only about documents the
rules pass, so it can never clear what the rules flag. `TypesafeDiscoveryTest#asTheInjectionBackendItStaysBehindTheRuleBasedFloor`
pins this against a stub that clears everything.

## Tests

| Test | Needs | What it pins |
|------|-------|--------------|
| `TypesafeDecisionModelContractTest` | nothing (JDK `HttpServer` stub on loopback) | the documented request bodies, every answer type, 401/403/422/429/529/503, retry-after (including waits too large to add to the clock), the deadline, the body bound, the concurrency bound, availability caching and sharing, `close()` |
| `TypesafeWireTest` | nothing | strict decoding (every documented required field), criteria encoding, retry header parsing |
| `TypesafeDiscoveryTest` | nothing | ServiceLoader registration, system-property configuration, resolver selection, the rule-based floor |
| `TypesafeLiveTest` | `TYPESAFE_API_KEY`, or `-Dtypesafe.live=true` for the invalid-key test only | the real API |

```bash
./mvnw test -pl modules/ai-decision-typesafe -am \
    -Dtest=TypesafeLiveTest -Dsurefire.failIfNoSpecifiedTests=false -Dtypesafe.live=true
```
