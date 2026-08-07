---
name: release-sample-sweep
description: Run the pre-release end-to-end sweep of every user-facing surface — the 31 samples under samples/ (booted from their packaged artifacts and driven in a real browser via chrome-devtools MCP), the Expo/React Native client, and the atmosphere CLI. Use before cutting a release, and after any change to the Console bundle, a shared module, atmosphere.js, the CLI, or several samples at once. Covers preconditions, the keyless Ollama backend, the per-sample launch/drive/collect/teardown loop, the evidence ledger, the fix phase (every issue gets a biting regression test in the right suite), the re-test subset, and the report.
---

# Release sample sweep (chrome-devtools)

Before a release, every user-facing surface is exercised the way a user
exercises it. This skill is that procedure written down.

Three surfaces, three drivers — all three are release gates:

| Surface | What | Driver |
|---|---|---|
| **Samples** (31) | `samples/*`, booted from packaged artifacts | chrome-devtools MCP, or the wire protocol for the headless ones |
| **Expo client** (1) | `samples/spring-boot-ai-classroom/expo-client/` | iOS simulator MCP — it is a native app, chrome-devtools cannot reach it |
| **CLI** | `atmosphere run` / `new` / `compose` / `import` / `checkpoint` + its four distributions | Shell, then chrome-devtools against what `atmosphere run` booted |

## When to run it

- **Before cutting any release.** Non-negotiable — it is the last gate before
  `release-4x.yml`.
- After a change to the **Console bundle** (`modules/spring-boot-starter/frontend/`),
  since the Console is both the shipped sample UI and the validation surface.
- After a change to a **shared module** that every sample transitively depends on
  (`modules/cpr`, `modules/ai`, `modules/spring-boot-starter`, `modules/admin`).
- After a **dependency bump wave** — two of the last three sweeps found a
  version-skew bug that compiled clean and only failed at runtime.

## What it catches that CI does not

CI builds and tests modules; this sweep exercises **packaged artifacts in a
browser**. The gap between those is where the real bugs live:

| Sweep | Bug found | Why CI was green |
|---|---|---|
| 2026-06-30 | `quarkus-ai-chat` would not start — OTel api/common version skew from a Dependabot bump | Module tests never boot the sample's fast-jar |
| 2026-07-17 | `spring-boot-orchestration-demo` crashed on every tool turn — the sample pom hardcoded `langchain4j-open-ai:1.15.0` while the reactor manages 1.17.0 | The module built against 1.17.0; only the sample's own jar bundled 1.15.0 |

`scripts/release-gate-samples.sh` automates the boot-and-assert half of this in
CI. This sweep is the **browser half** on top of it — the layer that sees
rendering, streaming, transport headers, tool cards, and console errors.

## The shape of the sweep

```
Phase 0   Preconditions  — build everything, start Ollama, free the ports, open the ledger
Phase 1a  Samples        — 31 samples: launch → drive → collect → verdict → teardown
Phase 1b  Expo client    — the RN client in the iOS simulator
Phase 1c  CLI            — atmosphere run/new/compose/import/checkpoint + distributions
                           ALL OF PHASE 1 IS COLLECT-ONLY. Do not fix anything mid-sweep.
Phase 2   Triage         — classify every finding, rank by blast radius
Phase 3   Fix            — root-cause fix + a regression test per issue, in the right
                           suite, each proven to bite
Phase 4   Re-test        — the failed surfaces in full, plus the blast-radius subset
                           of already-passing ones
Phase 5   Report         — vault report, CI green, memory updated
```

Phase 1 is deliberately fix-free. Fixing mid-sweep changes the artifact under
test and invalidates every sample already verified against the old one. The one
exception: a defect that **blocks the sweep itself** from continuing — fix it,
say so in the ledger, and note which already-passed samples were re-run.

## Non-negotiables

