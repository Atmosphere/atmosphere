---
title: Sample Sweep — chrome-devtools E2E (<YYYY-MM-DD>)
date: <YYYY-MM-DD>
type: report
tags: [samples, e2e, chrome-devtools, sweep, release-gate]
version: <x.y.z-SNAPSHOT>
---

# Sample Sweep — chrome-devtools E2E (<YYYY-MM-DD>)

Copy this to `claude_docs/sample-sweep-<YYYY-MM-DD>.md` before Phase 1 and fill
each row **as that sample finishes**. Never batch the rows at the end — a
compacted context or a crashed session loses everything not yet written.

## Run header

| | |
|---|---|
| Version under test | |
| Commit (`git rev-parse --short HEAD`) | |
| Branch | |
| Sample count (`ls samples/` minus `shared-resources`) | |
| LLM backend | Ollama `qwen2.5:3b` / `qwen2.5:7b-instruct-q4_K_M`, `LLM_MODE=local` |
| Port block | 9101+ (18810 for quarkus-ai-chat) |
| Reactor build | `./mvnw install -DskipTests -Pfastinstall` → BUILD SUCCESS at |
| Console bundle | `scripts/sync-console-bundle.sh --check` → |
| Environment notes | e.g. a port remapped because another process held it |

## Phase 1a — Sample matrix

Verdicts: **PASS** (headline feature rendered, no unexplained errors) ·
**PARTIAL** (plumbing proven, feature not observed — reason required) ·
**FAIL** (feature broken / exception / won't boot).

| # | Sample | Port | Transport | Verdict | Evidence (what was observed) | Console errors | Server WARN/ERROR |
|---|--------|------|-----------|---------|------------------------------|----------------|-------------------|
| 1 | | | | | | | |
| 2 | | | | | | | |

Fill the warning columns even for PASS rows — the warning inventory is half the
value of the sweep.

## Phase 1b — Expo client (`spring-boot-ai-classroom/expo-client`)

Backend port used: ____ · `SERVER_URL` pointed at: ____ · reverted after: yes/no

| # | Assertion | Verdict | Evidence |
|---|---|---|---|
| 1 | Connects to the backend over WebSocket | | |
| 2 | Four rooms render and are selectable | | |
| 3 | AI response streams text-by-text (two growing reads) | | |
| 4 | AppState background → reconnect | | |
| 5 | NetInfo offline banner + sends suppressed | | |

`atmosphere.js` rebuilt before this pass: yes/no · `git status` clean after: yes/no

## Phase 1c — CLI

`ATMOSPHERE_HOME` used (throwaway): ____ · CLI tested: in-repo / installed

| Check | Result | Evidence |
|---|---|---|
| Registry count matches `ls samples/` | | |
| `list` / `list --tag` / `info` | | |
| `run` spring-boot → browser-driven | | |
| `run` quarkus → browser-driven | | |
| `run` executable-jar → browser-driven | | |
| Cold-cache build, then cached-and-verified | | |
| `runnable=false` refused with guidance | | |
| `new` per template: scaffold + compile from Central | | |
| `compose` / `import` / `checkpoint` | | |
| `install.sh` / npx | | |
| Post-publish: tap / npm / install.sh | | |

Templates covered: ____ of ____ in the `cmd_new` map. Not covered: ____ (reason)

## Phase 2 — Triage

| # | Sample | Symptom | Class (framework / sample / config / model / environment) | Blast radius | Priority |
|---|--------|---------|------------------------------------------------------------|--------------|----------|
| | | | | | |

Warnings seen on many samples (candidate framework findings hiding as noise):

- 

## Phase 3 — Fixes

For each issue:

### Issue N — <one-line symptom>

- **Sample / surface:**
- **Root cause:** (the mechanism, not the patch)
- **Fix:** files changed + commit hash
- **Playwright regression spec:** path, project name, workflow leg,
  `release-gate-samples.sh` coverage entry
- **Proven to bite:** failed pre-fix with `<symptom>` / passed post-fix
- **Build-time gate added (if the issue is a class):**

## Phase 4 — Re-test

- **Fix touched:** <module / sample / shared surface>
- **Blast-radius family:** <from references/retest-subset.md>

| Sample | Reason in subset | Re-driven verdict |
|--------|------------------|-------------------|
| | | |

**Deliberately not re-driven:** <samples> — reason: <why the fix cannot reach
them>. (Never leave this blank. An empty skip list must say "none".)

## Result

- **N / N samples** — X PASS, Y PARTIAL, Z FAIL→fixed
- **Expo client** — PASS / PARTIAL / FAIL (which of the 5 assertions)
- **CLI** — PASS / PARTIAL / FAIL (which checks; templates covered)
- **Issues found and fixed:** with commit hashes
- **CI:** all workflows green on <commit>

## Methodology notes / honest caveats

- What was gated on a missing key, Docker, or a collector — and what that means
- Any model-limitation-vs-framework-bug call, and how it was proven
- Anything not verified this run

## Follow-ups (non-blocking)

- 
