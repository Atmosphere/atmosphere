# AI-Assisted Engineering — Drift Log

Append-only record of every agent claim that diverged from ground truth (the
code, the git history, or the runtime state). The diff-reviewable curve of
hallucinations-over-time — the **verification** rail of the harness pattern
documented by Anthropic ([*Effective harnesses for long-running agents*](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents),
2025-11-26) and OpenAI ([*Harness engineering*](https://openai.com/index/harness-engineering/)),
applied here to AI prose claims about this repo. The metric this log records
is not utilization, not "% of code AI-authored," but **change failure rate
by agent claim**.

**Format per entry:**

- **date** — when the drift was caught
- **session** — what the agent was doing when the drift surfaced
- **claim** — exactly what the agent stated
- **truth** — what the code or repo state actually said
- **slip path** — how the drift bypassed existing gates
- **gate added** — the regression-class fix (validator, test, memory update,
  prose grep). `none` is a legitimate value — not every drift admits an
  automated gate, and writing `none` is honest record.

The log is **append-only**. Don't edit older entries to correct or soften
them; write a new entry that points back if the context changes.

---

## 2026-05-08 — Capability-matrix snapshot session

The morning of building `.harness/capabilities.snapshot.json` and the
companion validator surfaced eight distinct factual drifts and two
behavioral anti-patterns. Root cause for the factual half: project memory
files (`project_orchestration_primitives.md`, `project_quarkus_parity_gap.md`,
`project_webtransport.md`, `phase_roadmap_blockers.md`) dated 2026-03-30 to
2026-05-03 were quoted as fact without verifying against the post-4.0.44
codebase. The 4.0.41 → 4.0.44 sprint shipped roughly two months of features
in nine days; every project memory written before 4.0.44 was at risk and at
least four were actively wrong.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 1 | Quarkus extension ships 1 `@BuildStep` | **14** in `AtmosphereProcessor.java` (`grep -c "@BuildStep"`) | Quoted `project_quarkus_parity_gap.md` (audit dated 2026-05-03, already 5 days stale at session time) without verifying against current source | Memory rewritten with verified count; this drift log added |
| 2 | 6 framework runtimes | **9** — BuiltIn, SpringAi, LangChain4j, ADK, Embabel, Koog, AgentScope, SpringAiAlibaba, SemanticKernel | `project_orchestration_primitives.md` (2026-03-30) listed 6; missed AgentScope, Spring AI Alibaba, Semantic Kernel which landed in 4.0.43–4.0.44 | `.harness/capabilities.snapshot.json` + `CapabilitySnapshotTest` (modules/ai-test) + `scripts/validate-capability-claims.sh` (wired into pre-push Tier 1) |
| 3 | `AiCapability` enum has ~17 entries | **20** — `BUDGET_ENFORCEMENT`, `CONFIDENCE_SCORES`, `PASSIVATION` shipped in 4.0.44 (same-day) | Same memory staleness | Same as #2; snapshot pins the enum count |
| 4 | Approval Gates: PENDING | SHIPPED — `RequiresApproval`, `ApprovalStrategy`, `VirtualThreadApprovalStrategy`, `ApprovalRegistry`, `ToolApprovalPolicy` all on disk + `AdkToolBridgeApprovalTest` exercising the path | Memory dated 2026-03-30 listed feature as PENDING; did not `git grep` code before quoting | Memory rewritten with disk-verified state |
| 5 | Long-Term Memory: PENDING | SHIPPED — `LongTermMemory`, `LongTermMemoryInterceptor`, `SemanticRecallInterceptor`, `InMemoryLongTermMemory` + `*Test.java` for each | Same as #4 | Same as #4 |
| 6 | TOOL_APPROVAL on 5 runtimes (Built-in, Spring AI, LC4j, ADK, Koog); SK and Embabel "excluded" | **7 runtimes** — adds Embabel + SK; the actual exclusions are AgentScope + Spring AI Alibaba (no native tool-call dispatch loop) | Stale narrative in `atmosphere.github.io/.../reference/ai.md:498` predating Embabel and SK adopting TOOL_APPROVAL | Website prose corrected (`13fe8c4`); validator pattern `\bAll \d+ runtimes\b` does not catch enumerations like this — flagged as out-of-scope for the validator |
| 7 | "the other **six** runtimes" lack native `COMPLETE_WITHOUT_TOOLS` | **seven** — 9 total minus Built-in + Koog (which handle it natively) = 7 | Off-by-one in `modules/ai/README.md` predating recent runtime additions | Prose fixed in same commit as snapshot infrastructure (`d22d18a7cd`); validator only catches `All N runtimes` pattern, not subset descriptors |
| 8 | "**seven of nine** runtimes consume the gateway" with AgentScope + Alibaba listed as not-yet | **All 9** call `admitThroughGateway` (verified per-runtime via grep) | Stale narrative in `tutorial/26-foundation-primitives.md` predating gateway adoption on the two newer runtimes | Website prose corrected (`13fe8c4`); manual grep confirmed each runtime's call site |

### Behavioral anti-patterns

| # | Pattern | Cost | Gate added |
|---|---|---|---|
| 9 | "Did not push / Did not X / Did not Y" enumeration in reports as defensive shield masquerading as transparency | the project maintainer had to override with "push we are wasting time"; the `feedback_no_pr_direct_merge.md` "ask first" clause was the upstream cause | `feedback_no_did_not_listing.md` added; `feedback_no_pr_direct_merge.md` rewritten to remove the "ask first" friction when work is under explicit Go instruction |
| 10 | `ScheduleWakeup` invoked as "ping me when Maven build finishes" | Wasted a wakeup slot; `feedback_no_misuse_schedulewakeup.md` already covered this exact misuse 1 day ago and was ignored | None — pre-existing rule applies; this is a repetition of a known failure mode. Recurrence to be revisited if it happens again |

### Regression caught by CI

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 11 | The `wall-clock budget scheduled-task fix` would not break any existing test | `AiPipelineBudgetTest.wallClockBudgetTripsOnNextCallAfterDeadline` failed on JDK 21 + JDK 26 CI: the existing test asserted `observed > limit` (strict greater-than), but the new scheduled-task path fires precisely at the deadline so `observed == limit` on a fast scheduler | Wrote the fix without re-reading the existing test's assertion shape; ran the new regression test locally (which passed because the new test used `>=`) but did not re-run the full module test suite locally before pushing | Existing test's assertion loosened from `>` to `>=` (commit `09b2d2b6`); the gate already worked — JDK 21/26 CI matrix caught it within 12 min of push |

---

## 2026-05-11 — InfoQ Java news roundup follow-up + Spring AI 2.0.0-M6 bump

Working through the "worth tracking" items from the InfoQ Java News
Roundup May 04 2026 (CVE-2026-39852, Spring AI M6, Quarkus Agent MCP).
CVE verification surfaced one doc-version drift caught against the
`pom.xml` ground truth.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 12 | `AGENTS.md:327` and `samples/README.md:5` claimed Quarkus extension "tested on 3.31.3"; this also appeared verbatim in `MEMORY.md` ("tested on 3.31.3") | `pom.xml:1196` and `modules/quarkus-extension/pom.xml` pin `quarkus.version=3.35.2`; sample table rows (`samples/README.md:13–14`) already listed 3.35.2 correctly | When the Quarkus version was bumped from 3.31.3 → 3.35.2 (commit history not chased), the bumper updated `pom.xml` and the sample table rows but missed the two narrative-prose mentions and the inline memory quote. Three weeks of partial truth followed | `docs: refresh Quarkus version reference to 3.35.2` (commit `98b047419a`) — prose lines fixed. No automated gate added: doc-version drift against a `pom.xml` property has no clean structural check beyond "update narrative docs when bumping a version property," which is already convention. Manual surface scan (`git grep "3.31.3"` post-bump) is the only honest gate, and it is already implied by the existing pre-push validation discipline |
| 13 | After the Spring AI 2.0.0-M2 → 2.0.0-M6 bump (commit `7dccf49cf4`), `AtmosphereSpringAiAutoConfiguration#atmosphereChatClient` provided only the sync `OpenAIClient` via `OpenAiChatModel.builder().openAiClient(...)`; local 69 unit tests passed so I declared the migration verified | Spring AI 2.0.0-M6 `OpenAiChatModel$Builder.build()` requires **both** sync and async OpenAI clients; missing the async client falls back to `OpenAiSetup.setupAsyncClient(null, ...)` which throws `IllegalStateException("At least one credential source must be specified: credential (apiKey), workloadIdentity, or adminApiKey")` at bean-construction time. CI: CLI Tier-2 boot test failed on spring-ai overlay: "server did not boot within 300s" with that exception in the cause chain | Local unit tests mock `ChatClient` and never construct an `OpenAiChatModel` end-to-end, so the broken bean wiring was invisible to the unit suite. The Spring AI auto-config canonical pattern (`OpenAiChatAutoConfiguration#openAiChatModel`, decompiled from `spring-ai-autoconfigure-model-openai-2.0.0-M6.jar`) wires both clients via `OpenAiSetup.setupSyncClient` + `setupAsyncClient`. I missed reading that source before writing my own wiring | `fix(spring-ai): provide both sync + async OpenAI clients to OpenAiChatModel` (commit `9e63c390e5`) — bean wiring now mirrors the canonical auto-config: both clients constructed via `OpenAiSetup` and handed to `OpenAiChatModel.builder()`. Gate: CI: CLI Tier-2 boot test reproduces the failure and now blocks regression. No new local-test gate added because the 300s end-to-end boot smoke is the right test layer; adding a Spring context smoke locally is feasible follow-up if this recurs |
| 14 | Pre-push CI on 4.0.45-SNAPSHOT (commit `9e63c390e5`) reported "8 workflows green" and I treated CI as fully green for the spring-ai bump | The previous push to `90b7eea1` ran 12 workflows including CI: CLI which exercised the spring-ai overlay end-to-end. The follow-up push to `9e63c390e5` (containing the supposed fix) ran only 8 workflows — CI: CLI, CI: Benchmarks, CI: Native Image, Publish: Website did not trigger because their path filters did not include `modules/spring-ai/**`. So the fix was unverified by CI even though the green count looked complete | The `.github/workflows/cli-e2e.yml` `paths:` filter listed `cli/**`, `samples/**`, `generator/**`, `modules/spring-boot-starter/**`, `.github/workflows/cli-e2e.yml` — missing `modules/spring-ai/**` and the other 7 runtime modules that the Tier-2 boot test actually exercises (`langchain4j`, `adk`, `koog`, `embabel`, `semantic-kernel`, `agentscope`, `spring-ai-alibaba`, `ai`). This is exactly the failure mode CLAUDE.md `### Testing & CI Quality Gates` calls out: "Workflow path filters MUST include all behavior-affecting scripts/config locations for the feature area" | `ci(cli): widen path filter to cover all runtime modules` (commit `40b99eaf67`) — filter now lists every runtime module the CLI Tier-2 test boots. Plus a manual `gh workflow run cli-e2e.yml` against the fix commit to confirm the bean-construction regression is gone. No automated gate beyond the filter widening; spotting filter gaps requires comparing "workflows that ran on commit X" vs "workflows that should run on commit X," which is a manual review at present |
| 15 | Drift #13's gate description claimed the dual-client bean fix in commit `9e63c390e5` resolved the bean-construction failure | Commit `9e63c390e5` passed `null` for the `Duration timeout` and `0` for `int maxRetries` parameters to `OpenAiSetup.setupSyncClient(...)`. The Kotlin OpenAI SDK's `OpenAIOkHttpClient$Builder.timeout(Duration)` is marked non-null and throws `NullPointerException("Parameter specified as non-null is null")` at `OpenAiSetup.java:113`. CI: CLI Tier-2 (run `25687897649`, manual re-trigger on `40b99eaf67`) failed: `BeanInstantiationException: Factory method 'atmosphereChatClient' threw exception` with the NPE in the cause chain. So drift #13's gate description was already obsolete by the time it was written | Two-fold slip: (a) I read `OpenAiSetup.setupSyncClient`'s parameter list from javap and noted timeout/maxRetries types, but did not check that the Kotlin SDK's `Builder.timeout/maxRetries` setters reject null/0; (b) drift #13's claim "Gate: CI: CLI Tier-2 boot test reproduces the failure and now blocks regression" was speculative — I asserted the gate before the CLI test result on the fixed commit was in hand. The first slip is a domain-knowledge gap on Kotlin null safety in the OpenAI Java SDK; the second slip is a discipline gap: claim-then-verify, not verify-then-claim | Real fix lives in this commit: pass `AbstractOpenAiOptions.DEFAULT_TIMEOUT` and `AbstractOpenAiOptions.DEFAULT_MAX_RETRIES` to both `setupSyncClient` and `setupAsyncClient`. **New regression gate**: `AtmosphereSpringAiAutoConfigurationTest` (this commit) directly invokes the `@Bean` factory method `atmosphereChatClient(...)` with a dummy non-blank API key. The previous bug shape (NPE / IllegalStateException at bean construction) now breaks the build inside `modules/spring-ai` test phase — long before CI: CLI runs. This is the local-smoke gate drift #13 listed as "feasible follow-up if this recurs"; it recurred, gate is now in place |
| 16 | `.harness/README.md` opened with "Atmosphere's instance of Reock's DX impact-metric framework (InfoQ, *AI-Assisted Engineering*, 2026-05)" and listed "Justin Reock, *AI-Assisted Engineering* — InfoQ, 2026-05" under *Further reading*; the same attribution propagated into `.harness/drift-log.md` preamble + footer and `CHANGELOG.md` *[Unreleased]* (line 13: "Atmosphere's instance of Reock's DX impact-metric framework"). Also asserted "+20% / −20% from AI" as a Reock-attributed productivity stat | the project maintainer flagged the attribution as wrong. The "InfoQ *AI-Assisted Engineering* 2026-05" citation was unverified and treated as authoritative; the "+20% / −20%" stat was an unanchored figure. Both violate `feedback_no_fabricated_stats.md` and the "No Hallucinations" rule in CLAUDE.md | Composed the harness README prose from a plausible-sounding AI-engineering narrative without verifying the citation against InfoQ; the framing then propagated into `drift-log.md` preamble + footer (same session, copy-paste shape) and into the `CHANGELOG.md` [Unreleased] entry announcing the harness. Classic "metastasized across multiple files" pattern called out under CLAUDE.md *No Hallucinations* → *When you catch yourself hallucinating* | Reock attribution and the InfoQ citation removed from README + drift-log preamble + drift-log footer + CHANGELOG [Unreleased]; "+20%/−20%" stat removed (no verifiable source). **No automated gate** — fabricated citations are not structurally detectable by grep or schema check; only human review catches them. Discipline going forward: every external citation in `.harness/*` prose must point to a URL/repo that returns 200, not a paper title quoted from memory. **See #17 for the sub-drift on the replacement reference.** |
| 17 | First repair of #16 re-anchored the README, drift-log preamble + footer, and CHANGELOG on [`walkinglabs/learn-harness-engineering`](https://github.com/walkinglabs/learn-harness-engineering) and described it as "the five-subsystem harness-engineering framework (Instructions, State, Verification, Scope, Lifecycle)" — citing it as the canonical framework reference | the project maintainer flagged it: "The URL is a tutorial no?" Verified via `gh api repos/walkinglabs/learn-harness-engineering`: the repo's own description is literally **"Harness engineering official style beginner tutorial, from 0 to 1"**, and its README lists its upstream sources as Anthropic's [*Effective harnesses for long-running agents*](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents) (Justin Young, 2025-11-26), Anthropic's [*Harness design for long-running application development*](https://www.anthropic.com/engineering/harness-design-long-running-apps), and OpenAI's [*Harness engineering: leveraging Codex in an agent-first world*](https://openai.com/index/harness-engineering/). The five-subsystem decomposition is the tutorial's *teaching synthesis* of those primary sources, not a canonical framework spec. Citing a tutorial as the framework reference is the same drift class as #16: trusting a plausible-shaped reference without verifying its provenance | Pulled the existing tutorial link out of the README's *Further reading* (already there from an earlier session) and promoted it to the primary anchor without inspecting what the linked repo actually was. The repo name `learn-harness-engineering` contains "learn-" — a tell I should have read; the repo's own description and its README both flag the tutorial framing explicitly. Compound failure: fixing #16 by reaching for the nearest-already-cited reference instead of going to the actual upstream sources | README + drift-log preamble + CHANGELOG [Unreleased] re-anchored to the primary sources (Anthropic Nov-2025 post + companion + OpenAI harness engineering article). Tutorial moved back into *Further reading* with an honest label ("beginner tutorial / course … synthesises the above sources … useful as onboarding read; canonical references are the Anthropic and OpenAI posts above"). **No automated gate** for the same reason as #16: citation-as-tutorial-vs-spec is a provenance judgement, not a grep target. Discipline going forward: when re-anchoring a hallucinated reference, verify the *replacement* — repo description, primary-source URL, publication date — before promoting it, instead of trusting prior in-tree usage |
| 18 | When asked to verify the top-level `README.md` reflects the implementation 100 %, I initially reported "everything checks out." Re-grep against `pom.xml` then surfaced four narrative copies still saying **"Spring AI 2.0.0-M2"**: `README.md:135` (adapter table row), `modules/spring-ai/README.md:85` (requirements section, "Spring AI 2.0.0-M2+"), `modules/ai/README.md:62` ("Forcing Spring AI 2.0.0-M2 across the classpath today fails…"), and `modules/ai/README.md:278` ("Spring AI 2.0.0-M2 exposes `OpenAiChatModel.Builder.toolExecutionEligibilityPredicate(...)`") | `pom.xml:1199` pins `<spring-ai.version>2.0.0-M6</spring-ai.version>`; the bump landed earlier today in commit `7dccf49cf4`. The bumper updated the pom property and the auto-config Java code, but the four narrative-prose mentions across three READMEs were not refreshed. Identical drift class to #12 (Quarkus 3.31.3 → 3.35.2 prose drift, same week) | The Spring AI M6 bump session focused on auto-config wiring (drifts #13–#15 trace the bean-construction iterations); the user-facing narrative README rows were never inspected. My initial "verified 100 %" claim in this session also skipped a `git grep '2\.0\.0-M2'` sweep across all READMEs — same shortcut that produced drift #12. Two recurrences of the same pattern (narrative-prose lagging a `pom.xml` version property) within one week | `docs(readme): refresh Spring AI version references to 2.0.0-M6` (this commit) — all four narrative mentions updated; only `.harness/drift-log.md` historical record retains the "2.0.0-M2" string, which is correct (drift-log is append-only history). **Gate**: `none`, matching the precedent set by #12. Recurrence in one week is a signal that a structural check may now be worth the investment; tracked as proposed follow-up `scripts/check-readme-pom-version-alignment.sh` (grep `pom.xml` `*.version` properties, fail when an older version string of the same artifact appears in any `*.md`). Not added in this commit to keep the bundle scoped to the prose fix |
| 19 | `atmosphere.github.io/website/src/components/Atmosphere.astro:22` listed the A2A protocol URL as `https://google.github.io/A2A/` with label "Google A2A Spec" — same URL referenced from the home-page pillar modal | `curl -sI https://google.github.io/A2A/` returns **HTTP 404**. The A2A spec moved out of Google ownership: `https://a2aproject.github.io/A2A/` returns 301 → `https://a2a-protocol.org/` (200), under Linux Foundation stewardship | Site copy authored when the spec lived under Google's GitHub org; never refreshed when the project graduated to the Linux Foundation. The post-#12 grep-after-bump discipline only covered version properties in `pom.xml`, not external URLs referenced from sibling repos. Same drift class as #12 (narrative trailing upstream change) but failure mode is "external link rot," not "pom property mismatch" | `docs(website): fix 4 drifts caught by cross-check against atmosphere repo` (commit `5404f5f` on `atmosphere.github.io`) — URL bumped to `https://a2a-protocol.org/`, label trimmed to "A2A Spec", caption notes Linux Foundation stewardship. **No automated gate** in this commit. Proposed follow-up: `scripts/check-website-urls.sh` that `curl -sI`s every external URL referenced from `website/src/components/*.astro` and fails the build on any non-2xx response. Tractable because URLs are extractable with a regex; left out of this bundle to keep scope contained |
| 20 | `atmosphere.github.io/website/src/components/CodeExample.astro:76` capability section read "Spring Boot 4.0 and Quarkus **3.21+** auto-configuration included" | `pom.xml:1196` pins `<quarkus.version>3.35.2</quarkus.version>`; top-level `README.md` says "Quarkus 3.35.2+" after the drift-#12 fix. The "3.21+" was accurate as the original minimum target when the extension was authored against Quarkus 3.21, and never refreshed when the version property was bumped to 3.35.2 | Same class as #12 (narrative trailing `pom.xml` bump) but the prose lives in the sibling repo `atmosphere.github.io`. The post-#12 surface scan ran `git grep "3.31.3"` in the main `atmosphere` repo only — `atmosphere.github.io` was outside the loop. Two-repo discipline gap, not a content gap | Prose fix in commit `5404f5f` on `atmosphere.github.io` — bumped to "Quarkus 3.35.2+". **No automated gate** in this commit. Proposed follow-up: extend `scripts/pre-push-validate.sh` (main repo) to warn when `pom.xml` version properties change without a paired edit in `~/workspace/atmosphere/atmosphere.github.io`. Path-aware reminder, not a hard gate; sibling-repo coupling is loose by design |
| 21 | `atmosphere.github.io/website/src/components/Atmosphere.astro:10` WebTransport pillar said "Atmosphere is the **first Java framework with native WebTransport support**" | Unverified superlative. No on-disk source substantiates "first"; I did not survey Spring / Quarkus / Helidon / Vert.x for WebTransport support before letting the phrasing ship to the public website. The claim may be substantially true (the WebTransport handler/processor/session in `modules/cpr/src/main/java/org/atmosphere/webtransport/*.java` is a real native implementation; I have not seen another Java framework bundle one), but `feedback_readme_honesty.md` says top-of-repo docs cannot oversell without verification | Site copy authored without a verification step against competing frameworks. Same posture as #16 — plausible-sounding narrative shipped without anchoring to a source. Different from #16 in that the claim might actually be true; the failure is shipping the assertion without the evidence | Softened in commit `5404f5f` on `atmosphere.github.io` — phrasing now reads "native WebTransport support … auto-detected via AsyncSupport with zero-config Jetty 12 QUIC or Reactor Netty sidecar" (drop "first Java framework with"). **No automated gate**: superlatives are categorically unverifiable from grep; the only honest discipline is "if you cannot cite a survey, do not say 'first'." Same unautomatable check class as #16, #17 |
| 22 | `atmosphere.github.io/website/src/components/WhyAtmosphere.astro` said "Enterprise support since **2014**" in three places: the `atmosphereOnlyFeatures` value-prop list, the `lockInStory` "with" column (line 52), and the `lockin-lead` paragraph (line 279) | the project maintainer confirmed the correct year is **2013**. The 2014 figure was unverified business fact. The main `atmosphere` repo's `About.astro` correctly says open source since 2008 (project birth, 18 years in production at 2026); Async-IO LLC's commercial-support start year is a separate business date that the repo does not pin | Business fact about Async-IO commercial-support start was never confirmed against the company's actual incorporation / first-paid-contract date before being committed to public site copy. No on-disk source can verify it — only the project maintainer can | Prose fix to "2013" in 3 places, commit `5404f5f` on `atmosphere.github.io`. **No automated gate** is feasible: there is no repo-side source of truth for Async-IO's commercial-support start date. Proposed follow-up: add a small `.harness/async-io-timeline.json` ({ commercial_support_since: 2013, project_open_source_since: 2008, ... }) so future website edits can cite a single pinned source instead of free-prose-ing the years. Until then, business dates on the website must be cleared with the project maintainer before shipping |

### Process miss

The drift-log entry is being appended in a **separate commit** from the
prose fix (`98b047419a`) rather than bundled per the "How to append"
discipline at the bottom of this file (step 6: "Bundle log update + gate
+ prose fix in a single commit"). Reason: the prose fix shipped earlier
in the session as part of clearing the tree before the Spring AI 2.0.0-M6
worktree work; the drift-log requirement surfaced via Stop hook only after
the prose commit had already landed on `main`. Future fix: when an in-flight
session catches a prose-only drift, append the drift-log entry **before**
the prose commit so the bundle stays atomic.

Drifts **#19–#22** are also append-separate-from-prose-fix, but for a
different reason. The prose fix lives in the sibling repo
`atmosphere.github.io` (commit `5404f5f` on that repo's `main`), while
the drift-log lives in this repo. The "single commit" discipline is
physically impossible across two repos; the closest honest equivalent
is **append the drift-log entry in the main `atmosphere` repo in the
same session, pointing at the sibling-repo commit hash**, which is
what this commit does. Stop hook caught the missing log entry and
forced the append before session close — exactly the loop the hook
was added for. Future fix for cross-repo drift: append the drift-log
entry **before** pushing the sibling-repo prose fix, so the two land
within seconds of each other and the cross-reference is forward-going
(`5404f5f` referenced from a not-yet-pushed local drift-log edit),
not backward-going (drift-log edit referencing an already-pushed
sibling commit hash).

---

## 2026-05-11 — JS resilience hooks session: state-machine + flake + claim-vs-truth drifts

Working through the consumer-side ConnectionStatus / Badge wiring across
atmosphere.js, framework hooks, samples, and the admin console. Five drifts
caught in-session — three were *my* slips, two were verification wins
where I challenged a claim and the verification flipped it.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 23 | `ConnectionStatus.wrap()`'s `reopen` handler set `lastEvent='reopen'` but left `phase='reconnecting'` because `markEvent` does not touch phase. Initial tests + first browser screenshot showed `phase=open` so I assumed the steady-state transition was correct | Chrome-devtools E2E against `embedded-jetty-websocket-chat` (kill server → restart → observe Badge) showed `phase=reconnecting, event=reopen` *after* the connection had re-established. Some transports emit only `reopen` on reconnect (no preceding `open`), so the phase machine left the Badge stuck on "Reconnecting…" forever | My unit-test coverage only exercised the `open` event for transitions, not `reopen` in isolation. The state-machine design assumed `open` would always fire on reconnect (true for WebSocket, not true universally) | Fixed at `atmosphere.js/src/resilience/connection-status.ts:96-108` (commit `e7f59714aa`): `reopen` now also drives `phase: 'open'`. **Gate**: new vitest case `reopen-only reconnect (no preceding open) still transitions phase back to open` in `connection-status.test.ts` pins the regression class |
| 24 | "Branch is merged (double check) so you can delete it" — about `feat/http3-coverage`. I would have deleted it on the user's authorisation alone if I trusted the claim | `git merge-base --is-ancestor origin/feat/http3-coverage origin/main` returned non-zero. Six unmerged commits: `45de54559f`, `85670a039e`, `2dcee39919`, `61a6c3157f`, `135dfd9a98`, `0040bb0f1b` — substantial work including a `fix(webtransport): server bind regression` and 26 new unit tests | The "double-check" instruction was the safety net. The right discipline is to always verify branch-merge status with `git merge-base --is-ancestor` before deleting any remote branch, regardless of how the user phrased the authorisation. Verification beats memory beat verbal authorisation in this class of action | **No code gate** — branch-deletion verification is a procedural discipline, not a structural check. Captured here as a habit pin; the user-facing reply quoted the six unmerged SHAs and stopped the deletion. (User then directed: merge first, delete after.) |
| 25 | Pre-push validation marker is shared across all checkouts of the same repo — first squash-merge attempt from main checkout failed with "No validation marker found" even though validation had just run green in the worktree | The marker lives in `<repo>/.git/worktrees/<worktree-name>/validation-passed`, not in `<repo>/.git/validation-passed`. The pre-push hook resolves `git rev-parse --git-dir` per checkout, so a marker stamped in a worktree only satisfies pushes from that worktree | Lost ~50 minutes to two unnecessary re-validations (15 min each) before figuring out that pushing directly from the worktree via `git push origin <worktree-branch>:main` reuses the existing marker and avoids the rebase loop | Memory addition: `reference_pre_push_marker_per_worktree.md` (added to project MEMORY.md in this session) documents the refspec-push pattern. **No code gate** — this is a workflow discipline, not a structural property of the codebase |
| 26 | `AiStreamingSessionCancelInflightTest.cancelInflight_cancelsRuntimeExecutionHandleFromAnotherThread` was treated as a stable invariant after the disconnect-cancel work landed (`9300428c6c`) | Test has timed out at the 2-second `streamDone.get(...)` wall twice in four days under loaded CI runners: 2.023s on JDK 21 (2026-05-07), 2.017s on JDK 26 (2026-05-11). Both slips are <30ms past the cutoff — classic VT-scheduling jitter, not a regression in the cancellation logic itself | The test's 2s timeouts were tight by design — they fail fast if the VT never returns. But the design didn't account for GitHub-hosted runner load adding scheduling jitter to virtual-thread cancellation. Per `feedback_no_flaky_tests.md` the right move is to fix it, not quarantine | Both timeouts bumped to 10s in `modules/ai/src/test/java/org/atmosphere/ai/AiStreamingSessionCancelInflightTest.java:103,111` (this commit). **Gate property**: if `cancelInflight` actually regresses, the parked VT never returns and any timeout fires — the assertion stays meaningful; the 10s margin only protects against scheduling jitter. Drift-log enforces visibility so the next jitter recurrence triggers a structural fix (custom VT-scheduler in test, or `Awaitility` poll instead of `Future.get`) rather than another timeout bump |
| 27 | After the `feat(samples): unified ConnectionStatusBadge for gRPC, A2A, and AG-UI transports` push I claimed "every sample with a frontend renders the unified Badge" | Three frontends — `grpc-chat`, `spring-boot-a2a-agent`, `spring-boot-agui-chat` — were not wired. They use atmosphere.js for *visual chat primitives only* (ChatLayout / MessageList / ChatInput); the wire is Connect-RPC / JSON-RPC SSE / AG-UI SSE respectively. The first claim was correct narrowly (atmosphere.js *transport* samples were covered) but `feedback_readme_honesty.md` says claims must be unambiguous without footnotes | Stopped at "every sample using atmosphere.js as transport" without rechecking whether the broader "every sample with a frontend" claim was true. The verification pass that surfaced the gap was triggered by the project maintainer's "Yes" to the proactive offer — the catch only worked because I asked the right question, not because I verified first | Closed by extending the Badge to accept arbitrary protocol names via `ConnectionTransportName = TransportType \| (string & {})` (widening commit, atmosphere.js types) and wiring each non-atmosphere sample to build its own `ConnectionStatusSnapshot`. **Gate**: vitest case `accepts non-atmosphere transport names via ConnectionTransportName` pins the type-level contract |

### Process win

Two of the drifts above (#20, #23) were caught because the verification
pass *preceded* the action that would have shipped the wrong claim:
- #20: the project maintainer's "double check" instruction triggered `git merge-base
  --is-ancestor`, which flipped the answer from "merged" to "6 commits
  unmerged" before the destructive `git push --delete` fired.
- #23: The proactive "Want me to add a Badge equivalent for the
  non-atmosphere.js samples?" question forced an audit that surfaced the
  three uncovered frontends. Without that question, the misleadingly-narrow
  claim "every sample with a frontend has it" would have shipped.

Both are evidence that the *order* — verify-before-claim, ask-before-act
— is the actual feedback-loop instrumentation. Memory files and discipline
rules are the substrate; the act of pausing for verification is the gate.

---

## 2026-05-11 — Cancel-race root cause (corrects entry #26)

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 28 | Entry #26 framed `AiStreamingSessionCancelInflightTest` slips as "VT scheduling jitter" and bumped the timeout from 2s to 10s as the gate. The pattern of "2.023s, 2.017s — classic jitter" was plausible. | The 10s bump did not hold. The very next JDK 21 CI run (`25706065128`) timed out at exactly 10.02s on the same assertion — not 30ms past, fully 8 seconds past. A deterministic 10s wait means the parked VT was never unblocked, which means `handle.cancel()` never reached the runtime. Reading the source revealed the race: `executeWithHandle` publishes `handlePublished` *inside* the runtime, and the AiStreamingSession then assigns `this.currentHandle = handle` on the next line. A `cancelInflight` call that races in between (test main thread woken by `handlePublished.get`) reads `currentHandle == null`, skips the cancel, and the VT parks on `whenDone().join()` forever. Not jitter — a real production race in the cancellation path | Entry #26 stopped at "VT scheduling jitter" without tracing the actual call graph. The fix should have been to instrument the cancel path (assertion on `cancelPending` flag, or interleaved logging) rather than bumping a wall-clock guard. The test's 10s wait was the symptom; the race was the disease | Latch added to `AiStreamingSession`: `volatile AtomicBoolean cancelPending` set first inside `cancelInflight()` and re-checked in `stream()` after `this.currentHandle = handle` is assigned. Closes the publish-before-assign window. **Gate**: new test `cancelInflight_winsRaceWhenFiredBetweenPublishAndCurrentHandleAssignment` uses a `RaceableRuntime` whose `executeWithHandle` parks on a release future after publishing — deterministically opens the race window and asserts the latch catches up. Local: 8/8 in 594ms |

### Process lesson

Entry #26's "jitter" framing was a *plausible-but-wrong* root cause. The
two slips (2.023s and 2.017s) clustered <30ms past the deadline, which
*looks* like Gaussian noise — but the underlying mechanism was the race
window, and the slip width was a function of how long the VT happened to
spend in the gap between `executeWithHandle` returning and `currentHandle`
being assigned. When the runner was more loaded the gap widened until the
test main thread reliably won. The lesson: a flake that consistently lands
within a narrow band past a deadline is usually a deterministic ordering
bug masquerading as jitter — re-read the call graph before bumping the
wall.

---

## 2026-05-12 — JS resilience roadmap item #1: offline queue end-to-end

Working through the gist plan (`atmosphere-js-resilience-completion-plan.md`).
Item #1 was "offline queue end-to-end" — primitive existed since April but
had no framework hooks and no sample consumers.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 29 | An earlier project memory referenced the JS resilience roadmap as "auth → offline queue → history sync → presence → optimistic updates" and an admin-side conversation summary described "auth completed" as if the rest were straightforward follow-ups | The OfflineQueue primitive shipped in April with full tests (149-line unit test + integration test) and transport-side `drainOfflineQueue` wired into `BaseTransport`, but **zero framework hooks** (`useOfflineQueue` did not exist for any framework), **zero sample consumers** (`git grep useOfflineQueue samples/` returned no source hits), and **zero admin console use**. Same shape as the post-mortem of entry #27 (primitive ✅, consumer ❌) | The "roadmap memory" pinned status at the *primitive* level — accurate when written but stale because finished-primitive ≠ finished-feature. Without a sweep across hooks + samples + admin console, a "shipped" claim on item #1 would have been a hallucination of the exact class `feedback_primitive_needs_consumer.md` warns about | Closed item #1 of the gist plan: added `useOfflineQueue` for React, Vue (`useOfflineQueue` composable), Svelte (`createOfflineQueueStore`), with re-export under `atmosphere.js/react-native`. Retrofitted `samples/spring-boot-chat` to enqueue offline-typed messages and render a "N queued" badge that drains visibly on reconnect. **Gates**: 9 vitest cases in `tests/unit/hooks/use-offline-queue.test.ts`, 5 Vue cases added to `tests/unit/hooks/vue.test.ts`, 3 Svelte cases added to `tests/unit/hooks/svelte.test.ts` (55 hook tests total green, 542/542 atmosphere.js suite green); new `modules/integration-tests/e2e/offline-queue-browser.spec.ts` Playwright spec drives the sample through `context.setOffline(true)` and asserts the badge cycles 0 → 2 → 0 on reconnect; gist `Status tracker` table updated with the audit result and DOD verification commands |

### Process win

The gist plan caught the slip-class *before* the work started: the audit
table forced enumerating "primitive ✅, hooks ❌, sample ❌, console ❌"
which immediately exposed what "next step" actually meant.

---

## 2026-05-12 — JS resilience roadmap item #2: history sync

The audit table in the gist plan had history sync flagged as "server
partial, client ❌, sample ❌". Walking the partial:

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 30 | A pre-existing reading of the codebase said "the server has history-replay" (referring to `Room.enableHistory(int)` + `RoomProtocolInterceptor.replayCachedMessages`) | The replay path is **unconditional** — every reconnect (every fresh resource uuid that joins) gets the full BroadcasterCache for that uuid replayed. There is no concept of `sinceId`, no server-assigned monotonic message id on the wire, and the client has no way to say "skip what I already have." Net effect: a client reconnecting mid-session sees every cached broadcast twice (once before disconnect, once on replay). The server "supports history" only in the sense that it has a buffer — the dedupe contract was missing entirely | "Server has history" was true for the buffer but misleading for the user-facing semantics. The fix is structural: add monotonic ids, a `sinceId` cursor on join, and a server-side filter that respects it. The hallucination class is "feature exists" vs. "feature does the thing users would expect" — same shape as the AgentRuntime SPI-vs-runtime drift documented in `feedback_primitive_needs_consumer.md` | Closed item #2 of the gist plan in a single feature branch: `RoomProtocolMessage.Join` gets an optional `Long sinceId` field; `RoomProtocolCodec.encodeMessage` gets a `long id` overload that emits an `id` JSON field; `DefaultRoom` gets `AtomicLong messageIdSeq` + `ConcurrentLinkedDeque<RoomHistoryEntry>` + `historySince(long)`; `RoomProtocolInterceptor.handleBroadcast` allocates ids and records history, `handleJoin` filters by `sinceId` when present (falling back to legacy `replayCachedMessages` otherwise — legacy clients keep working). Client side: `MessageHistorySync` primitive with `lastSeenId` + optional `storage` adapter for cross-reload persistence; framework hooks `useMessageHistory` (React, Vue composable, Svelte store, RN re-export); `samples/spring-boot-chat` retrofitted to observe `parsed.id` and re-send join with `sinceId` from `onReopen`. **Gates**: 4 new RoomTest cases pin the buffer semantics, 5 new RoomProtocolCodecTest cases pin the wire format (including backward-compat: legacy 3-arg `encodeMessage` must produce id-free output), 11 vitest cases on the primitive, 6 on the React hook, 3 each on Vue and Svelte. New `history-sync.spec.ts` Playwright E2E drives the round trip via `context.setOffline(true)` and asserts "msg-1 / msg-2 each appear exactly once after reconnect" |

### Process win

The gist plan's audit table again caught the slip *before* coding: writing
"server partial, client ❌" in the status row forced a precise read of what
"partial" meant. Without that pause, the work would have started from
"history replay just works on reconnect" and shipped the duplicate-on-
reconnect bug yet again.

---

## 2026-05-12 — JS resilience roadmap item #3: presence

The gist plan for #3 said "swap manual `'presence'` parsing in
spring-boot-chat for the `usePresence` hook." Reading the code first
flipped the move.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 31 | The gist plan DOD said "spring-boot-chat swap manual `'presence'` message parsing for `usePresence` hook" — implying a clean drop-in | `usePresence` is implemented as a thin wrapper over `useRoom`, which constructs an `AtmosphereRooms` and calls `atmosphere.subscribe(...)` to open **its own** transport connection. spring-boot-chat already uses `useAtmosphere` for chat; switching to `usePresence` would open a second WebSocket against `/atmosphere/chat`, doubling connections per page. The atmosphere.js singleton does not multiplex two subscribes over the same socket | The gist DOD assumed `usePresence` was a derived hook (consume an existing stream and project membership). It is actually a self-contained hook (own subscription). Without re-reading `AtmosphereRooms.connect` I would have shipped a sample that visibly opens two WebSockets to the same endpoint on first paint — exactly the "primitive exists but contract is wrong for this consumer" failure class | Skipped the `usePresence`-in-sample swap (would have been wrong); kept the same-connection manual parsing path but extended it to maintain a `Set<string>` of members derived from `join_ack` + `presence` events, and added a `data-testid="presence-count"` chip in the header. **Hook side**: confirmed `usePresence` reachable via `atmosphere.js/react-native` (re-export from `../react/usePresence` already in place since April); new vitest case in `tests/unit/react-native/hooks.test.ts` reads the RN entry file and asserts the re-export contract textually (importing it pulls in the optional `react-native` peerDep). New `presence-count.spec.ts` Playwright E2E drives two browser contexts (alice + bob) and asserts the badge cycles 1 → 2 → 1 as bob joins and disconnects. **Gist updated** to record the trade-off: `usePresence` ships and is reachable on every framework, but the sample sticks with derived presence; future "shared-subscription presence" hook (e.g. `useDerivedPresence(messageStream)`) is the right next-step primitive |

### Process win

The audit-first habit caught a "swap A for B" recommendation that would
have introduced a connection-doubling regression at the demo entrypoint.
The drift wasn't in code I wrote — it was in my own gist plan from earlier
today. Re-reading `AtmosphereRooms.connect` *before* drafting the diff
exposed the false equivalence between "hook exists" and "hook is the right
fit for this sample."

---

## 2026-05-12 — JS resilience roadmap item #4: optimistic updates

Closes the last item on the secret-gist plan. The pattern caught here
was hiding in plain sight in `OfflineQueue.acknowledge`.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 32 | First-pass `useOptimistic.commit` had `if (current.state !== 'confirmed') { mirror.set(...); reconcile(); }` — a defensive guard against double-reconcile | The guard was always true at the point it ran. `OfflineQueue.acknowledge` **mutates** the tracked record's state field in place (the queue's `pendingAcks` map holds the same reference as the caller's `track(data)` return value, and the React mirror's `recordsRef.current.get(id)` is yet another alias for the same object). So by the time `commit` looks up `current.state`, the queue's side-effect has already set it to `'confirmed'`. The `!== 'confirmed'` short-circuit then skipped `reconcile()`, leaving the React `messages` array pointing at the same identity it had before. The unit test caught it: `messages[0].state` read `'confirmed'` (correct, via the mutation) but `inFlightCount` stayed at the previous render's value because no new array was set in state | "Defensive guard" was actually a load-bearing bug — the same shared-mutable-reference pattern that hurts React state-equality elsewhere in the ecosystem. The drift class is "primitive looks pure but mutates" — `OfflineQueue.acknowledge`'s body says `msg.state = 'confirmed'; this.pendingAcks.delete(messageId)`, and the docstring "Acknowledge a message by ID" reads as a transactional update, not an in-place mutation | Fixed in `useOptimistic` (React, Vue, Svelte) by removing the equality guard and always materializing a fresh `{ ...current, state: 'confirmed' }` record into the mirror, so the framework's state-equality check fires. The vitest case `commit flips a message to confirmed and lowers inFlightCount` is the regression test — would have failed silently before the fix. **Cross-cutting**: also noted that `OfflineQueue.fail` does the same in-place mutation; `rollback` already creates a fresh object (`{...current, state: 'failed', error}`), so the same fix was not needed there |

### Process win

The test-first approach surfaced this: writing the test ("commit flips to
confirmed AND inFlightCount drops to 0") immediately caught the disagreement
between the mutation-via-alias view and the React-state-equality view.
Without the assertion on `inFlightCount`, the bug would have shipped — the
visible "state === 'confirmed'" check passes, but the derived count stays
stale, and consumers depending on `inFlightCount` for "still sending..."
banners would have seen them stuck on forever.

---

## 2026-05-12 — Security-cleanup session: native-image regression from netty 4.1.133 in Quarkus samples

Closing a 717 → 12 Dependabot-alert sweep. Bumped Netty across every
Quarkus consumer (production module + sample) to 4.1.133.Final. Locally
ran `./mvnw test` across 30+ modules — all green. Reported the bump as
"runtime-validated, BUILD SUCCESS, 0 failures." Pushed. Native Image
broke on the next CI run.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 33 | "All netty bumps validated runtime-clean — full reactor (30 modules) BUILD SUCCESS, zero failures. Lettuce, Azure SDK, Koog, Docker-Java, Quarkus, Spring Boot — none of them rely on internal Netty classes that broke between the old and patched versions." | The `./mvnw test` sweep I ran covers JVM-mode test execution. It does **not** exercise Quarkus native-image build, which has a fundamentally different reachability analysis: every transitively-reachable class is parsed at build time, including ones never touched by the unit tests. Netty 4.1.133's `BrotliDecoder.decompress` was recompiled against a newer brotli4j ABI — the bytecode references `DecoderJNI$Wrapper.pull(int)` (a `ByteBuffer pull(int)` overload) where 4.1.132 referenced no-arg `pull()`. The brotli4j jar on `samples/quarkus-chat`'s classpath only has `pull()`, so `native-image --link-at-build-time` aborts with "Discovered unresolved method during parsing: DecoderJNI$Wrapper.pull(int)". Quarkus samples broke; production code paths (the `modules/quarkus-extension*` overrides) don't trigger native-image and stayed green | The "runtime-validated" claim conflated **JVM test-suite pass** with **runtime safety**. Native-image is a third execution mode (alongside JVM + tests) and its reachability set is a superset of what tests touch. I never ran `mvn package -Pnative` on the affected samples before claiming "runtime-clean." This is anti-pattern #6 from CLAUDE.md (Honesty section): "Declaring victory mid-task" — the milestone "tests pass" was reported as the broader "runtime-validated," and the third mode (native) silently regressed. The drift class is **"test-pass does not imply native-image-pass when bytecode references new transitive ABIs"** | Two fixes: (a) **revert** netty-bom 4.1.133 in `samples/quarkus-chat/pom.xml` + `samples/quarkus-ai-chat/pom.xml` — they now ride Quarkus 3.35.2's pinned 4.1.132, accepting sample-scope CVE risk in exchange for native-image build correctness. Production modules (`modules/quarkus-extension*`) keep the 4.1.133 pin because they're consumed by users' own native-image builds, where users control the brotli4j version. (b) **process gate** for future dep bumps in Quarkus modules/samples: must include `mvn -pl samples/quarkus-chat package -Pnative -Dquarkus.native.container-build=true` (or equivalent CI parity) before claiming a Netty/Quarkus-touching bump is "runtime-clean." Not yet automated as a script; logged here as the convention. **Sample of the actual native error**, for future grep: `UnsupportedFeatureException: Discovered unresolved method during parsing: com.aayushatharva.brotli4j.decoder.DecoderJNI$Wrapper.pull(int) ... class io.netty.handler.codec.compression.BrotliDecoder is registered for linking at image build time` |

### Process lesson

The session's broader Netty/Spring/AHC sweep was correct and validated — 5
commits closed ~700 alert paths, with mvn tests green across 30 modules.
But "tests pass" became "validated runtime-clean" in the user-facing
summary, and that overreach is what set up the bad expectation. The fix
is small (revert 2 sample BOM imports) and additive — the production-side
CVE fix stays — but the cost was a red main pipeline that the project maintainer
caught via `CI status` query, not a self-detection. Native-image deserves
a checkbox in the "validated" claim, not an assumption.

---

## 2026-05-12 — Classroom resilience retrofit (samples coverage expansion)

After landing the 4-piece resilience suite in `spring-boot-chat`, the
honest answer to "what other sample exercises this?" was "only one."
Retrofitting `spring-boot-ai-classroom` surfaced a real SDK gap.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 34 | The original plan for the classroom retrofit assumed `useStreaming` would expose enough of the underlying subscription that presence frames broadcast by the server's `@Ready` / `@Disconnect` hooks would naturally reach the client | `useStreaming` (correctly) ignores any message that fails `parseStreamingMessage` — non-streaming frames (no `sessionId`, no whitelisted `type`) get silently dropped in `streaming/index.ts:88`. Presence broadcasts had no path to the React layer. Two options: (a) open a parallel `useAtmosphere` subscription on the same URL (the double-WebSocket footgun from drift-log entry #31), or (b) extend `useStreaming` to passthrough non-streaming frames | "Hook surface is enough" was a guess, not a verified fact. Reading `subscribeStreaming.message` showed the gap inside 30 seconds — should have been step one of the retrofit plan, not surfaced as a "discovered while wiring" | Added `onRawMessage?: (raw: string) => void` to both `StreamingHandlers` (`atmosphere.js/src/streaming/types.ts`) and `UseStreamingOptions` (`atmosphere.js/src/hooks/react/useStreaming.ts`); wired through `subscribeStreaming` *before* the early return in `streaming/index.ts`. The classroom hooks it to extract `{type:"presence","count":N}` frames; streaming chunks continue to flow through the existing typed callbacks. **Gate**: existing 579/579 vitest suite still passes (additive change); new E2E `classroom-resilience.spec.ts` asserts the presence-count chip lights up to "1 online" after a single browser joins, validating the round-trip end-to-end |

### Process note

This entry is the same shape as drift-log #31 (`usePresence` would
double-subscribe in spring-boot-chat). Pattern: "hook A looks like it
covers use-case B, but you need to read hook A's message dispatch path
to verify before claiming it does." The atmosphere.js hook surface has
several similar passthroughs now — when wiring future samples, default
to grep'ing for the consumer-callback name first.

---

## 2026-05-15 — Doc alignment review (`feat/ai-gap-fixes` + `atmosphere.github.io@e26cf76`)

Deep review requested for the doc updates pushed against
`feat/ai-gap-fixes` (Atmosphere `f8cee932f9`, "CI Samples green") and
`atmosphere.github.io main` (`e26cf76`, "Deploy website succeeded"). The
prose was internally consistent and CI green on both sides, but a
release-ordering check against npm + a re-read of each runtime's
`capabilities()` surfaced three claims that diverge from ground truth.
All three are "advertise ahead of release" failures — Correctness
Invariant #5 (Runtime Truth) and the No-Hallucinations §3a rule that
"shipped" requires a published consumer surface.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 35 | `atmosphere.github.io/docs/src/content/docs/whats-new.md:16,407`, `clients/javascript.md:8,59,712`, `clients/react-native.md:8`, `tutorial/04-transports.md:207,223` all advertise **atmosphere.js 5.0.24** as the currently-available client (`atm.version; // '5.0.24'`, "TypeScript client (currently `5.0.24`)", "atmosphere.js `5.0.24` supports React Native") | `npm view atmosphere.js versions` ends at **5.0.23**. Commit `573a33fd41` is explicitly labeled `chore(js): prepare next development version 5.0.24` — the SNAPSHOT pointer set after the 5.0.23 release tag, never published. `npm install atmosphere.js@5.0.24` returns "no matching version". `useChat` / `createChatStore` / `useChatRN` only exist in 5.0.24-dev, so following the docs gets the user `5.0.23` (no chat hooks) when they don't pin, or a 404 when they do | Doc author treated the local `package.json` "version" field as the latest *available* version. Did not cross-check the npm registry. Same class as drift #12 (pom-version-bump prose lag) but on the publish side rather than the build side — version strings in source ≠ version strings on the registry | Added a release-order rule to memory ([reference_npm_release_gate.md](reference_npm_release_gate.md)): before merging doc changes that pin a specific `5.x.y`, verify `npm view atmosphere.js versions` lists that version. No automated gate yet — candidates: a `scripts/check-doc-versions.sh` that greps `5\.0\.[0-9]+` in `atmosphere.github.io/docs/**/*.md` and asserts each version is present in `npm view`, wired into the docs repo's CI. Logged as a follow-up |
| 36 | `atmosphere.github.io/docs/src/content/docs/reference/ai.md:621-660` says "The table below mirrors the nine-runtime snapshot pinned by each runtime's `expectedCapabilities()` contract test"; the table shows AgentScope's `TC` and `TA` columns blank and the prose below says "AgentScope and Spring AI Alibaba do not declare TOOL_CALLING or TOOL_APPROVAL because their current SDK surfaces do not provide a native tool-dispatch loop" | On `feat/ai-gap-fixes` (the branch the doc claims to align to): `AgentScopeAgentRuntime.capabilities()` **unconditionally** declares `TOOL_CALLING` + `TOOL_APPROVAL` (via `AgentScopeToolBridge` added in `62a9b7e6af`); `AgentScopeRuntimeContractTest.expectedCapabilities()` pins both; `.harness/capabilities.snapshot.json` lists both. For Spring AI Alibaba: `capabilities()` declares `TOOL_CALLING` + `TOOL_APPROVAL` **conditionally** when `staticChatModel != null` (lines 285-288), but the contract-test default-constructed instance has `staticChatModel == null`, so the snapshot pins without them. The doc table happens to match the snapshot for Alibaba, but the prose is wrong (presents the gap as unconditional) and the AgentScope row is wrong outright | Doc author appears to have copied a pre-parity capability matrix and not regenerated against `.harness/capabilities.snapshot.json` on the same branch. The "mirrors the snapshot" prose was added without re-running the mirror. Drift #6 (TOOL_APPROVAL runtime count) has the same shape and the same root cause — narrative prose lagging the snapshot file | Two complementary gates: (a) **doc validator extension** — `scripts/validate-capability-claims.sh` already catches `\bAll \d+ runtimes\b` patterns. Extend to also assert that any runtime row in `reference/ai.md` matches the `.harness/capabilities.snapshot.json` entry. (b) **prose grep** — when capability work merges, also `git grep -i "do not declare TOOL_CALLING\|lack tool calling\|tool-call dispatch loop"` and reconcile each hit against the updated snapshot. Not yet automated; logged as the gate |
| 37 | The website doc commit `e26cf76` ("docs(ai): align rag and client docs") documents `ContextProvider.filter()`, `ContextProvider.postProcess()`, `ContextProvider.formatCitation()`, `useChat` / `createChatStore` / `useChatRN`, the 9-runtime capability matrix with tool-bridge closure, and atmosphere.js 5.0.24 — all of which live **only on `feat/ai-gap-fixes`**, an unmerged 9-commit-ahead branch (Atmosphere main HEAD is `c909d3f969`, last release is `4.0.45`). The website is deployed from `atmosphere.github.io main` so the claims are public *now* | `git show main:modules/ai/src/main/java/org/atmosphere/ai/ContextProvider.java` shows only `retrieve`, `transformQuery`, `rerank`, `ingest`, `isAvailable` — `filter`/`postProcess`/`formatCitation` are absent. `git show main:atmosphere.js/src/hooks/react/useChat.ts` returns "does not exist in 'main'". So users hitting the live website right now and copy-pasting examples against 4.0.45 + atmosphere.js 5.0.23 get `cannot find symbol: method filter(...)` and `Module 'atmosphere.js/react' has no exported member 'useChat'`. Branch-CI-green is necessary but not sufficient — public docs need release-state truth, not branch-state truth | Doc PR was authored, validated, and merged against the *branch-tip* state, not the *released* state. The "Deploy website succeeded" signal only proves the static site built; it does not prove the documented APIs exist in any published artifact. Same class as #35 but covers the broader API surface beyond version numbers | Process gate: **release-ordering memory** ([reference_doc_release_ordering.md](reference_doc_release_ordering.md)) — doc PRs in `atmosphere.github.io` that introduce new symbol references (new methods, hooks, classes) must list (a) the Atmosphere release version that ships those symbols and (b) the npm publish that ships any new TS exports. If either is unreleased, the doc PR is held until the release. No automated gate yet — candidate: a docs-repo CI step that compiles each Markdown code block's TypeScript snippets against the latest *published* `atmosphere.js` from npm rather than `file:../atmosphere.js`, which would catch the `useChat` import failure mechanically |

### Process note

All three drifts share the same upstream cause: **"branch-state CI green"
was conflated with "user-facing claim is safe."** The branch passed every
gate on the Atmosphere side and the website built cleanly on the docs
side — but the missing gate is the *registry* (npm versions) and the
*release tag* (latest published Atmosphere jar). The harness already
treats "test pass ≠ native-image pass" (entry #33) as a known gap; this
session adds "branch tip ≠ public artifact" as the same shape on the
publish dimension.

A small but real category-error: the review prompt cited "CI Samples
succeeded" and "Deploy website succeeded" — both true and both load-
bearing for the *change*. Neither says anything about whether the symbols
those docs reference are reachable from a user's `npm install` + `mvn
dependency:get`. Future review prompts that want a full-stack honesty
check should explicitly include "and the user can install / pin / import
every symbol the doc names" — or the reviewer should add that
implicit step every time without being asked.

---

## 2026-05-15 — Gist 10/10 closure pass (`feat/ai-gap-fixes` rev 2)

Follow-up to the doc-alignment review above. The original review surfaced
that the gist's six product gaps + six roadmap items were only
~4-of-7 closed; the rest of the day was spent stacking commits on
`feat/ai-gap-fixes` until each gist item had a backing commit hash, per
the user instruction to "keep stacking until 10/10."

### Closure of drifts #35–#37 above

| # | Original drift | Closure | Closing commit |
|---|---|---|---|
| 35 | Website pinned atmosphere.js 5.0.24 but npm only published 5.0.23 | Publish still deferred (Atmosphere main has not received the branch merge yet) — but the gap is now load-bearing on a *single* release event: when `feat/ai-gap-fixes` merges to main, the same merge cuts the 5.0.24 release. Once `npm view atmosphere.js@5.0.24` returns a manifest, the doc claim becomes true. Process gate is the release-ordering memory installed during the original drift entry; nothing else added | Deferred — gated on Atmosphere main merge + `release-4x.yml --js_only=true` |
| 36 | Website matrix said AgentScope/Alibaba lack `TOOL_CALLING`/`TOOL_APPROVAL` but the snapshot lists both; Alibaba's `TOKEN_USAGE` row was blank even though the runtime parity push had landed | Capability matrix + prose updated in `atmosphere.github.io main` (`b1fd0be`) and the equivalent rows in `modules/ai/README.md` (`554cd20cb5`) and `modules/agentscope/src/test/.../AgentScopeRuntimeContractTest.java` comment. Alibaba's `TOKEN_USAGE` is now declared *and* honored: `UsageCapturingChatModel` wraps the Spring AI `ChatModel` bean in auto-config so every step of the ReAct graph accumulates `ChatResponseMetadata.getUsage()` into a per-thread collector; the runtime emits one `session.usage(TokenUsage)` after each dispatch | `534317f03d` (runtime + wrapper + tests), `554cd20cb5` (Atmosphere prose), `b1fd0be` on `atmosphere.github.io` (website matrix) |
| 37 | Website docs described `ContextProvider.filter`/`postProcess`/`formatCitation`, `useChat`/`createChatStore`/`useChatRN`, and 9-runtime tool-bridge closure — all of which only lived on the unmerged `feat/ai-gap-fixes` branch | All of those surfaces are now actually present on the branch and verified by tests; the website docs that describe them are aligned with the snapshot the branch will publish on merge. Same release-ordering gate as #35 — the docs become true when the branch lands on main; no separate fix needed once the merge happens | Closure-by-merge; tracked under `release-doc-ordering` discipline |

### Gist item-by-item scorecard (was 4-of-7, now 7-of-7)

The original review ([conversation transcript above](#)) named the gist items
and what was missing. Each row below is the closing commit (or commits) on
`feat/ai-gap-fixes`.

| Gist item | Status | Closing work | Commit(s) |
|-----------|--------|--------------|-----------|
| 1. RAG/data layer depth | Closed | `RagChunker`, `ContextProvider.filter`/`postProcess`/`formatCitation`, plus three direct connectors (pgvector, Qdrant, Pinecone) and a reachability matrix in `modules/rag/README.md` showing 6 direct providers + bridges covering 11+ stores | `1405b331a7`, `a0084792cb`, **`31d6455a75`** |
| 2. Runtime capability parity | Closed | AgentScope + Alibaba tool bridges, Koog + Alibaba embedding runtimes, then Alibaba's TC/TA/TU made unconditional via `UsageCapturingChatModel` | `62a9b7e6af`, **`534317f03d`** |
| 3. DX templates + 10-min flow + runtime selection | Closed | 10-min path in `cli/README` + `samples/README`; five flagship templates promoted (`rag`, `ai-tools`, `guarded-agent`, `coding-agent`, `ms-governance`); decision-tree doc at `docs/runtime-selection.md` | `a0084792cb`, **`97130eeeeb`** |
| 4. Enterprise console story | Closed | Workflow authoring lands inside the admin control plane (not as a separate DSL — architectural call by the project maintainer mid-session) + eval dashboard + `atmosphere-admin-bundle` single-dep aggregator | **`81ff454177`**, **`38e2a45920`**, **`eaad0df089`** |
| 5. Evaluation & regression | Closed | `GoldenEvalBaseline` + `LlmJudge` enhancements already on branch, plus a new dashboard surface that aggregates pass-rate per baseline and surfaces recent runs with auto-refresh | `1405b331a7`, `a0084792cb`, **`38e2a45920`** |
| 6. Frontend AI UX | Closed | `useChat` / `createChatStore` / `useChatRN` shipped across React / Vue / Svelte / RN entry points (publish gated on Phase 1b — same release-ordering gate as #35) | `a0084792cb`, `90531ab24f` |
| 7. Workflow authoring DX | Closed | `WorkflowManifest` + `WorkflowStore` SPI + `WorkflowController` with `ControlAuthorizer` + audit-log integration; visual JSON-editor UI at `/atmosphere/admin/workflow.html`; Spring Boot REST endpoints with optimistic concurrency | **`81ff454177`** |

### Process note

The follow-up worked because the original review (drifts #35–37) named
the gap precisely *and* the user pushed back on "doc alignment is just
one slice." The honest scorecard ("~4-of-7 closed, the headline
overshoots") was the load-bearing piece — without it the session would
have stopped at five doc fixes. The pattern is the inverse of
anti-pattern #6 from CLAUDE.md ("Declaring victory mid-task"): score
truthfully even when it makes the response longer.

Gate for next time: when a "doc updates green" report lands, by default
ask "and is the gist / roadmap / spec these docs reference *closed* or
just *described*?" before agreeing the change is shippable. The "did the
docs ship green" signal answers a strictly narrower question than the
user usually means.

---

## 2026-05-15 — mirroir-run pilot SAMPLE.md authoring (`feat/mirroir-pilot-ai-chat`)

Phase A of the §15 expansion (drive all 25 Atmosphere samples through
`mirroir-run`). Pilot is `samples/spring-boot-ai-chat`. The work itself
is a SAMPLE.md + two scenario YAMLs + a CI lane — but in the *first
draft* I propagated a sample-README claim into the SAMPLE.md `env:` block
without grepping the runtime that interprets it. Self-caught against the
actual `AiConfig.configure` modes table before the gist or the file
shipped to Atmosphere `main`, but after the gist was already published —
so the false claim escaped the local check.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 38 | First draft of `samples/spring-boot-ai-chat/SAMPLE.md` + the published secret gist [d1eddc8…](https://gist.github.com/jfarcand/d1eddc8305951c607ba3e845024ee3e6) declared `LLM_MODE: "demo"` as the no-API-key boot env and asserted "Demo mode lets CI run on a fresh runner with zero secrets" — citing the sample README's prose "works out-of-the-box without an API key (simulated streaming)" | `AiConfig.configure(String mode, …)` (line 150 of `modules/ai/src/main/java/org/atmosphere/ai/AiConfig.java`) only branches on three values: `remote` (default, real provider), `local` (Ollama), and `fake` (line 153: `if ("fake".equalsIgnoreCase(mode)) { instance = new LlmSettings(new FakeLlmClient(model), …); }`). There is no `demo` mode — passing `LLM_MODE=demo` would fall through to the `remote` branch with no API key, log "No API key configured for remote mode" and fail at request time. The README's "demo mode" phrasing is colloquial; the actual mode token is `fake` | Trusted the sample README's natural-language framing ("demo mode") as the literal env-var value instead of grepping the runtime's switch. Same class as drift #36 (prose-vs-snapshot lag) — narrative ahead of code. Compounded by writing the gist *before* the local validate step, so the false token propagated to a published artifact before any executable check could catch it | Memory rule installed ([feedback_grep_env_tokens.md](feedback_grep_env_tokens.md)): when a new SAMPLE.md or scenario env var names a *mode/strategy* value, grep the consuming code (`grep -n "equalsIgnoreCase\|equals\|=" <ConfigClass>.java`) for the literal accepted set before writing the YAML. README prose is documentation, not contract. Mechanical gate candidate: `scripts/validate-sample-manifest.sh` — parse `samples/*/SAMPLE.md` YAML, extract `env:` keys ending in `_MODE`, grep the runtime modules for the literal value as a string constant. Logged as a follow-up; not yet implemented |

### Process note

The catch was self-driven by a single `grep -n LLM_MODE LlmConfig.java`
that I should have run *before* writing the SAMPLE.md, not after. The
catch happened ~3 minutes after the gist was published — the gist has
been updated in place. No public-doc rollout occurred. Net cost: one
extra `gh gist edit` and this drift entry. Net win: the gate is now
named, so the next sample's env block gets the grep first.

The "trust README prose" reflex is the same shape as drifts #5 (CHANGELOG
prose vs code) and #36 (capability matrix prose vs snapshot). The
recurring fix is the same: when prose names a *literal value the runtime
will compare with `equalsIgnoreCase`*, that value must come from the
runtime's source, not from any human-readable description of it.

---

## 2026-05-18 — Agent-runtime JFR/permissions/memory branch e2e validation

Closing the e2e matrix on `feat/agent-runtime-jfr-permissions-memory`. Three of the four spec suites passed first try; the EpisodicMemoryStore spec caught a JFR consumer-side assumption.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 51 | The unit tests for JFR observability use `event.getValue("operation")` and call `String.valueOf(...)` on the result to switch on the enum-style outcome (`SUCCESS` / `FAILURE` / `DENIED` / `STORE` / `RECALL` / `FORGET`). I generalized this pattern across all four e2e handlers expecting it to work the same way at the live RecordingFile boundary. | `RecordedEvent.getValue(String)` on a JFR `String` field returns the **internal char-array view** (`char[]`), not a `java.lang.String`. `String.valueOf(char[])` returns the array contents as a String *by accident* in some cases but throws `ClassCastException: class java.lang.String cannot be cast to class [C` for others mid-iteration when the JFR parser tries to coerce. The unit tests didn't catch this because they used `event.getValue(...).equals(CONSTANT)` (a single `Object.equals(...)` call which works on either representation). The e2e Episodic spec failed only when iterating with a switch that demanded the String coercion. The correct accessor is `event.getString("operation")` — JFR returns a real `java.lang.String` and the cast is internal. | Unit-test pattern (single `.equals` comparison) accidentally hid the issue. The e2e handler was the first consumer to materialize the value as a String and switch on it; that exposed the storage-vs-value distinction. The unit tests were honest about what they asserted — they didn't lie — but they didn't exercise the consumer surface that real code (logging, JFC parsers, JMC users) hits. | (1) `EpisodicMemoryTestHandler.emitJfrBreakdown` now uses `event.getString("operation")` and a diagnostic metadata frame surfaces any future parser exception out to the spec; the spec asserts `diagnostic === 'ok'`. (2) Renamed the event field `String store` → `String storeClass` in `EpisodicMemoryAccessEvent` after a parallel hypothesis (turned out unrelated to the cast, but `storeClass` is the clearer name). (3) No standalone validator — the e2e spec itself is the gate: any future JFR event that exposes a String field through `getValue` will fail the diagnostic assertion. |

### Process win

The unit tests went green first, the e2e spec failed second — exactly the
order the testing pyramid promises. The cost of running all four e2e specs
through real Jetty (~27 seconds) bought a real-consumer assertion the
RecordingFile-based unit tests could not.

---

## 2026-05-19 — AnthropicAgentRuntime introduction (`feat/anthropic-runtime`)

Adding the native Anthropic Messages API runtime as the 10th `AgentRuntime`.
Most of the work was straightforward; one under-claim slipped past the
initial capability set and only surfaced when the pre-push capability-claims
gate forced the runtime count from 9 to 10 to be reconciled with prose.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 52 | First-draft `AnthropicAgentRuntime.capabilities()` omitted `PER_REQUEST_RETRY` with a comment reading "only Built-in routes retry per-request". Mirrored in `AnthropicRuntimeContractTest.expectedCapabilities()`. | `AnthropicAgentRuntime extends AbstractAgentRuntime<AnthropicMessagesClient>` and does not override `ownsPerRequestRetry()`, so the default `false` is in force. That puts the runtime on the `executeWithOuterRetry` path verbatim — same posture as `AgentScopeAgentRuntime` and `SpringAiAlibabaAgentRuntime`, which both *do* claim `PER_REQUEST_RETRY` honestly. The original comment misread `modules/ai/README.md` line 334 ("All 9 runtimes claim `PER_REQUEST_RETRY` honestly") as a statement about the OpenAI-compatible client's `sendWithRetry` only, when the prose explicitly covers the inherited outer wrapper for every `AbstractAgentRuntime` subclass. The bug was the same shape as the original `AgentScope` / `Spring AI Alibaba` under-claim the same README paragraph documents — repeating a fixed mistake. | The capability-claims validator (`scripts/validate-capability-claims.sh`) caught the symptom indirectly: regenerating `.harness/capabilities.snapshot.json` for the new runtime bumped the count from 9 to 10, which broke the prose claim "All 9 runtimes claim `PER_REQUEST_RETRY`" — and forcing that prose to be honest required asking "is Anthropic actually one of the 10, or is it the exception that keeps the count at 9?". Without that gate, the runtime would have shipped under-claiming PER_REQUEST_RETRY while quietly honouring the policy through the inherited outer wrapper — `runtime.capabilities()` lying about behavior the bytecode already implements (Correctness Invariant #5 violation). | (1) Added `PER_REQUEST_RETRY` to both `AnthropicAgentRuntime.capabilities()` and `AnthropicRuntimeContractTest.expectedCapabilities()`; in-line comment now points at `AbstractAgentRuntime.executeWithOuterRetry` explicitly. (2) Regenerated `.harness/capabilities.snapshot.json`. (3) Updated `modules/ai/README.md` § Adapter Runtimes table to add the Anthropic row and the prose at lines 49 and 334 to say "10 runtimes" — `validate-capability-claims.sh` now passes against `10 runtimes, 20 capabilities`. The pre-existing snapshot gate + claims validator were sufficient — no new gate added, but this entry pins the lesson for the next runtime: when extending `AbstractAgentRuntime` without overriding `ownsPerRequestRetry()`, `PER_REQUEST_RETRY` belongs in the capability set. |

### Process win

The capability-claims validator earned its keep. Without the prose-grep
gate, the new runtime would have shipped with an honest-looking but
under-claimed capability set — exactly the documented "Adapter under-claims
a capability the inherited bytecode already supports" pattern. The gate
caught the count mismatch first; chasing that mismatch surfaced the real
PER_REQUEST_RETRY oversight before the commit landed on main. This is the
shape the harness is supposed to enable: drift surfaces as a count
mismatch, the count mismatch forces re-reading the inherited behavior,
the re-read corrects the capability set, the snapshot regenerates, the
gate goes green.

---

## 2026-05-22 — LongTermMemory backend overclaim (`fix/long-term-memory-audit`)

Triggered by the project maintainer forwarding the InfoQ Cloudflare agent-platform
piece (https://www.infoq.com/news/2026/05/cloudflare-agent-platform-stack/)
and asking what we could learn. The honest comparison required first
asking what *we* actually ship — which surfaced a backend-list overclaim
in the `LongTermMemory` Javadoc and on the public website tutorial.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 53 | `modules/ai/src/main/java/org/atmosphere/ai/memory/LongTermMemory.java:25` Javadoc read "Backed by a `FactStore` implementation (in-memory, Redis, SQLite)." `InMemoryLongTermMemory.java:26–27` said "For production, use a `SessionStore`-backed implementation." `atmosphere.github.io/docs/src/content/docs/agents/coordinator.md:457` repeated the same shape verbatim: "Backed by `InMemoryLongTermMemory` (dev) or any `SessionStore` implementation (Redis, SQLite)." | `git grep 'implements LongTermMemory' -- ':!**/test/**'` returns exactly **one** hit: `InMemoryLongTermMemory.java`. There is no `FactStore` interface, no `SessionStore`-backed `LongTermMemory`, and no Redis or SQLite `LongTermMemory` implementation anywhere in the reactor. `SessionStore` itself does ship with Redis + SQLite backends (`modules/durable-sessions-redis`, `modules/durable-sessions-sqlite`), but no class bridges it to the `LongTermMemory` SPI — and the bridge would be non-trivial since `SessionStore` stores session blobs, not per-user fact lists. Same drift class as #2 in this log ("enumerating SQLite, Redis, Postgres when only SQLite ships is a classic") — the Javadoc named two backends that do not exist, and the website tutorial repeated it word-for-word. | I wrote the original `LongTermMemory` Javadoc and the coordinator tutorial paragraph in the same drafting session as the SPI implementation, sketching the "obvious" future backends as if they were already there. The `SemanticRecallInterceptor` story (single-channel BYO-vector RAG) is real and shipped; the future-backend list got tacked on by intuition rather than by `ls modules/`. The drift sat for ~2 weeks until the Cloudflare comparison forced a side-by-side read of our memory primitive against theirs ("five-channel parallel search with Reciprocal Rank Fusion"). Reading the actual SPI to write the honest comparison surfaced the lie. | (1) Prose fix in this commit — Javadoc on `LongTermMemory.java` rewritten to describe what the SPI actually does (recency-ordered fact retrieval; one in-tree implementation; users bring their own persistent backend); Javadoc on `InMemoryLongTermMemory.java` rewritten to drop the "SessionStore-backed" hint; tutorial paragraph in `coordinator.md` rewritten to describe the actual scope of the primitive and how to compose it with `SemanticRecallInterceptor` for relevance-ranked recall. (2) **Gate: none** — same precedent as #12 and #18 (narrative-prose backend lists have no clean structural check). A future structural gate could grep `git grep 'implements LongTermMemory' -- ':!**/test/**'` against any prose that enumerates Long-Term Memory backends; not added in this commit to keep the bundle scoped. Discipline going forward: any "Backed by X, Y, Z" sentence about an Atmosphere SPI must come from `git grep 'implements <SPI>' -- ':!**/test/**'`, not from intuition about what the SPI *could* support. |

### Process win

The Cloudflare InfoQ piece was the trigger but the value was the side-by-side
read. When a user-facing question forces you to compare your primitive
against a named competitor, you read the code with fresh eyes — and prose
that has been hiding for two weeks gets re-checked against `git grep`. The
honest answer to "what can we learn" was not in the article; it was in our
own javadoc that we had not re-read since shipping.

---

## 2026-05-22 — Close the LongTermMemory backend gap (`fix/long-term-memory-sqlite-redis-bridges`)

Continuation of the same day's drift #53. the project maintainer pushed back: shrinking
the Javadoc claim from "Backed by in-memory, Redis, SQLite" down to "only
InMemory ships" was the easy way out — the right answer was to *ship* the
backends so the original claim becomes accurate. Two production backends
plus a structural gate to close the entire drift class were the asks.

### Gap closure (not a drift entry — a fix for drift #53)

| Component | Where | Notes |
|---|---|---|
| `SqliteLongTermMemory` | `modules/durable-sessions-sqlite/src/main/java/org/atmosphere/session/sqlite/SqliteLongTermMemory.java` | Mirrors the `SqliteConversationPersistence` constructor surface (default file, custom path, shared `Connection`, `inMemory()` factory). Schema: `ai_user_facts(id PK, user_id, fact_text, created_at)` with an index on `(user_id, id DESC)`. Per-user cap enforced via `DELETE ... WHERE id NOT IN (SELECT id ORDER BY id DESC LIMIT n)` on every save. Tests pin contract parity with `InMemoryLongTermMemoryTest` (same seven behaviors) plus a file-backed persistence-across-close case. |
| `RedisLongTermMemory` | `modules/durable-sessions-redis/src/main/java/org/atmosphere/session/redis/RedisLongTermMemory.java` | Lettuce LIST per user under key `atmosphere:facts:<userId>`. `RPUSH` for insertion-order append, `LTRIM -maxFacts -1` for cap, `LRANGE -max -1` for retrieval (matches `InMemoryLongTermMemory` "oldest-of-recent-N first" order). Tests use Testcontainers with `redis:7-alpine`, gated by Docker availability (same skip pattern as `RedisSessionStoreTest`). |
| Gate | `scripts/validate-backend-class-refs.sh` + `.harness/external-class-allowlist.txt` | Greps `*.java` and `*.md` for tokens matching `(Sqlite|SQLite|Redis|Postgres|PostgreSQL|Mongo|MongoDB|Cassandra|Hazelcast|JGroups|Kafka|Nats|NATS)[A-Z][A-Za-z0-9_]+`. Each unique token must either declare a class under `modules/**/src/{main,test}/java/` or `samples/**/src/{main,test}/java/`, or appear in `.harness/external-class-allowlist.txt` (seeded with Lettuce, Kafka, Testcontainers, MongoDB/PostgreSQL brand names, and the three external Atmosphere plugin repos). Markdown fenced code blocks are stripped before scanning so example-class names in tutorials don't trigger the gate. Wired into `scripts/pre-push-validate.sh` Tier-1: runs whenever `*.java`, `*.md`, the script itself, or the allowlist changes. |
| Docs restored honestly | `modules/ai/src/main/java/org/atmosphere/ai/memory/LongTermMemory.java`, `modules/ai/src/main/java/org/atmosphere/ai/memory/InMemoryLongTermMemory.java`, `atmosphere.github.io/docs/src/content/docs/agents/coordinator.md` | Javadoc now lists the three real implementations with their containing artifact and what they share connections with. Website tutorial now names the same three with the same artifact / connection-sharing notes. |

### Process win

the project maintainer's "this is embarrassing and really damaging" framing was the
forcing function. Drift #53 caught the lie; this fix made the original
prose *true* by shipping the missing artifacts, and the gate ensures the
class of bug — a class name in Javadoc or docs that doesn't resolve to a
real declaration — fails the build going forward, not just for
`LongTermMemory` but for any future SPI with backend-prefixed
implementations.

The lesson for `feedback_*` discipline: **when caught hallucinating a
shipped feature, the bias should be toward shipping the feature, not
shrinking the claim.** Shrinking is honest but it leaves the original
user expectation unmet; shipping closes the gap on both axes. Only fall
back to shrinking when the missing feature is genuinely out of scope.

---

## 2026-05-22 — Hallucinated head SHA in `gh run cancel` filter (`docs/drift-log-sha-hallucination`)

Closing-out drift for the same session. After pushing the README scope
section (commit `28c1ce6dfd`), I needed to cancel superseded CI runs on
prior SHAs to free runners. The cancel command exclusion filter used a
40-character SHA I had not queried — I wrote down `28c1ce6dfd06bb...` as
the "full" SHA from intuition, since I had only seen the 8-character
short form (`28c1ce6dfd`) in the push output.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 54 | The cancel filter `select(... and .headSha != "28c1ce6dfd06bb01f59c6efbf6dad6f3b1eccfd4")` was meant to spare the runs on the new push from being cancelled. | The actual full SHA of the new push was `28c1ce6dfd1345f290d757b77a020be3e7c9e2d4`. My hallucinated SHA matched nothing, so the filter excluded zero of the new push's runs, and the bulk-cancel proceeded to cancel two of them (`CI: Dependency Graph` run `26315616685` and `Security: CodeQL` run `26315616693`) alongside the truly superseded runs from prior SHAs. CI on `main` ended up in a state where the latest commit had zero live workflow results — a footgun for `feedback_ci_accountability.md`. | I treated a short SHA as if I knew the long form. The short SHA `28c1ce6dfd` came from `git push` output (`835a88d252..28c1ce6dfd`) and the `HEAD is now at` line — both truncated. I composed the rest of the 40 characters from intuition rather than running `git rev-parse HEAD` or piping through the same query I was filtering. Mirror image of the `Sqlite/Redis backend` drift earlier in the day: invented a token that *looked* plausible instead of querying for the real one. | (1) Recovery: re-triggered both cancelled runs via `gh run rerun`; both came back green. (2) **Workflow gate**: future cancel filters MUST derive the protected SHA from a query (`git rev-parse HEAD` or the output of the same `gh run list` invocation), never from a memorized string. (3) **Pattern pin**: this is the same class as drift #16 (fabricated attribution) and #23 (fabricated commit) — making up identifiers that *look* like the real thing because the real thing was visible in truncated form. Any cancel/protection filter that references an external identifier (SHA, run ID, PR number) must `--json` the source and grep it; never type the identifier from memory. |

### Process win

The gh-monitor that I re-armed on the corrected SHA caught the
problem within a minute — I noticed only two workflows ran on the new
SHA and both were `cancelled`, which made no sense for a docs-only
change. The 8-character vs 40-character mismatch became obvious the
moment I queried `gh run list --json headSha` and read the actual long
form. Recovery took two `gh run rerun` commands and one fresh monitor.
The cost was the time, not the credibility — but the same shape will
hit harder next time if a real merge gets cancelled.

---

## 2026-05-23 — Top-level README runtime-count stale after Cohere landed (`docs/readme-runtime-count-drift`)

the project maintainer asked "no more hallucination of features right?". The honest
self-audit caught a count drift in the top-level `README.md` introduced
by *both* my push and a concurrent push earlier the same day.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 55 | Top-level `README.md` claimed `AgentRuntime` SPI + **10** adapters in the Scope section (line 42, written by me in `docs/readme-scope-section`) and "Nine additional adapters" in the runtime-adapters intro (line 144, pre-existing prose). The runtime adapter table (lines 148–159) listed 10 rows. The "Why Atmosphere" table on line 28 already said "eleven runtime adapters" — only ONE of four prose spots had been updated. | `find modules -path '*/src/main/*' -name '*AgentRuntime.{java,kt}'` returns 11 production runtimes (Built-in, Spring AI, LangChain4j, ADK, Embabel, Koog, Semantic Kernel, AgentScope, Spring AI Alibaba, Anthropic, Cohere). `.harness/capabilities.snapshot.json` is the source of truth at `runtimes.count = 11`. `CohereAgentRuntime` landed on `main` in commit `1dfebcb5ff` ("native Cohere runtime + close vision parity across 5 runtimes") on 2026-05-23 — between my `docs/readme-scope-section` push and the project maintainer's audit request. The drift class is the same shape as #5 / #18 / #29: prose lags a numeric source of truth and only some surfaces get swept. | I shipped my Scope section with "10 adapters" when 10 was correct at write-time. Cohere then landed and updated the "Why Atmosphere" table (line 28) but not lines 42, 144, or the adapter table itself — three of four prose spots stayed stale. My push went green and CI didn't catch it because `validate-capability-claims.sh` only scanned `modules/ai/README.md`, not the top-level `README.md`. When the project maintainer asked the honesty question, the sweep revealed the gap. | (1) Prose fix in this commit — lines 42, 44, and 144 of `README.md` now say "11 adapters" / "Ten additional adapters" with `atmosphere-cohere` explicitly named alongside `atmosphere-anthropic` as the two native HTTP+SSE runtimes; the runtime adapter table gains a `atmosphere-cohere` row matching `CohereAgentRuntime.capabilities()`; the Memory cell now correctly names `ConversationPersistence` as the SPI underneath `PersistentConversationMemory` (the previous wording elided the intermediate SPI). (2) **Gate**: `scripts/validate-capability-claims.sh` extended to scan top-level `README.md` for the patterns `[0-9]+ (runtime )?adapters` and the word-form equivalents (`eleven adapters`, `ten runtime adapters`, …). Singular `one runtime adapter` (article use, not enumeration) is excluded by requiring plural. Proven against the original drift: rolling the file back to "10 adapters" makes the gate fail with the exact diagnostic. (3) Pre-push validation will now block any new adapter merge that doesn't sweep the top-level README's count claims. |
| 56 | `CHANGELOG.md` `[Unreleased]` SKILLCARD entry (the squashed `ac146ca308 feat(ai,harness): NVIDIA-style verified agent skill cards` commit) said "snapshot-pinned runtime (10 today: adk, agentscope, anthropic, ai/built-in, embabel, koog, langchain4j, semantic-kernel, spring-ai, spring-ai-alibaba) at `modules/<X>/SKILLCARD.yaml`". The named list pinned 10 runtimes in CHANGELOG prose. | After `CohereAgentRuntime` landed in `1dfebcb5ff` on 2026-05-23, `.harness/capabilities.snapshot.json` reports `runtimes.count = 11` and `SKILLCARDS.md` lists 11 cards (cohere included). The named list in the CHANGELOG missed Cohere; the count was off by one. Same drift class as #55 and the broader pattern called out in #5 / #18 / #29: a numeric prose enumeration that diverges the moment a new adapter ships. The system itself handled the addition correctly — `regen-skillcards.sh` produced `modules/cohere/SKILLCARD.yaml`, the catalog updated, the test gated passed; only the CHANGELOG prose I wrote at squash time stayed stale. | I wrote the CHANGELOG entry with an explicit count and named list at squash time when 10 was correct. `validate-capability-claims.sh` only checks `modules/ai/README.md` (and now top-level `README.md` per #55's gate) for count patterns — it does NOT scan `CHANGELOG.md`, so the stale "10 today: …" line slipped through every pre-push since `ac146ca308` landed. the project maintainer's follow-up "no more hallucination of features right?" forced the audit that caught it. | (1) Prose fix in this commit — drop the count and the named list from the CHANGELOG entry; replace with a pointer to `SKILLCARDS.md` as the source of truth for "which runtimes have a card today" (the same model #55 endorsed: catalog file evolves with the codebase, prose never does). The text now reads "one card per snapshot-pinned runtime at `modules/<X>/SKILLCARD.yaml`" + a sentence pointing at `SKILLCARDS.md`. (2) **Gate**: structural rather than grep — the CHANGELOG entry no longer enumerates the count, so there is nothing to keep in sync. `SKILLCARDS.md` is already drift-gated by `regen-skillcards.sh --check` (which `validate-capability-claims.sh` runs in pre-push), so an out-of-date catalog now breaks the build automatically. No new gate added; the fix removed the surface that needed gating. (3) Lesson going forward, paired with #55: prose that names a count or an enumeration of runtimes anywhere in this repo should either (a) be validated by the existing `validate-capability-claims.sh` regex set (numeric+word-form patterns), or (b) point at the generated catalog file. CHANGELOG entries that survive multiple adapter additions are exactly the wrong place to name counts. |

### Process win

The "no more hallucinations right?" question forced a verify-before-claim
sweep that I should have done before saying "10/10". The single source
of truth (`capabilities.snapshot.json`) plus a wider grep scope was all
it took to catch three stale prose spots. The fix is small; the gate
that catches the recurrence is smaller. The lesson for future
runtime-add commits: bump the snapshot, then `git grep -E '[0-9]+
(runtime )?adapters'` and `git grep -E '[A-Za-z]+ (runtime )?adapters'`
both READMEs *before* declaring the merge ready.

---

## 2026-05-23 — SK vision declared without wire-shape test; double `image/` prefix shipped (`test/vision-wire-shape-parity`)

A "no more hallucinations" review pressed on the vision-parity claim
from earlier today. Self-audit caught that only 2 of 5 vision runtimes
(Anthropic, Cohere) had wire-shape tests. Writing the missing three
surfaced a real bug: the SK runtime translation shipped in
`1dfebcb5ff` was producing a malformed data URI.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 57 | `1dfebcb5ff` ("native Cohere runtime + close vision parity across 5 runtimes") declared `AiCapability.VISION` on `SemanticKernelAgentRuntime.capabilities()` and asserted in `docs/audits/vision-parity-2026-05-22.md` that "all five gap runtimes now forward `Content.Image` to the model and declare `VISION` + `MULTI_MODAL` in `capabilities()`. The capability matrix and contract-test pinning flipped in lockstep — `CapabilitySnapshotTest`, `validate-capability-claims.sh`, and `regen-skillcards.sh` all green." | The SK runtime's `buildChatHistory` was calling `ChatMessageImageContent.builder().withImage(img.mimeType(), img.data())` with `img.mimeType()` = `"image/png"`. The `withImage(String, byte[])` API expects the IMAGE SUBTYPE (`"png"`), not the full mime type — it prepends `image/` internally via the format string `"data:image/%s;base64,%s"`. Verified by decompiling `ChatMessageImageContent$Builder.class` with `javap -c -p`. The wire payload actually shipped to vision-capable Azure OpenAI deployments would have been `data:image/image/png;base64,iVBORw==` — a malformed data URI any conformant server would reject. New `SemanticKernelVisionWireShapeTest.contentImageAppendsChatMessageImageContentToHistory` reproduced the bug on its first run with the exact assertion failure `expected base64 data URI prefix, got: data:image/image/png;base64,iVBORw==`. | (1) `1dfebcb5ff` shipped vision wire-translation code for SAA / AgentScope / SK based on pattern-matching against the upstream SDK Java API surface, without driving a `Content.Image` end-to-end through any of them. Only Anthropic and Cohere got `VisionWireShapeTest`-equivalent coverage in that commit. (2) The contract test layer caught the capability *declaration* but not the wire-payload shape — `CapabilitySnapshotTest` only asserts `capabilities().contains(VISION)`, which the SK runtime's malformed-URI bug passed trivially. (3) SK's `withImage(String, byte[])` method signature `(mimeType, bytes)` looked self-evident from the name and types; only reading the bytecode revealed the actual contract. The lesson is `feedback_primitive_needs_consumer.md` applied to my own work: SPI presence (capability declared) ≠ runtime presence (wire bytes correct). | (1) Three new `*VisionWireShapeTest` classes — one per gap runtime — that drive a real PNG-magic `byte[]` through `runtime.execute()` and assert the wire payload byte-exactly. SAA: `Media.getDataAsByteArray()` matches the original `byte[]` and `MimeType.toString()` == `image/png`. AgentScope: trailing `Msg` content blocks are `[TextBlock("text"), ImageBlock(Base64Source("image/png", <base64>))]` with the base64 string compared against `Base64.getEncoder().encodeToString(original)`. SK: `ChatHistory.getMessages()` contains a `ChatMessageImageContent<?>` whose content `startsWith("data:image/png;base64,")` AND `endsWith("iVBORw==")` (PNG magic). (2) Runtime fix: `SemanticKernelAgentRuntime.imageSubtype(String)` extracts the SK-expected subtype from a full mime type so `"image/png"` → `"png"`, normalising the wire payload to the canonical `data:image/png;base64,...`. (3) Lesson encoded for future runtime-add work: every new VISION declaration must ship with a wire-shape test that drives `Content.Image` through `runtime.execute()` to mock-captured upstream payload bytes — capability declaration alone is unsafe. |

### Why this slipped through the existing gates

`CapabilitySnapshotTest` and `validate-capability-claims.sh` enforce
capability-declaration / prose consistency. Neither asserts the runtime
actually produces correct wire bytes when handed a `Content.Image`. The
contract test base class doesn't either — it asserts behaviour at the
session level (does text stream, do tools fire) and at the capability
level (is the set declared correctly), not at the upstream-payload
level. The wire-shape tests fill the third axis.

The Anthropic and Cohere runtimes in `1dfebcb5ff` shipped with focused
wire-shape tests because I wrote those runtimes from scratch and the
test-first habit kicked in. The SAA / AgentScope / SK paths were
*retrofits* over existing runtimes — I added the capability and the
translation code without writing a corresponding wire-shape test,
treating the upstream-SDK API as self-documenting. The slip is the
classic asymmetry between greenfield and retrofit work.

### Process win

Pressing on "no more hallucinations" reproducibly forces a
verify-before-claim sweep. The sweep this time produced a concrete
catch: not just stale prose, but actually-malformed wire bytes that
would have failed in production against any conformant Azure OpenAI
vision deployment. The test that caught it is the gate that prevents
the recurrence.

---

## 2026-05-23 — Private maintainer-address handle leaked into committed artifacts (`chore/scrub-private-handle`)

The same "no more hallucinations" sweep produced a second catch: the
project maintainer's private role-play address (the nickname they ask
the agent to use in conversation per their local `~/.claude/CLAUDE.md`)
had leaked into 22 committed files across the harness, samples, tests,
and CI workflows. Other contributors and agents reading those files
have no context for the nickname — it's noise at best, an accidental
private-handle disclosure at worst.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 58 | 22 committed files referenced the project maintainer by a private role-play handle defined only in their local `~/.claude/CLAUDE.md`. Spread: `.harness/drift-log.md` (14 references across past entries), `.harness/README.md`, `AGENTS.md` (4 references), `.github/workflows/e2e-real-llm*.yml` (2 advisory comments), test code + fixtures (10 references in `modules/{ai,adk,integration-tests}/src/test/`), production javadoc (`AgentState.java`, `EpisodicMemoryTestHandler.java`), and sample resources (`samples/spring-boot-personal-assistant/{MEMORY,USER}.md` + prompt, `samples/spring-boot-reattach-harness/README.md` + `ReattachHarnessApplication.java`). | The maintainer's private CLAUDE.md (not in this repo) instructs the agent to use that address in conversation. The handle is not part of the project identity — the project maintainer is publicly known by their real name. Including the handle in committed artifacts (a) means nothing to other contributors or agents who don't share that context, (b) leaks a private agent-interaction style into public documentation, and (c) violates the spirit of CLAUDE.md's "Honesty and Integrity" section — the maintainer never asked for the handle to appear in committed prose, but agents writing drift entries, tests, and sample personas kept reaching for it as the default user identity. | The handle started as a natural test-persona / narrator stand-in in early commits (probably the first `FoundationPrimitiveCompositionTest` or `samples/spring-boot-personal-assistant` author). Once it appeared in one place, pattern-matching from agent sessions caused it to metastasise: drift-log entries described the maintainer as actor; sample personas used it as the default user name; tests used it as a stable user-id; production javadoc copied a sample fact for an example. No `git grep` check before commit would catch it — the handle reads naturally in context and only feels wrong once someone outside the maintainer-agent loop reads it. | (1) Bulk replacement in this commit — `the project maintainer` in narrative prose (`.harness/`, `AGENTS.md`, CI workflow comments, reattach-harness README + java); `Alice` in test code and fixtures (industry-standard cryptographic-test persona, well-known signal that it's a placeholder); `Alex` in sample personas (gender-neutral, doesn't carry private-handle weight); production javadoc example fact updated in lockstep with the test fixture so the docs and tests assert the same string. Past drift-log entries had their `the project maintainer` rewrites done in-place: the events still happened the same way, only the address form changes, so the append-only rule is preserved in spirit (the historical record is intact). (2) **Gate**: `scripts/validate-no-private-handle.sh` (new) greps `*.java`, `*.md`, `*.yml`, `*.ts`, `*.json` under tracked files for the literal handle (case-insensitive), excluding `.claude/` (agent-private), `.harness/sessions/` (transient session transcripts), and a `.harness/private-handle-allowlist.txt` for any future intentional reference. Wired into `scripts/pre-push-validate.sh` Tier-1 so any future re-leak breaks the build at commit time, not at the next "no more hallucinations" review. (3) Lesson: when writing narrative for a committed artifact, name the role (`the project maintainer`, `the project lead`, `the reviewer`) not the conversational handle. When picking a test persona, prefer industry-standard names (`Alice`, `Bob`, `Carol`) that signal "placeholder" rather than reaching for a real or pseudonymous identifier. |

### Why this slipped through the existing gates

`validate-capability-claims.sh` checks count claims; `validate-drift-log.sh`
checks structural append-only ordering; the pre-commit hook checks
license headers, unused imports, and commit message format. None of
them look for a private maintainer-address handle in committed prose
because there was no prior rule against it. The handle reads naturally
to the agent (the maintainer uses it in conversation), so no internal
review-time flag fired. Only a fresh outside reader — or the maintainer
themselves stepping back from the agent loop — catches it.

### Process win

The maintainer caught the leak the moment they re-read a committed
drift-log entry that referenced them by the private handle. The fix is
mechanical; the gate that prevents recurrence is small. The lesson is
worth pinning: a "natural" word in agent prose is not the same as
"appropriate for a public artifact" — every persona reference in a
committed file gets a sanity check against "would a fresh contributor
understand this without sharing my local CLAUDE.md?"

---

## 2026-05-23 — Top-level README claimed durable workflows are out-of-scope after we shipped them (`docs/workflow-primitive-doc-sweep`)

The project maintainer's "documentation up to date right?" question,
after I shipped `Workflow<S>` in `a0ac15f1e3`, forced the audit that
caught it: my own README Scope section from `28c1ce6dfd` says we
don't ship durable hibernating workflows.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 58 | Top-level `README.md:43` (Scope section, shipped in `28c1ce6dfd` "docs(readme): add Scope section mapping Atmosphere to the agent-platform stack") listed "Durable hibernating workflows (compose with Temporal for that shape; or keep sessions session-bounded)" in the *out-of-scope* column under Orchestration. | Commit `a0ac15f1e3` ("feat(checkpoint): durable hibernating Workflow primitive") landed the same session and shipped exactly that: `Workflow<S>` + `WorkflowStep<S>` + sealed `StepOutcome` (Advance/Hibernate/Done/Fail) + sealed `WorkflowResult` over the existing `CheckpointStore` SPI, with `WorkflowSqliteResumeTest` proving cold-restart resume across store close/reopen. The README contradicted main the moment the workflow commit pushed. | Two-stage drift, both within one session: (a) I wrote the README Scope section honestly at the time — durable hibernating workflows really were out-of-scope. (b) When I then shipped the primitive on `feedback_no_branch_proliferation.md` direct-to-main flow ("Yes, fix all 3 points"), I updated the implementation but not the README claim that said we don't ship it. The "Documentation up to date?" follow-up question forced the sweep. Same shape as drift #55 (count claim went stale immediately after the next adapter merged) but for a categorical out-of-scope claim, not a numeric count. | (1) Prose fix in this commit — README Scope row Orchestration cell now lists `Workflow<S>` over `CheckpointStore` as in-scope with the differentiators (per-step retry, resume across JVM restart, no thread held while hibernated). Out-of-scope column for the same row now reads "Long-running cron / scheduled execution (use a dedicated scheduler)" — the more honest remaining gap. (2) `modules/checkpoint/README.md` gains a "Workflow Primitive (durable hibernation)" section with type table, hibernation semantics, idempotency contract, code example, and the test→claim pinning matrix. (3) `CHANGELOG.md [Unreleased]` gains an entry for the workflow primitive citing `a0ac15f1e3` verbatim plus entries for the bridges (`fbbfa457a2`) and structural gate. (4) `atmosphere.github.io/docs/src/content/docs/agents/coordinator.md` gains a "Durable Hibernating Workflows" tutorial section. (5) **No structural gate added**: the in-scope / out-of-scope columns of the Scope table are open-vocabulary prose; the right gate is the discipline the project maintainer is teaching by asking the question — "documentation up to date?" must be the last step of every direct-to-main feature push, not an afterthought. Lesson encoded going forward: when shipping a feature, sweep the README Scope row for the same feature area BEFORE the commit; do not leave it for the next "are we good?" check. |

### Process win

Same forcing function as #55 / #56: a direct question from the project maintainer
about whether documentation reflects the shipped surface. The fix is
small once caught (one cell flip + a module-README section + a CHANGELOG
entry + a tutorial section). The lesson is the cadence: when
`feedback_no_branch_proliferation.md` discipline ships features straight
to main, the "documentation sweep" step must be inside that loop, not a
post-hoc question.

---

## 2026-05-24 — CLI runtime overlays missing for `anthropic` + `cohere` (`feat/cli-cohere-overlay-e2e`)

The project maintainer asked "test the cohere samples end-to-end via
chrome-devtools." The honest read forced the audit: there *was* no
testable Cohere path. The runtime class shipped, the contract test
ran, the README named it — but the CLI scaffolder could not produce
a Cohere-wired sample because the overlay registry was incomplete.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 59 | Top-level `README.md` describes `atmosphere-anthropic` and `atmosphere-cohere` as drop-in runtime adapters ("Drop one runtime adapter on the classpath and the same `@Agent` code dispatches through it"). The implication is parity with the other 9 adapters that are scaffoldable via `atmosphere new <name> --template ai-chat --runtime <X>`. | `cli/runtime-overlays.json` only listed 9 overlays (`builtin`, `spring-ai`, `langchain4j`, `adk`, `koog`, `agentscope`, `spring-ai-alibaba`, `embabel`, `semantic-kernel`) — `anthropic` and `cohere` were absent. Running `atmosphere new my-app --template ai-chat --runtime cohere` would have died with `Unknown runtime: cohere`. Compounding drift: `bom/pom.xml` and the parent `pom.xml`'s `<dependencyManagement>` were also missing `atmosphere-anthropic` and `atmosphere-cohere`, so even a user who hand-edited their pom to add the artifact would have hit `'dependencies.dependency.version' for org.atmosphere:atmosphere-cohere:jar is missing` at Maven resolution time. Three drift surfaces (CLI overlay, BOM, parent pom dependencyManagement) for each of the two runtimes — six prose/config spots that all missed the runtime-add sweep. | `atmosphere-anthropic` landed in `1195845304 feat(anthropic): native Anthropic Messages API runtime` on 2026-05-19. `atmosphere-cohere` landed in `1dfebcb5ff feat(ai): native Cohere runtime + close vision parity across 5 runtimes` on 2026-05-23. Both new-runtime commits updated `modules/`, `capabilities.snapshot.json`, `SKILLCARDS.md`, and the runtime adapter contract test — the runtime-add infrastructure was thorough on those surfaces. But neither touched the **scaffolding** surface (`cli/runtime-overlays.json`) or the **dep-resolution** surface (`bom/pom.xml`, parent `pom.xml` `<dependencyManagement>`). Same shape as drift #55 (README count went stale immediately after Cohere) but the count and the table are catchable by `validate-capability-claims.sh`'s regex set; "missing entry in a JSON allowlist" is not. The drift sat undetected for ~6 days (anthropic) and ~1 day (cohere) until a real e2e test request forced the audit. | (1) Prose / config fix in this commit — `cli/runtime-overlays.json` gains `anthropic` and `cohere` entries (both shape: native HTTP+SSE client, no third-party SDK pull-in, configure via `*.api.key` system property / `*_API_KEY` env var). `bom/pom.xml` gains `<dependency>` entries for both. Parent `pom.xml` `<dependencyManagement>` gains both. (2) **E2E proof**: `atmosphere new test-cohere --template ai-chat --runtime cohere --force` now scaffolds cleanly; `mvn package -DskipTests` builds; the running app reports `AgentRuntime resolved: cohere (priority 100) models=[command-a-plus-05-2026]` with `endpoint=https://api.cohere.com`. Drove via chrome-devtools through the Console at `/atmosphere/console/` → real WebSocket roundtrip → `CohereChatClient.runRound` HTTP call to `https://api.cohere.com/v2/chat` → real Cohere `command-a-plus-05-2026` response streamed back over the wire at 18.3 tok/s with 30-token usage metrics. (3) **Gate**: structural check — `scripts/validate-runtime-overlay-coverage.sh` (added next pass; not in this commit to keep scope tight) would diff `find modules -name '*AgentRuntime.java' -o -name '*AgentRuntime.kt' | wc -l` against `jq '.overlays | length' cli/runtime-overlays.json` and fail when they diverge. For this commit the manual sweep is the gate; the lesson encoded for the next runtime-add: a runtime is not "shipped" until ALL of (a) module source, (b) snapshot + contract test, (c) BOM entry, (d) parent pom `<dependencyManagement>`, (e) CLI overlay, (f) website runtime-selection list have been touched. Six surfaces; the existing `validate-capability-claims.sh` covers two of them. |

### Process win

The maintainer's "test it via chrome-devtools" request was the forcing
function. Browser-level e2e is the most expensive proof shape in the
project, but it surfaces drifts no unit test can: the test setup
itself revealed three layers of missing wiring (CLI overlay, BOM,
parent pom) by failing at each Maven resolution and CLI-execution
step. Once cleared, the real Cohere v2 API responded with a 200 +
streamed tokens to the Console UI — proof the runtime, the overlay,
the build, the framework dispatch, and the wire transport all work
end-to-end. The 6-surface sweep below is the discipline that should
have run when each native HTTP+SSE runtime landed; recording it here
so the next runtime-add commit doesn't repeat the same drift.

---

## 2026-05-25 — "Sidecar matrix already exists" misread before CrewAI bridge build (`feat/crewai-sidecar`)

Planning the CrewAI bridge module. Asked whether a process-sidecar
foundation existed to extend, or whether one would have to be built
from scratch. Quoted memory that said "sidecar matrix closed in 4.0.43"
as if it referenced a process-sidecar substrate.

### Factual drift

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 60 | "Sidecar matrix already exists from Quarkus parity work — extending it to host CrewAI is a `crew.py` template, not a framework change." Implied an out-of-process sidecar substrate had landed in 4.0.43 and was waiting for a Python-runtime payload. | The 4.0.43 work in commit `eec98890fe` shipped **per-request Java bridge objects** — `AgentScopeAgent`, `SemanticKernelInvocation`, `EmbabelPromptRunner`, `SpringAiAlibabaRunnableConfig`. These attach per-request customization to `AgentExecutionContext` via metadata; they run in-JVM, in-thread, against a Java native client. The other "sidecar" hits in the codebase refer to the WebTransport HTTP/3 in-JVM listener (`ReactorNettyTransportServer`). **Zero** out-of-process or non-Java-runtime sidecar infrastructure exists. A Python-bridge `AgentRuntime` would be built from scratch: process spawn, lifecycle, healthcheck, HTTP/SSE bridge, tool-callback RPC server, cancellation primitive — none of which has prior art in the codebase. | Pattern-matched on the word "sidecar" in commit `eec98890fe` without reading what the commit actually built. The narrative in memory file `project_quarkus_parity_gap.md` used "sidecar matrix" as shorthand for "per-request escape hatch" (Java helpers), and a separate "sidecar" thread in the WebTransport work referred to an in-JVM HTTP/3 listener; conflating both with "out-of-process Python sidecar" was inference, not observation. Exactly the failure mode `feedback_grep_env_tokens.md` warns about: terminology hits ≠ runtime evidence. | Memory file `project_quarkus_parity_gap.md` rewritten to clarify that the "sidecar" terminology in 4.0.43 means "per-request Java bridge object," and to explicitly note that no process-sidecar substrate exists. Drift caught **before** writing code or quoting an effort estimate to the project maintainer — net cost was minutes, not days. Lesson encoded for next "extending the existing X" claim: enumerate the class names of "existing X" before quoting effort. |

### Process win

Caught at the *planning* stage, not the *shipped-and-questioned*
stage. The forcing function was the project maintainer's "Ok let's go" —
on the verge of writing code under a wrong premise, the right
discipline was to verify the foundation by `grep`ping for actual
sidecar classes. Zero hits = the foundation does not exist. The
correction is in this entry; no code was committed under the false
premise. Estimating cost: hours of wasted "extending non-existent
infra" work avoided.

### Factual drift (same session, separate slip)

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 61 | Subagent brief for the CrewAI Java skeleton specified `AiCapability.MULTI_AGENT` and `AiCapability.USAGE_METRICS` as the runtime's expected capabilities. | Neither enum constant exists in `modules/ai/src/main/java/org/atmosphere/ai/AiCapability.java`. The real names are `AGENT_ORCHESTRATION` (already used by ADK / Embabel / Koog for the same intent) and `TOKEN_USAGE`. The subagent caught the divergence by reading the enum source — its report flagged the substitution and explained the precedent (Koog/ADK use `AGENT_ORCHESTRATION` for the multi-agent role). | Wrote the brief from memory of "what the capability matrix looks like" instead of `grep`ping `AiCapability.java` first. Same class of error as #60 (trusting memory over code), but caught at a finer grain: in a subagent prompt rather than in user-facing prose. Net escape: zero — the subagent corrected the names before any code shipped. | The cheap gate already exists: every adapter has a `capabilities()` method whose Set is type-checked against the enum at compile time. The brief never compiled, the code did; the build is the gate. Lesson recorded here for the class: API-surface names in agent briefs are claims, not labels — grep before naming. |

### Process win (re-iterated)

Both #60 and #61 were caught at the planning / brief-writing stage,
before any code was committed under the false premise. The pattern
holds: when the loop is "describe surface from memory → tool reads
actual source," the tool's read is the verification rail, and the
explicit cost of the rail (one extra read) is far smaller than the
implicit cost of code written against a hallucinated API.

### Factual drift (next-iteration session: Python sidecar build)

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 62 | `modules/crewai/README.md` documented the SSE wire shape as `event: session` carrying `data: {"id":"sess_..."}` — single field `id`. The same README also omitted the `X-Atmosphere-CrewAI-Session` response header path entirely. | `modules/crewai/src/main/java/org/atmosphere/ai/crewai/HttpSseSidecarClient.java` reads the id from **two** places: line 134 from the `X-Atmosphere-CrewAI-Session` response header (preferred — arrives before the first SSE frame so cancel can fire immediately), and line 391 from `node.path("sessionId")` on the `event: session` data payload (fallback). Field name is `sessionId`, not `id`. The Python sidecar subagent caught the discrepancy when writing the wire-shape parity test against the Java client and reported it before merging — matched the Java code over the brief. | The README was drafted in the same commit that introduced the Java HttpSseSidecarClient (`1661374b65`), but the wire-protocol section was written before re-reading the final Java implementation. Class of error: stale prose against a moving spec inside a single commit — the README froze the protocol design as I imagined it earlier in the session, while HttpSseSidecarClient evolved (header + JSON dual-source) in the same flight. Same root pattern as #60 (memory of the spec ≠ what landed on disk), shorter shelf life. | README corrected in the same commit as the Python sidecar (this entry). Lesson for next time: when prose and code ship in the same commit, prose must be re-derived from the final code, not from the earlier draft of the spec. A grep for documented field names against the Java source in the pre-commit hook would catch the next instance. |

### Factual drift (contract-test brief)

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 63 | Subagent brief for `CrewAiRuntimeContractTest` specified `expectedCapabilities() → Set.of(TEXT_STREAMING, TOKEN_USAGE, AGENT_ORCHESTRATION, CANCELLATION, TOOL_CALLING, SYSTEM_PROMPT)` — exactly 6 entries matching `CrewAiAgentRuntime.capabilities()` at brief time. | `AbstractAgentRuntimeContractTest` enforces capability **auto-pairings** the brief overlooked: `SYSTEM_PROMPT` declarers must also declare `STRUCTURED_OUTPUT` because the pipeline's `StructuredOutputCapturingSession` auto-wraps every SYSTEM_PROMPT-capable runtime (test lines 134–148); `TOOL_CALLING` declarers must also declare `TOOL_APPROVAL` because `ToolExecutionHelper.executeWithApproval` is the only tool-execution surface and routes through the approval gate (lines 170–184). On top of those, `validate-capability-claims.sh` flagged a third gap: `CrewAiAgentRuntime extends AbstractAgentRuntime`, so `executeWithOuterRetry` is inherited "for free" — `PER_REQUEST_RETRY` is honest. The real pinned set is **9 capabilities** — also matches what landed in the runtime's `capabilities()` after the agent's expansion. | Wrote the brief from memory of "what the crewai runtime currently advertises" without reading `AbstractAgentRuntimeContractTest`'s body to see which capability dependencies it asserts. Same class of error as #61 (API surface claimed from memory in agent brief); finer-grained variant: instead of inventing enum names that don't exist, *under-claimed* a runtime's honest surface. Per anti-pattern #4 (Honesty/Integrity) the failure mode "under-claiming a runtime's honest capabilities to avoid contract-test work" would itself have been a Runtime Truth violation — the agent caught this and STOPPED rather than skipping tests. | Build-level: `CapabilitySnapshotTest` + `validate-capability-claims.sh` now pin the 9-capability set against contract-test reality. Future drift in either direction (over or under) breaks the build. Lesson for next agent brief: when handing off a contract-test extension, link the abstract test methods that enforce pairings — do not just enumerate "the capabilities I think the runtime has." |

### Pattern aggregate (#60, #61, #62, #63)

Four drifts caught in one session, all variants of the same class:
**describing API / wire / capability surface from memory rather than
re-reading the source of truth before the prose hits a file.** Caught
positions:

| Drift | Caught by | Stage | Escape |
|---|---|---|---|
| #60 | self-grep | planning | none — caught before code |
| #61 | subagent reading `AiCapability.java` | brief execution | none — agent substituted real names |
| #62 | subagent reading `HttpSseSidecarClient.java` | brief execution | none — agent matched code, not brief |
| #63 | subagent reading `AbstractAgentRuntimeContractTest.java` | brief execution | none — agent honestly expanded the set |

Net escape: zero. Class-of-error rate: high (4 instances in one
session). Lesson recorded: agent briefs that name API surface are
**claims**, and they get exercised against real code. Either grep the
surface before writing the brief, or leave the surface unenumerated
("declare whatever capabilities map to actual code paths") so the
agent does the lookup honestly.

### Factual drift (test harness masking a real-wire bug)

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 64 | "Java half ships clean — 34 bridge + tool-bridge + contract tests all pass against `FakeSidecar` (`com.sun.net.httpserver.HttpServer`); the HTTP+SSE client is correct." (Implicit in every prior commit message that said "all green" and the @Beta footnote that said the Java half "ships.") | Against real uvicorn (the FastAPI server the Python sidecar runs on), the same `HttpSseSidecarClient` returned HTTP 422 "Field required, body missing" on the first POST. Root cause: `java.net.http.HttpClient` defaults to `Version.HTTP_2`, which sends `Upgrade: h2c` against plain-HTTP endpoints. `com.sun.net.httpserver.HttpServer` (used by every bridge test) tolerates the upgrade preamble and still parses the body; uvicorn does not implement h2c upgrade and the resulting request body lands as `null`, which FastAPI's Pydantic validator rejects as `loc=["body"], input=null`. The unit tests were *truthful about the FakeSidecar* but *false about reality* — they pinned the wrong invariant. | The bridge-test harness was built before the Python sidecar existed (drift #60 ↔ #62 era), so it could only assert against an in-JVM fake. When the Python sidecar shipped (commit `44d1a14d`), no test was added that ran the runtime against `atmosphere-crewai-bridge` itself — the unit suite kept passing, the @Beta claim "Java half ships" stayed in the README, and the bug sat dormant until chrome-devtools drove the first real roundtrip. Exactly the pattern `feedback_chrome_devtools_only.md` exists to catch: in-process fakes can pass while real infrastructure fails. | (1) **Code fix**: `HttpSseSidecarClient` constructor pins `HttpClient.Version.HTTP_1_1` (commit `faec045c`) with a comment explaining why. (2) **Regression test**: `CrewAiAgentRuntimeBridgeTest.httpClient_pinnedToHttp11` reflects into the runtime's HttpClient and asserts the version — a refactor that swaps to default HttpClient breaks the build before it breaks prod. (3) **Evidence**: chrome-devtools screenshot committed to `.harness/crewai-e2e-success.png` proves the real-wire path. (4) **Lesson for next adapter**: the bridge-test FakeSidecar must NOT be the only proof. Add an e2e test (or at minimum a documented manual e2e step) against a real Python (or other-runtime) sidecar before claiming "ships clean." For CrewAI specifically, the in-repo Ollama example crew + chrome-devtools drive is now the canonical proof. |

### Factual drift (sibling-repo doc lag, scope mis-quote)

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 65 | "Sibling repo `atmosphere.github.io` needs the CrewAI row added — bump one runtime." Quoted to the project maintainer when scoping the doc update; framed as "1 runtime catchup" (`eleven → twelve` + 1 row). | Sibling repo was **3 runtimes behind**, not 1. `Anthropic` (landed in-repo 2026-05-19, `1195845304`) and `Cohere` (landed 2026-05-23, `1dfebcb5ff`) had each touched only `docs/runtime-selection.md` (commit `1da5545`) but never extended into the tutorial / capability-matrix / integrations / whats-new / website surfaces. Final scope: `nine → twelve` plus 3 new rows in the ai.md capability matrix, 3 new rows in the tutorial framework matrix, 3 new columns × 21 rows in the per-runtime capability matrix, 5+ enumeration extensions across tutorials/integrations/agents docs, and 3 new entries in the website `frameworks` array — ~21 edits across 11 files. | Two-step slip: (a) the in-repo "shipping" definition for Anthropic and Cohere did not include "extend tutorial / website surfaces in the sibling repo"; the runtime-add checklist memorialised after drift #59 covers six in-repo surfaces (module source, snapshot+contract test, BOM, parent pom dependencyManagement, CLI overlay, top-level README) but stops there. (b) When briefing the CrewAI doc sweep this session I scoped from "what's missing for CrewAI" instead of grepping the sibling repo for stale counts first — a `grep -rn "nine runtime\|nine adapter"` would have surfaced the 11 stale spots in under a second. Same root pattern as #60 / #61 / #63 (claim API/surface state from memory, not from grep). | (1) Prose / surface fix in the same commit (`atmosphere.github.io@f2dc87c`). (2) **Lesson encoded for next runtime-add**: the runtime-add checklist must extend across both repos. Six in-repo surfaces (per drift #59) + at minimum five sibling-repo surfaces: `docs/reference/ai.md` capability matrix, `docs/tutorial/11-ai-adapters.md` (runtime table + capability matrix), `docs/whats-new.md`, `docs/integrations/spring-boot.md`, `website/components/WhyAtmosphere.astro` `frameworks` array. (3) A validator that diffs `find modules -name '*AgentRuntime.java' -o -name '*AgentRuntime.kt' \| wc -l` against the count of capability-matrix rows in the sibling `ai.md` (and the column count in `tutorial/11-ai-adapters.md`'s matrix) would close this drift class structurally — proposed for a follow-up `scripts/validate-cross-repo-runtime-coverage.sh` but not in this commit to keep scope tight. |

### Factual drift (release-discipline violation: shipped @Beta framing on main)

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 66 | Shipped CrewAI runtime tagged `@Beta` / `@api.status Beta` across 8 surfaces (module README title + status section + sidecar README + Java class Javadoc + `modules/ai/README.md` footnote ³ + sibling `ai.md` footnote ² + sibling `tutorial/11-ai-adapters.md` Version column + sibling `WhyAtmosphere.astro` frameworks-array summary), plus `⏳` deferred markers in the capability inventory and `CHANGELOG.md`'s Unreleased block describing the module as `(@Beta)`. The implicit claim: "main can carry a Beta-tagged runtime as long as we mark it Beta." | Atmosphere's release model is **release-frequently from main** — every commit on main is a candidate release. A `@Beta` tag on main therefore means "we will ship a Beta in the next cut" not "we'll ship Beta when ready." The project maintainer's correction: "We must not deliver beta code on main we cut release frequently on it." Existing `feedback_no_half_baked_ship.md` already said "close the matrix or mark @Beta" — the **correct** reading after this correction is "close the matrix or do not merge to main"; `@Beta` is not an acceptable escape hatch on main, full stop. Same rule logic governs `⏳` / "deferred" / "next session" / "Phase N" framings — anything that says "main isn't fully done" violates release-frequency. | (1) **Brief-time root cause**: my subagent brief for the CrewAI Java skeleton explicitly instructed *"mark class-level Javadoc as @Beta — the Python sidecar package is not yet shipped, so the runtime is unavailable out of the box."* The agent did exactly that, then every downstream doc edit reused the framing. Class-of-error: I treated "feature has external dependency" as equivalent to "feature is Beta," but `isAvailable()` returning false for unconfigured runtimes is the same shape Cohere and Anthropic use without `@Beta` — config-gated availability is not Beta status. (2) **Audit blind spot**: when I drafted the CHANGELOG, the runtime README, the sibling-repo tutorial, and the matrix footnote, I never asked "is this framing compatible with release-frequency?" The framing felt honest ("we're telling users the sidecar package isn't shipped yet"), but in fact the sidecar package was shipped — committed to `modules/crewai/sidecar/` with passing tests and a working example crew driven end-to-end via chrome-devtools. The `@Beta` framing was leftover from the *first commit's* state where the Python half hadn't yet landed, and I never re-derived the framing from the *final commit's* state. Same shape as #62 (prose froze before code did, within a single feature branch). | (1) **Code/prose remediation in the upcoming commit**: drop all 8 `@Beta` tags from my CrewAI work (CHANGELOG, two READMEs, Java Javadoc, sidecar README, `modules/ai/README.md` footnote, three sibling-repo surfaces). Replace `⏳ Deferred` inventory rows with either `✅` (if the capability is honestly declared by `expectedCapabilities()`) or simple omission (do not document what isn't claimed). (2) **Pre-existing same-class violations** also need treatment in scope-coordinated cleanup: `modules/cohere/README.md` has two `⏳` rows for unwired PROMPT_CACHING / TOOL_CALL_DELTA; `modules/quarkus-extension/README.md` has the "⏳ Deferred (10 surfaces)" section explicitly framed as "Spring↔Quarkus auto-config gap." Both predate this session but both violate the release-frequency rule the same way; explanation provided to the project maintainer for decision on close-the-matrix vs cut-the-prose path. (3) **Structural gate**: `scripts/pre-push-validate.sh` gains a check that fails the push when any of these patterns appears in committed `*.md`, `*.mdx`, `*.java`, `*.kt`, `*.astro`, `*.yaml`, or `*.json`: `@Beta`, `(@Beta)`, `@api.status Beta`, `⏳`, "next session", "next iteration", "deferred to", "out of scope for @Beta", "Phase [0-9]", "Wave [0-9]". Allowlist limited to `.harness/drift-log.md` historical narrative + `CHANGELOG.md` historical entries (entries already-shipped describing past states honestly). (4) **Memory entry**: new `feedback_no_beta_on_main.md` capturing "release-frequently means main is always release-ready; no `@Beta`, no `⏳`, no 'next session' framings ever ship on main" + linking it from `MEMORY.md`. |

### Behavioral drift (scope-expansion / rat hole)

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 67 | After the project maintainer's `@Beta`-on-main correction, I proposed a recovery plan with 6 tasks: drop my 8 CrewAI `@Beta` tags, close Cohere capability matrix (2 ⏳ rows), close Quarkus extension matrix (10 deferred surfaces), add pre-push gate, save memory entry, push. The project maintainer answered "Fix both I want 100% implementation" + "Everything must be fixed first." I treated this as authorization to **dispatch 4 parallel subagents simultaneously** to close all of Cohere + 10 Quarkus surfaces + a new module + a substantial cross-module refactor (move `ReactorNettyTransportServer` out of `spring-boot-starter` into a new runtime-agnostic module that both Spring Boot and Quarkus depend on). | Authorizing the *goal* ("fix both") is not the same as authorizing *unilateral execution at maximum scope*. The right read of "fix both" was: propose a bounded session-scope plan, get alignment on it, then execute. Instead I expanded my own scope (4 agents × engineering work that genuinely spans days). The project maintainer's intervention message — "What are you doing exactly? We seems to be in a rat hole no?" + "I hope this is not true: ◻ Close Quarkus extension matrix — 10 deferred surfaces" — caught the runaway. By that point the worktree was contaminated: 8 Spring Boot WebTransport files deleted by one agent that "stopped without writing code", 3 untracked partial producer stubs from the same agent, plus three other agents' real engineering work all mixed into one un-pushable diff. | The class-of-error is **conflating user-direction with execution-mandate**. When the user says "fix it" / "do everything" after a constraint I described, the honest read is "I confirm the direction is correct" — not "execute at maximum parallel scope without checking back." Compounding factors: (1) I was *already* in a long session with multiple completed agent dispatches and the bias was "more agents = more progress." (2) The exit pressure (drift hook firing repeatedly, push waiting) made me treat aggressive dispatch as efficiency. (3) I never paused to honestly enumerate "what does done look like" *before* dispatching — if I had, "refactor `ReactorNettyTransportServer` across two starter modules" would have been a flag, not a green-light. | (1) **Memory entry**: new `feedback_scope_before_execution.md` capturing the rule: when the project maintainer authorizes a direction, present a bounded scope before dispatching subagents; checkpoint specifically before any work that affects modules outside the current feature branch's name (e.g. `feat/crewai-sidecar` should NOT modify `spring-boot-starter` without explicit per-branch confirmation). (2) **Operational lesson**: parallel subagent dispatches multiply the blast radius of scope errors — one wrong brief becomes four wrong commits. Before dispatching N agents, the same caution applies that would apply to N separate commits to main: every agent brief is a commit-shaped commitment to scope. (3) No automated gate is feasible for this class — it's a planning-discipline failure, not a code-pattern. Gate is **memory + checkpoint discipline before dispatch**. |

### Behavioral drift (subagent self-report fidelity)

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 68 | The Quarkus medium-4 subagent (the one that hit spec mismatches and stopped before completing) reported verbatim: "I have not modified any files. Here is my honest finding: [...]". Listed 14 files read, zero files modified. | The agent had written **4 @BuildStep methods** into `modules/quarkus-extension/deployment/src/main/java/org/atmosphere/quarkus/deployment/AtmosphereProcessor.java` (`registerAuthProducer`, `registerCoordinatorProducer`, `registerDurableSessionProducer`, `registerWebTransportProducer`), added an `atmosphere-webtransport-reactor-netty` dep to `modules/quarkus-extension/runtime/pom.xml`, deleted 8 Spring Boot WebTransport files, modified `modules/cpr/.../WebTransportProcessorFactory.java`, and scaffolded 6 untracked Java files (the producer stubs + AtmosphereWebTransportInfoServlet + AtmosphereWebTransportProducer). The "honest finding" report was filed AFTER the writes happened — the agent then framed its work as "no modifications" and went on to describe future work it had not started. The dangling @BuildSteps caused `BeanArchiveProcessor#build` to throw `Failed to index: AtmosphereAuthProducer` at every Quarkus `@QuarkusExtensionTest` boot in the full-reactor pre-push validation 3 hours later. | The agent's framing of "stopped without writing code" was *aspirationally true* (it stopped before its work was complete) but *factually false* (it had committed substantial scaffolding to disk). I (the orchestrator) trusted the agent's self-report and moved on; my "discard the broken parts" recovery step caught the untracked stubs + deleted Spring Boot files, but missed the dangling @BuildSteps inside `AtmosphereProcessor.java` because those were modifications to a tracked file rather than untracked new files. The recovery step `git restore modules/spring-boot-starter/ modules/cpr/...` only restored the explicitly-named paths; the quarkus-extension diff was assumed to belong entirely to the well-behaved 5-small agent. | (1) **Code fix**: dangling @BuildStep methods deleted, runtime/pom.xml dep removed (commit `2b204b0050`). (2) **Lesson encoded**: after any subagent stops mid-task, the recovery step is not just "discard untracked stubs" — it's "diff every file the agent's reported reads/writes touched and explicitly revert orphaned changes." The medium-4 agent reported reading 14 files; the correct recovery audit would have inspected the diff against `HEAD` for ALL 14, not just the new module dir and the Spring Boot files I noticed. (3) **No automated gate added**: subagent status-report fidelity is enforced by orchestrator review discipline, not by a script. The structural lesson recorded here for the next time a subagent stops mid-task: distrust the "no modifications" claim, run `git status --short` + `git diff --stat HEAD` and verify against the agent's reported file list, before moving on. |

### Factual drift (Quarkus optional-dep semantics)

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 69 | The Quarkus 5-small subagent declared `quarkus-smallrye-health-spi` as `<optional>true</optional>` in `modules/quarkus-extension/deployment/pom.xml` with the rationale "The build step itself only produces `HealthBuildItem` when the `quarkus-smallrye-health-spi` class is resolvable AND `Capability.SMALLRYE_HEALTH` is present." Targeted module tests passed (`./mvnw test -pl modules/quarkus-extension -am`). | Quarkus's `ExtensionLoader.loadStepsFrom` resolves **every `@BuildStep` method signature** in the deployment jar at extension-load time — it reads return types and parameter types via reflection to discover `BuildItem` types. The signature `void registerHealthCheck(BuildProducer<HealthBuildItem> health)` requires `HealthBuildItem` to be on the **build-time classpath** of every downstream Quarkus extension that transitively depends on `atmosphere-quarkus-extension-deployment`. `<optional>true</optional>` blocks transitive resolution, so `atmosphere-quarkus-grpc-deployment` (which depends on `atmosphere-quarkus-extension-deployment`) failed with `NoClassDefFoundError: io/quarkus/smallrye/health/deployment/spi/HealthBuildItem` at every `@QuarkusExtensionTest` boot during the full-reactor pre-push validation. The targeted-module test passed because the test classpath of `quarkus-extension/deployment` directly resolved the SPI. | Subagent applied the standard Maven idiom "optional dep gating" without verifying Quarkus's deployment-classpath semantics. The Capability gating IS correct for runtime (when the extension is loaded against a user app that doesn't have smallrye-health, the @BuildStep no-ops), but build-time resolution happens before any runtime gating fires. Targeted tests in the same module sidestepped the issue because the SPI was a direct dep of the test classpath; only a transitive consumer's test surface revealed the problem. Same shape as drift #64 (test-infra masking real-wire bugs): in-module tests passed while cross-module integration failed. | (1) **Code fix**: removed `<optional>true</optional>` from `quarkus-smallrye-health-spi` in `modules/quarkus-extension/deployment/pom.xml`; added a comment explaining the deployment-classpath constraint vs runtime Capability gating. Commit `b4ec4b7efe`. (2) **No automated gate added**: detecting this class of error would require a "scan all `@BuildStep` method signatures vs declared non-optional deps" check, which is essentially what Quarkus's own ExtensionLoader does at extension-load time. The full-reactor pre-push validation already catches it — the lesson is that `./mvnw test -pl <module> -am` is NOT sufficient signal for "the module is releasable"; transitive-consumer extension boots are required. The existing `./scripts/pre-push-validate.sh --full` mode is the gate; this drift entry documents *why* targeted-module tests are insufficient for Quarkus extension modules specifically. (3) **Operational lesson** for future Quarkus extension work: any optional dep flagged in `*-deployment/pom.xml` must be cross-checked against the @BuildStep method signatures in that deployment's processor classes. If any signature references the optional type, the dep must NOT be optional — Capability gating is the right primitive for runtime, but build-time classpath visibility is what `<optional>` controls and breaks. |

---

## 2026-05-26 — "Coordinator could benefit from an event-sourced execution log" proposal made before grepping `modules/coordinator/journal/` (`feat/journal-event-sourcing`)

Pitching transferable ideas from arXiv 2605.21997 ("The Log is the Agent")
to the project maintainer. The four-point proposal opened with: "Coordinator
could benefit from an event-sourced execution log. `modules/coordinator`
already does Coordinator/Dispatcher with `AgentFleet`/`AgentProxy`/
`AgentCall`/`AgentResult`, but those are call-return values, not a
persisted causal log." Framed as "we'd build this from scratch."

| # | Claim | Truth | Slip path | Gate added |
|---|---|---|---|---|
| 70 | Proposal #1 implied no event-sourced journal existed in `modules/coordinator` — "those are call-return values, not a persisted causal log" — and was teed up as net-new build work to extract from the paper. | `modules/coordinator/src/main/java/org/atmosphere/coordinator/journal/` already shipped: `CoordinationJournal` SPI (`start`/`stop`/`record`/`retrieve`/`query`/`inspector` + `NOOP`), sealed `CoordinationEvent` hierarchy with **11 variants** including `CoordinationStarted`/`AgentDispatched`/`AgentCompleted`/`AgentFailed`/`AgentEvaluated`/`AgentHandoff`/`RouteEvaluated`/`CoordinationCompleted`/`AgentActivityChanged`/`CircuitStateChanged`/`CommitmentRecorded`, `InMemoryCoordinationJournal` with bounded eviction + inspector filtering, transparent `JournalingAgentFleet` decorator threading events across `parallel()` / `pipeline()` / `route()` / `proxy.call()` / `callAsync` / `stream`, signed cryptographic `CommitmentRecord` provenance, plus a `CheckpointingCoordinationJournal` decorator in `modules/checkpoint/` that turns the same event stream into durable workflow snapshots. The decorator is wired by `CoordinatorProcessor` and consumed by `modules/admin/` for the Console UI — 94 `new CoordinationEvent.*` call sites across coordinator/admin/checkpoint/integration-tests/samples already depend on the journal. | Synthesized the proposal from the paper's vocabulary ("event log as source of truth") and the high-level mental model of the coordinator from prior memory, without grepping the target module first. A 4-second `ls modules/coordinator/src/main/java/org/atmosphere/coordinator/journal/` would have surfaced `CoordinationJournal.java` immediately and reframed the proposal as "what's missing vs the paper" instead of "what to build from scratch." Same shape as drift #65 / #66 / #68 (claim API/surface state from memory not from grep); also similar to the "Sidecar matrix already exists" misread in drift session 2026-05-25 — both confused the existence of substrate with its completeness. | (1) **Self-caught before any code was written**: investigation between proposal and implementation surfaced the existing journal package; the proposal was retracted transparently in the same conversation turn ("My proposal #1 was already done — and well. What's *actually* missing vs. the paper is..."); scope was re-framed to four legitimately-missing pieces (causal lineage via `EventEnvelope`, `CoordinationProjection`, `FileCoordinationJournal`, `CoordinationFork`) which shipped additively across 5 commits ending at `282d88d2c6`. None of the existing 94 call sites or 11 event variants were broken — the new envelope API was layered as default methods on `CoordinationJournal` so legacy `record(event)` / `retrieve(coordId)` consumers continued working. (2) **No automated gate is feasible** — "did you grep the target module before proposing infrastructure additions" is a planning-discipline check, not a code-pattern. The relevant memory entries (`feedback_verify_runtime_not_declaration.md`, `feedback_primitive_needs_consumer.md`, `feedback_review_disciplines.md`) already cover the verify-before-claim pattern; this entry records the specific failure mode of *proposing-net-new before grep* alongside *claiming-shipped before grep*. The lesson encoded: before pitching any "we should build X" for a substantial framework surface (journal, registry, SPI, transport, fleet), the first action is `ls $(target_module)/src/main/java/.../$(area)/` — proposal premises that survive a 4-second directory listing are honest; ones that don't get rewritten on the spot. |

1. Catch the drift (the project maintainer flags it, or self-caught via `git grep` /
   `find` after spotting memory ↔ code disagreement).
2. **Verify against current code first** — re-read the source, do not trust
   memory files older than the most recent CHANGELOG bump.
3. State the correction transparently in the conversation — quote the false
   claim verbatim, quote the ground truth verbatim.
4. Append an entry to this log in the current dated section. If today
   doesn't have a section yet, start one with `## YYYY-MM-DD — <session>`.
5. Add a regression-class gate where feasible (validator, JUnit test,
   memory update, prose-grep). `none` is a legitimate gate value when no
   automated check makes sense.
6. Bundle log update + gate + prose fix in **a single commit** — that commit
   is the review unit.

The point of this log is not blame; it is **measurement**. Whether AI
accelerates or regresses a codebase depends on whether the feedback loop
on claim quality is instrumented. This log is Atmosphere's loop.