1. **chrome-devtools, never curl, for validation.** `curl` is allowed only for
   port readiness and for headless wire protocols (A2A/MCP/REST) that serve no
   HTML. A "works via curl" claim skips the whole JS layer and is a false pass.
2. **The Atmosphere Console is the UI.** Drive `/atmosphere/console/` (Spring
   Boot samples redirect `/` there). A sample that needs a bespoke page instead
   of the Console is itself a finding.
3. **Assert the rendered element, not the payload.** An `image` node with a
   `src` is a rendered screenshot; the same base64 in a `StaticText` node means
   nothing rendered it. "Server started", "HTTP 200", and "bytes present in the
   DOM" are not passes.
4. **Boot the packaged artifact.** `java -jar` (or `quarkus-run.jar`), never
   `spring-boot:run` / `quarkus:dev`. Both historical bugs above existed *only*
   at artifact level.
5. **Kill by PID, never `pkill -f`.** Never touch a port or process the sweep
   did not start — if a port is occupied, move to another port.
6. **Model limitation ≠ framework bug.** A small local model emitting invalid
   tool-call arguments is a model limitation; record it as such and prove it by
   re-running the same flow on a capable model before calling it a regression.
7. **Never write "flaky".** Reproduce it, or explain the mechanism. If neither
   is possible yet, it is a FAIL with an open question, not a dismissal.
8. **Report honestly.** PASS / PARTIAL / FAIL with one line of concrete
   evidence each. PARTIAL must name what was not proven and why.

## Phase 0 — Preconditions

```bash
git status --porcelain                    # must be clean
git rev-parse --short HEAD                # record this SHA in the ledger
grep -m1 '<version>' pom.xml              # record the version under test

./mvnw install -DskipTests -Pfastinstall  # full reactor: framework + every sample jar
./scripts/sync-console-bundle.sh --check  # the Console you will drive must be current

ollama list                               # qwen2.5:3b + qwen2.5:7b-instruct-q4_K_M
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:11434/v1/models
```

- **LLM backend is local Ollama, keyless.** Use `qwen2.5:3b` for streaming
  samples and `qwen2.5:7b-instruct-q4_K_M` for tool-heavy agents — 3b emits
  invalid tool-call arguments and Ollama answers 400. Note `real-ollama` is a
  CI-harness alias only; `AiConfig` matches the literal `local`.
