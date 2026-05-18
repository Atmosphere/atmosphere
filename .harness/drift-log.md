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
| 9 | "Did not push / Did not X / Did not Y" enumeration in reports as defensive shield masquerading as transparency | ChefFamille had to override with "push we are wasting time"; the `feedback_no_pr_direct_merge.md` "ask first" clause was the upstream cause | `feedback_no_did_not_listing.md` added; `feedback_no_pr_direct_merge.md` rewritten to remove the "ask first" friction when work is under explicit Go instruction |
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
| 16 | `.harness/README.md` opened with "Atmosphere's instance of Reock's DX impact-metric framework (InfoQ, *AI-Assisted Engineering*, 2026-05)" and listed "Justin Reock, *AI-Assisted Engineering* — InfoQ, 2026-05" under *Further reading*; the same attribution propagated into `.harness/drift-log.md` preamble + footer and `CHANGELOG.md` *[Unreleased]* (line 13: "Atmosphere's instance of Reock's DX impact-metric framework"). Also asserted "+20% / −20% from AI" as a Reock-attributed productivity stat | ChefFamille flagged the attribution as wrong. The "InfoQ *AI-Assisted Engineering* 2026-05" citation was unverified and treated as authoritative; the "+20% / −20%" stat was an unanchored figure. Both violate `feedback_no_fabricated_stats.md` and the "No Hallucinations" rule in CLAUDE.md | Composed the harness README prose from a plausible-sounding AI-engineering narrative without verifying the citation against InfoQ; the framing then propagated into `drift-log.md` preamble + footer (same session, copy-paste shape) and into the `CHANGELOG.md` [Unreleased] entry announcing the harness. Classic "metastasized across multiple files" pattern called out under CLAUDE.md *No Hallucinations* → *When you catch yourself hallucinating* | Reock attribution and the InfoQ citation removed from README + drift-log preamble + drift-log footer + CHANGELOG [Unreleased]; "+20%/−20%" stat removed (no verifiable source). **No automated gate** — fabricated citations are not structurally detectable by grep or schema check; only human review catches them. Discipline going forward: every external citation in `.harness/*` prose must point to a URL/repo that returns 200, not a paper title quoted from memory. **See #17 for the sub-drift on the replacement reference.** |
| 17 | First repair of #16 re-anchored the README, drift-log preamble + footer, and CHANGELOG on [`walkinglabs/learn-harness-engineering`](https://github.com/walkinglabs/learn-harness-engineering) and described it as "the five-subsystem harness-engineering framework (Instructions, State, Verification, Scope, Lifecycle)" — citing it as the canonical framework reference | ChefFamille flagged it: "The URL is a tutorial no?" Verified via `gh api repos/walkinglabs/learn-harness-engineering`: the repo's own description is literally **"Harness engineering official style beginner tutorial, from 0 to 1"**, and its README lists its upstream sources as Anthropic's [*Effective harnesses for long-running agents*](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents) (Justin Young, 2025-11-26), Anthropic's [*Harness design for long-running application development*](https://www.anthropic.com/engineering/harness-design-long-running-apps), and OpenAI's [*Harness engineering: leveraging Codex in an agent-first world*](https://openai.com/index/harness-engineering/). The five-subsystem decomposition is the tutorial's *teaching synthesis* of those primary sources, not a canonical framework spec. Citing a tutorial as the framework reference is the same drift class as #16: trusting a plausible-shaped reference without verifying its provenance | Pulled the existing tutorial link out of the README's *Further reading* (already there from an earlier session) and promoted it to the primary anchor without inspecting what the linked repo actually was. The repo name `learn-harness-engineering` contains "learn-" — a tell I should have read; the repo's own description and its README both flag the tutorial framing explicitly. Compound failure: fixing #16 by reaching for the nearest-already-cited reference instead of going to the actual upstream sources | README + drift-log preamble + CHANGELOG [Unreleased] re-anchored to the primary sources (Anthropic Nov-2025 post + companion + OpenAI harness engineering article). Tutorial moved back into *Further reading* with an honest label ("beginner tutorial / course … synthesises the above sources … useful as onboarding read; canonical references are the Anthropic and OpenAI posts above"). **No automated gate** for the same reason as #16: citation-as-tutorial-vs-spec is a provenance judgement, not a grep target. Discipline going forward: when re-anchoring a hallucinated reference, verify the *replacement* — repo description, primary-source URL, publication date — before promoting it, instead of trusting prior in-tree usage |
| 18 | When asked to verify the top-level `README.md` reflects the implementation 100 %, I initially reported "everything checks out." Re-grep against `pom.xml` then surfaced four narrative copies still saying **"Spring AI 2.0.0-M2"**: `README.md:135` (adapter table row), `modules/spring-ai/README.md:85` (requirements section, "Spring AI 2.0.0-M2+"), `modules/ai/README.md:62` ("Forcing Spring AI 2.0.0-M2 across the classpath today fails…"), and `modules/ai/README.md:278` ("Spring AI 2.0.0-M2 exposes `OpenAiChatModel.Builder.toolExecutionEligibilityPredicate(...)`") | `pom.xml:1199` pins `<spring-ai.version>2.0.0-M6</spring-ai.version>`; the bump landed earlier today in commit `7dccf49cf4`. The bumper updated the pom property and the auto-config Java code, but the four narrative-prose mentions across three READMEs were not refreshed. Identical drift class to #12 (Quarkus 3.31.3 → 3.35.2 prose drift, same week) | The Spring AI M6 bump session focused on auto-config wiring (drifts #13–#15 trace the bean-construction iterations); the user-facing narrative README rows were never inspected. My initial "verified 100 %" claim in this session also skipped a `git grep '2\.0\.0-M2'` sweep across all READMEs — same shortcut that produced drift #12. Two recurrences of the same pattern (narrative-prose lagging a `pom.xml` version property) within one week | `docs(readme): refresh Spring AI version references to 2.0.0-M6` (this commit) — all four narrative mentions updated; only `.harness/drift-log.md` historical record retains the "2.0.0-M2" string, which is correct (drift-log is append-only history). **Gate**: `none`, matching the precedent set by #12. Recurrence in one week is a signal that a structural check may now be worth the investment; tracked as proposed follow-up `scripts/check-readme-pom-version-alignment.sh` (grep `pom.xml` `*.version` properties, fail when an older version string of the same artifact appears in any `*.md`). Not added in this commit to keep the bundle scoped to the prose fix |
| 19 | `atmosphere.github.io/website/src/components/Atmosphere.astro:22` listed the A2A protocol URL as `https://google.github.io/A2A/` with label "Google A2A Spec" — same URL referenced from the home-page pillar modal | `curl -sI https://google.github.io/A2A/` returns **HTTP 404**. The A2A spec moved out of Google ownership: `https://a2aproject.github.io/A2A/` returns 301 → `https://a2a-protocol.org/` (200), under Linux Foundation stewardship | Site copy authored when the spec lived under Google's GitHub org; never refreshed when the project graduated to the Linux Foundation. The post-#12 grep-after-bump discipline only covered version properties in `pom.xml`, not external URLs referenced from sibling repos. Same drift class as #12 (narrative trailing upstream change) but failure mode is "external link rot," not "pom property mismatch" | `docs(website): fix 4 drifts caught by cross-check against atmosphere repo` (commit `5404f5f` on `atmosphere.github.io`) — URL bumped to `https://a2a-protocol.org/`, label trimmed to "A2A Spec", caption notes Linux Foundation stewardship. **No automated gate** in this commit. Proposed follow-up: `scripts/check-website-urls.sh` that `curl -sI`s every external URL referenced from `website/src/components/*.astro` and fails the build on any non-2xx response. Tractable because URLs are extractable with a regex; left out of this bundle to keep scope contained |
| 20 | `atmosphere.github.io/website/src/components/CodeExample.astro:76` capability section read "Spring Boot 4.0 and Quarkus **3.21+** auto-configuration included" | `pom.xml:1196` pins `<quarkus.version>3.35.2</quarkus.version>`; top-level `README.md` says "Quarkus 3.35.2+" after the drift-#12 fix. The "3.21+" was accurate as the original minimum target when the extension was authored against Quarkus 3.21, and never refreshed when the version property was bumped to 3.35.2 | Same class as #12 (narrative trailing `pom.xml` bump) but the prose lives in the sibling repo `atmosphere.github.io`. The post-#12 surface scan ran `git grep "3.31.3"` in the main `atmosphere` repo only — `atmosphere.github.io` was outside the loop. Two-repo discipline gap, not a content gap | Prose fix in commit `5404f5f` on `atmosphere.github.io` — bumped to "Quarkus 3.35.2+". **No automated gate** in this commit. Proposed follow-up: extend `scripts/pre-push-validate.sh` (main repo) to warn when `pom.xml` version properties change without a paired edit in `~/workspace/atmosphere/atmosphere.github.io`. Path-aware reminder, not a hard gate; sibling-repo coupling is loose by design |
| 21 | `atmosphere.github.io/website/src/components/Atmosphere.astro:10` WebTransport pillar said "Atmosphere is the **first Java framework with native WebTransport support**" | Unverified superlative. No on-disk source substantiates "first"; I did not survey Spring / Quarkus / Helidon / Vert.x for WebTransport support before letting the phrasing ship to the public website. The claim may be substantially true (the WebTransport handler/processor/session in `modules/cpr/src/main/java/org/atmosphere/webtransport/*.java` is a real native implementation; I have not seen another Java framework bundle one), but `feedback_readme_honesty.md` says top-of-repo docs cannot oversell without verification | Site copy authored without a verification step against competing frameworks. Same posture as #16 — plausible-sounding narrative shipped without anchoring to a source. Different from #16 in that the claim might actually be true; the failure is shipping the assertion without the evidence | Softened in commit `5404f5f` on `atmosphere.github.io` — phrasing now reads "native WebTransport support … auto-detected via AsyncSupport with zero-config Jetty 12 QUIC or Reactor Netty sidecar" (drop "first Java framework with"). **No automated gate**: superlatives are categorically unverifiable from grep; the only honest discipline is "if you cannot cite a survey, do not say 'first'." Same unautomatable check class as #16, #17 |
| 22 | `atmosphere.github.io/website/src/components/WhyAtmosphere.astro` said "Enterprise support since **2014**" in three places: the `atmosphereOnlyFeatures` value-prop list, the `lockInStory` "with" column (line 52), and the `lockin-lead` paragraph (line 279) | ChefFamille confirmed the correct year is **2013**. The 2014 figure was unverified business fact. The main `atmosphere` repo's `About.astro` correctly says open source since 2008 (project birth, 18 years in production at 2026); Async-IO LLC's commercial-support start year is a separate business date that the repo does not pin | Business fact about Async-IO commercial-support start was never confirmed against the company's actual incorporation / first-paid-contract date before being committed to public site copy. No on-disk source can verify it — only ChefFamille can | Prose fix to "2013" in 3 places, commit `5404f5f` on `atmosphere.github.io`. **No automated gate** is feasible: there is no repo-side source of truth for Async-IO's commercial-support start date. Proposed follow-up: add a small `.harness/async-io-timeline.json` ({ commercial_support_since: 2013, project_open_source_since: 2008, ... }) so future website edits can cite a single pinned source instead of free-prose-ing the years. Until then, business dates on the website must be cleared with ChefFamille before shipping |

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
| 27 | After the `feat(samples): unified ConnectionStatusBadge for gRPC, A2A, and AG-UI transports` push I claimed "every sample with a frontend renders the unified Badge" | Three frontends — `grpc-chat`, `spring-boot-a2a-agent`, `spring-boot-agui-chat` — were not wired. They use atmosphere.js for *visual chat primitives only* (ChatLayout / MessageList / ChatInput); the wire is Connect-RPC / JSON-RPC SSE / AG-UI SSE respectively. The first claim was correct narrowly (atmosphere.js *transport* samples were covered) but `feedback_readme_honesty.md` says claims must be unambiguous without footnotes | Stopped at "every sample using atmosphere.js as transport" without rechecking whether the broader "every sample with a frontend" claim was true. The verification pass that surfaced the gap was triggered by ChefFamille's "Yes" to the proactive offer — the catch only worked because I asked the right question, not because I verified first | Closed by extending the Badge to accept arbitrary protocol names via `ConnectionTransportName = TransportType \| (string & {})` (widening commit, atmosphere.js types) and wiring each non-atmosphere sample to build its own `ConnectionStatusSnapshot`. **Gate**: vitest case `accepts non-atmosphere transport names via ConnectionTransportName` pins the type-level contract |

### Process win

Two of the drifts above (#20, #23) were caught because the verification
pass *preceded* the action that would have shipped the wrong claim:
- #20: ChefFamille's "double check" instruction triggered `git merge-base
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
CVE fix stays — but the cost was a red main pipeline that ChefFamille
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
| 4. Enterprise console story | Closed | Workflow authoring lands inside the admin control plane (not as a separate DSL — architectural call by ChefFamille mid-session) + eval dashboard + `atmosphere-admin-bundle` single-dep aggregator | **`81ff454177`**, **`38e2a45920`**, **`eaad0df089`** |
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

## How to append a new entry

1. Catch the drift (ChefFamille flags it, or self-caught via `git grep` /
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
