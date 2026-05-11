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