- **Always read the resolved endpoint out of the boot log before driving:**

  ```
  grep 'AI config:' target/sweep/<sample>.log
  ```

  `AiConfig.resolveBaseUrl` maps `local` → `AiConfig.OLLAMA_ENDPOINT`, but on
  4.0.64-SNAPSHOT the Spring Boot path was observed logging
  `mode=local … endpoint=https://generativelanguage.googleapis.com/…` and the
  turn failed against Gemini. Until that is fixed, pass the endpoint explicitly:

  ```
  --env LLM_MODE=local --env LLM_BASE_URL=http://localhost:11434/v1 \
  --env LLM_API_KEY=ollama --env LLM_MODEL=qwen2.5:3b
  ```

  Never assume the mode took effect — an endpoint that disagrees with the mode
  is itself a finding (Invariant #5, runtime truth).
- **Do not use a paid key.** The paid-LLM lane is retired; quota starvation is
  what made the 2026-06 sweep report nine samples as plumbing-only.
- **Do not use embacle** (`embacle-server --provider claude_code`) for
  tool-calling samples — it applies the host CLI's own configuration to
  responses and its tool-call fidelity is inconsistent. It is only useful to
  demonstrate "a capable model completes this flow cleanly", then stop it.
- **Ports:** the sweep runs on the 9101+ block so it never collides with the
  samples' own defaults or the Playwright fixture's 8080–8104. Assignments are
  in `references/sample-matrix.md`.
- **Open the ledger** at `claude_docs/sample-sweep-<YYYY-MM-DD>.md` (a gitignored
  symlink into the vault, so it survives context compaction). Template:
  `assets/ledger-template.md`. Write each row **as you finish that sample**,
  never in a batch at the end.

## Phase 1a — The per-sample loop

Work through `references/sample-matrix.md` in order. For each sample:

```bash
# 1. Launch (the helper refuses to boot if the port is already answering)
.claude/skills/release-sample-sweep/scripts/sweep-sample.sh start <sample> \
    --port <9101+n> --ready-path <path> --env LLM_MODE=local --env LLM_MODEL=qwen2.5:3b
```

2. **Fresh browser page per sample** — `new_page` on the drive URL. Never reuse
   the previous sample's page: stale state and leftover console noise both
   corrupt the evidence.
3. **Snapshot** — `take_snapshot`. Confirm the Console mounted and the transport
   badge reads what the matrix expects (`Connected · websocket` /
   `· webtransport` / `· grpc` / `· ag-ui`). A transport that silently fell back
   is a finding.
4. **Drive the headline flow** for that sample — the exact interaction is in the
   matrix, the mechanics per surface class are in `references/driving-recipes.md`.
5. **Wait for the rendered result** — `wait_for` the expected text/element, then
   re-`take_snapshot` and confirm the node type (see non-negotiable #3).
6. **Collect the evidence, all three sources:**
   - `list_console_messages` — every error and warning, verbatim
   - `list_network_requests` — any non-2xx/failed request
   - `sweep-sample.sh warnings <sample>` — server-side WARN/ERROR/exception/SLF4J
   Record warnings even when the sample passes. The warning inventory is half
   the value of the sweep and is what the next release's triage starts from.
7. **Verdict + one-line evidence** into the ledger:
   - **PASS** — headline feature observed rendered, no unexplained console error,
     no server exception.
   - **PARTIAL** — plumbing proven, headline feature not observed, *reason named*
     (missing third-party key, Docker unavailable, model limitation).
   - **FAIL** — feature broken, error frame, exception, or the sample won't boot.
8. **Teardown** — `close_page`, then
   `sweep-sample.sh stop <sample>`. The helper verifies the port is actually
   released; if it is not, stop and investigate before the next sample claims it.

## Phase 1b — The Expo client

`samples/spring-boot-ai-classroom/expo-client/` is a native Expo/RN app. It is
**not** a Maven module, **not** in `cli/samples.json`, and unreachable by the
Playwright suites — this sweep is its only gate. It links `atmosphere.js` by
file path, so it is also the only pre-release check that the client library's
`./react-native` export works in a real RN runtime.

Driven with the **iOS simulator MCP**, not chrome-devtools. Full procedure,
including the `SERVER_URL` port trap and the AppState/NetInfo assertions nothing
else covers: `references/expo-sweep.md`.

## Phase 1c — The CLI

The CLI is the documented Quick Start and ships as four distributions
(curl installer, npx, Homebrew tap, SDKMAN). CI covers `list`/`info`, argument
validation, the runtime overlays, and the installers — it never boots a sample
through `atmosphere run` and looks at the UI, and it has no coverage for
`compose` or `checkpoint`.

The manual pass closes that: `atmosphere run` → browser-driven, `atmosphere new`
→ scaffold + compile against Maven Central, plus the thin-coverage commands and
a post-publish check of the actually-shipped artifacts. Watch the jar cache —
a stale `$ATMOSPHERE_HOME/cache/v<version>` boots the previous release and fakes
a pass. Full procedure: `references/cli-sweep.md`.

## Phase 2 — Triage

With all samples tested, classify each finding before touching any code:

| Class | Meaning | Action |
|---|---|---|
| Framework bug | A module under `modules/` is wrong | Fix + regression spec. Release-blocking. |
| Sample bug | Only that sample's code/pom/config is wrong | Fix + regression spec. Release-blocking if the sample ships. |
| Config/env | Sample needs a key, Docker, a collector | Not a bug — document the graceful-degradation behaviour and assert *that* |
| Model limitation | Small local model can't drive the flow | Prove with a capable model, record, no code change |
| Sweep environment | Port conflict, stale `~/.m2`, half-built reactor | Fix the environment and re-run that sample |

Rank by blast radius: shared-module findings first (they can invalidate other
samples' passes), then per-sample.

## Phase 3 — Fix, with a regression test per issue

Every issue gets a test, but **in the suite that can actually run it**:

| Surface the issue is on | Regression home |
|---|---|
| Sample / Console / framework | Playwright spec → `references/regression-specs.md` |
| CLI | A case in `cli/test-cli.sh` (a Playwright spec is the wrong vehicle for a shell CLI) |
| Expo / React Native | An `atmosphere.js` vitest covering the `./react-native` export path; if the defect is genuinely RN-runtime-only, name it in the report as manual-sweep-only rather than faking a gate |

For **every** issue in the framework-bug or sample-bug class:

1. **Root-cause it first.** Read the failing path; do not pattern-match a fix.
2. **Smallest change that fixes the cause** — the 2026-07 langchain4j fix was a
   single pom property.
3. **Write a Playwright e2e spec that reproduces the failure**, in the right
   home, wired into the right CI lane, and **proven to bite**: it must fail
   against the pre-fix artifact and pass after. Recording only "it passes now"
   proves nothing. Full authoring + wiring recipe:
   `references/regression-specs.md`.
4. **Where a build-time lint can close the whole class, add that too** — the
   langchain4j fix shipped both a spec and `SampleLangChain4jVersionLintTest`,
   which fails the build if any sample pom hardcodes a LangChain4j version.
5. **Rebuild** the affected modules and samples before re-testing.
6. One commit per fix class, conventional-commit prefixed. No CHANGELOG edits —
   the CHANGELOG is touched only at release time.

## Phase 4 — Re-test

1. **Re-run the failed sample end to end** — the full headline flow, not just
   the broken step.
2. **Re-run the blast-radius subset** of already-passing samples. The fix
   changed the artifact those passes were recorded against, so their evidence is
   only still valid if the fix could not reach them. The mapping from
   "what the fix touched" to "which passing samples must be re-driven" is in
   `references/retest-subset.md`.
3. **Say what you did not re-run and why.** A subset is a deliberate scope
   decision; leaving it unstated reads as "everything was re-verified".
4. Repeat Phases 2–4 until the sweep is clean.

## Phase 5 — Report

- **Vault report** via the `obsidian-writer` skill →
  `Claude Outputs/Sample-Sweep-chrome-devtools-<date>.md`. Promote the ledger:
  full matrix with the evidence column, the issues-found-and-fixed section with
  commit hashes, methodology caveats, and non-blocking follow-ups.
- **Every number verified** — sample count from `ls samples/` (minus
  `shared-resources`) and `cli/samples.json`, never from memory.
- **CI green** on the fix commits before the release proceeds — all workflows,
  not just the one you were watching.
- **Update memory** with anything reusable: a new trap, a new drive recipe, a
  changed port, a sample added or removed.

## Files in this skill

| File | Use |
|---|---|
| `references/sample-matrix.md` | Every sample: boot type, sweep port, drive surface, headline assertion, gating |
| `references/driving-recipes.md` | chrome-devtools call sequences per surface class + browser-layer traps |
| `references/expo-sweep.md` | Phase 1b — the RN client in the iOS simulator |
| `references/cli-sweep.md` | Phase 1c — what CI already covers, the real gaps, and the CLI pass |
| `references/regression-specs.md` | Where a Playwright spec lives, how to wire it into CI, how to prove it bites |
| `references/retest-subset.md` | Blast radius → which passing samples to re-drive after a fix |
| `references/troubleshooting.md` | Known traps: PNA, long-poll probes, stale jars, port collisions, Quarkus LLM config |
| `assets/ledger-template.md` | The sweep ledger to copy into `claude_docs/` |
| `scripts/sweep-sample.sh` | Boot one sample from its packaged artifact on a sweep port and leave it running |
