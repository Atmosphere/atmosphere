# `.harness/` — AI-Assisted Engineering Instrumentation

Atmosphere's harness — the engineering scaffold around the AI agents that
contribute to this repo. The framing comes from the harness-engineering
pattern documented by Anthropic ([*Effective harnesses for long-running
agents*](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents),
[*Harness design for long-running application development*](https://www.anthropic.com/engineering/harness-design-long-running-apps))
and OpenAI ([*Harness engineering: leveraging Codex in an agent-first
world*](https://openai.com/index/harness-engineering/)): the model is the
engine, the harness is the rails — instructions, state, verification,
scope, and session lifecycle around the model. This directory is the
**verification** rail for AI prose claims about this repo. The point is
**not** utilization metrics — that's Goodhart's-Law-bait. The point is to
keep prose claims honest against running code, and to record every agent
claim that diverged from ground truth so the *rate* of divergence over
time is a diff-reviewable curve.

---

## Files

| Path | Purpose | Source of truth | Append-only? |
|------|---------|-----------------|---------------|
| `capabilities.snapshot.json` | Canonical aggregate of `AiCapability` enum (currently 20 entries) and each runtime's pinned `expectedCapabilities()` (currently 12 contract-tested runtimes — `DemoAgentRuntime` is the no-key fallback and is excluded from the snapshot by design). Used to validate aggregate count claims in `modules/ai/README.md` and to drive per-runtime `modules/<X>/SKILLCARD.yaml` regeneration. | `modules/ai/src/main/java/org/atmosphere/ai/AiCapability.java` + every `*RuntimeContractTest.{java,kt}` | No — regenerated whenever a capability or runtime is added/removed. |
| `drift-log.md` | Structured record of every agent claim that diverged from ground truth (claim, truth, slip path, gate added). The diff-reviewable curve of hallucinations-over-time. | Appended by the agent during sessions; reviewed by the maintainer. | **Yes** — pre-existing date sections are immutable. New entries go in today's section (or a new section at the bottom). |

Adding new files here means adding a new instrument. If you can't articulate
what failure mode it catches and what gate enforces it, don't add it.

---

## Validators (pre-push Tier 1, gating)

Both run from `scripts/pre-push-validate.sh` Tier 1 alongside
`architectural-validation.sh`. Each is fast (~1s on a clean tree) so they
don't add meaningful pre-push latency; they're the cheapest gate that
catches the highest-leverage drift class.

### `scripts/validate-capability-claims.sh`
1. Re-derives the snapshot from source (calls `regen-capability-snapshot.sh --check`).
2. Re-derives every `modules/<X>/SKILLCARD.yaml` from the snapshot (calls
   `regen-skillcards.sh --check`).
3. Greps `modules/ai/README.md` for tight count patterns (`\bAll \d+ runtimes?\b`,
   `\b\d+ AiCapability\b`, `\b\d+ capabilities total\b`).
4. Asserts every match equals the snapshot count.

Loose patterns (e.g. "the other N runtimes" — a subset count that depends
on which runtimes opt into a feature) are deliberately not validated;
those need human review.

### `scripts/regen-skillcards.sh`, `scripts/scan-skillcards.sh`, `scripts/sign-skillcards.sh`, `scripts/verify-skillcards.sh`
Four-script pipeline for the per-runtime `SKILLCARD.yaml` manifests
(`modules/<X>/SKILLCARD.yaml`), the repo-root `SKILLCARDS.md` catalog
index, and the OpenSSF Model Signing signatures (`SKILLCARD.yaml.sig`):

1. `regen-skillcards.sh` emits the YAML from the snapshot plus each
   module's `pom.xml` and `META-INF/services/org.atmosphere.ai.AgentRuntime`,
   and also rewrites `SKILLCARDS.md` — the repo-root catalog index of
   every runtime + signature state. The catalog model is "git IS the
   sync" — every push to `main` replicates the cards to every clone.
2. `scan-skillcards.sh` runs the SkillSpector-equivalent pre-publish
   gate: prompt-injection patterns, hidden Unicode (zero-width, Bidi
   overrides), capability-safety (TOOL_CALLING ⇒ TOOL_APPROVAL, OWASP
   excessive-agency), SPI class existence on disk, path-shaped-field
   safety. HIGH-severity findings fail pre-push and the signing
   workflow.
3. `sign-skillcards.sh` produces `.sig` files via
   `model_signing sign` — defaults to Sigstore keyless OIDC (the
   production path; same toolchain NVIDIA's verified-agent-skills
   programme uses; reads `SIGSTORE_IDENTITY_TOKEN` env in CI), with
   `--key` and `--certificate` fallback modes for developer proof and
   regulated environments.
4. `verify-skillcards.sh` is the symmetric verifier — runs
   `model_signing verify` against each card / `.sig` pair, mirrors
   the verification command from the NVIDIA blog post.

Production signing is performed by `.github/workflows/sign-skillcards.yml`
on tag push: the workflow has `id-token: write`, obtains a short-lived
Fulcio cert via GitHub's OIDC provider, logs the signature to Rekor,
and attaches the `.sig` files to the GitHub release. Cards on `main`
between releases are unsigned by design — `SkillCardSnapshotTest`
skips signature verification when no `.sig` is present.

### `scripts/validate-drift-log.sh`
Structural hygiene only — the validator does **not** enforce that drift
gets *added* (the Stop hook does that). It enforces:

1. File exists and parses.
2. At least one `## YYYY-MM-DD` section present.
3. No future-dated section headers.
4. Sections in chronological order (oldest top, newest bottom).
5. Pre-existing date sections (older than today) match `origin/main`
   verbatim — append-only against the upstream baseline.

### `modules/ai-test/.../CapabilitySnapshotTest`
Pure-Java mirror of the bash validator, so `mvn test` catches the same
drift the pre-push hook would. Re-derives the snapshot from source,
deep-equals against the committed JSON, then greps `modules/ai/README.md`
for `\bAll \d+ runtimes?\b` and asserts against the snapshot count.

### `modules/ai-test/.../SkillCardSnapshotTest`
Three contracts on every `modules/<X>/SKILLCARD.yaml`:
1. Capability set + count match `.harness/capabilities.snapshot.json`.
2. Top-level shape (required keys, field patterns, `signature_file`
   slot) conforms to `spec: atmosphere/skillcard/v1`.
3. When `SKILLCARD.yaml.sig` exists, the OMS signature verifies
   against the Atmosphere CI workflow's Sigstore identity. Skipped
   silently when no `.sig` is present (the normal state on `main`
   between tags) or when `model_signing` is not installed locally.

Refuses to pass when a snapshot runtime has no SKILLCARD on disk.

---

## Hooks (Claude Code, behavioral)

Registered in `.claude/settings.json` under the project-level `hooks`
block. Adds to (does not replace) any hooks defined in
`~/.claude/settings.json`.

### `Stop` hook → `.claude/hooks/check-drift-log.sh`
Fires at the end of every Claude Code session in this repo.

1. Reads the session transcript path from the hook input JSON.
2. Greps the transcript for **high-precision** drift-correction patterns
   (`stale memory`, `\boff-by-one\b`, `I (was wrong|claimed)…(but|actual|truth)`,
   `memor… was/is wrong/stale/out of date`, `fabricated rule/stat/count/claim`,
   `verified by grep…disagree/contradict/wrong/stale`).
3. If a pattern matches **and** `.harness/drift-log.md` was not modified
   this session (no working-tree diff, no untracked add, not in last 3 commits),
   emits `{"decision": "block", "reason": "..."}` to force the agent to
   either append an entry or explicitly state the correction was trivial.
4. `stop_hook_active=true` short-circuits to a no-op so deliberate skips
   don't loop.

The patterns are deliberately narrow to minimize false positives. If a
recurring real correction shape isn't matching, add a new pattern with
concrete real-session evidence — don't loosen existing ones.

---

## Protocols

### Regenerating the capability snapshot

```bash
./scripts/regen-capability-snapshot.sh           # write the snapshot
./scripts/regen-capability-snapshot.sh --check   # exit 1 if it would change
./scripts/regen-capability-snapshot.sh --stdout  # write to stdout
```

`LC_ALL=C` is forced inside the script so bash `sort` matches Java's
`String.compareTo` (ASCII code-point order, not locale-aware collation
that treats `_` differently from `I`). Without that the snapshot's
capability list and the JUnit test's `TreeSet<String>` view diverge.

### Appending a drift entry

When the project maintainer catches an agent claim that diverged from ground truth
(or you self-catch via `git grep` / `find` / file read after spotting
memory↔code disagreement):

1. **Verify against current code first** — re-read the source. Don't
   trust memory files older than the most recent CHANGELOG bump.
2. State the correction transparently in conversation — quote the false
   claim verbatim, quote the ground truth verbatim.
3. Append a structured entry to `drift-log.md` in today's date section
   (start one if today doesn't have one yet). Required columns: claim,
   truth, slip path, gate added.
4. Add a regression-class gate where feasible (validator, JUnit test,
   memory update, prose-grep). Writing `none` is a legitimate value when
   no automated check makes sense — don't fabricate a gate.
5. Bundle log update + gate + prose fix in **a single commit**. That
   commit is the review unit.

The log is **append-only**. Don't edit older entries to "improve" them;
write a new entry pointing back if context changes. The signal is the
*rate* of entries over time, not the cleanliness of any single one.

---

## Memory anchor

`feedback_drift_log.md` in the user's auto-memory codifies the protocol
above so it loads at every session start. If that memory ever drifts
from this README, the README wins (this directory is the source of truth
for the harness shape; the memory is a pointer).

---

## Why a `.harness/` directory at all

The directory is self-contained and clearly scoped — anyone seeing
`.harness/` knows it's project-engineering plumbing, not runtime code.
That's intentional: it keeps the boundary visible so:

- The pre-push gates know what to validate (everything under `.harness/`
  is fair game for hygiene checks).
- A future contributor doesn't have to grep a dozen places to understand
  the AI-engineering feedback loop — they read this README.
- If we decide to share the pattern publicly, the directory exports
  cleanly.

If you propose adding a file outside `.harness/` that does similar work,
ask first whether it should live here instead.

---

## Further reading

- Anthropic, [*Effective harnesses for long-running agents*](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)
  (Justin Young, 2025-11-26) — initializer-agent + coding-agent + feature-list
  + progress-log pattern; the controlled experiment showing the same model
  goes from "unreliable" to "reliable" when given a proper harness.
- Anthropic, [*Harness design for long-running application development*](https://www.anthropic.com/engineering/harness-design-long-running-apps)
  — companion piece on harness design choices for app-development agents.
- OpenAI, [*Harness engineering: leveraging Codex in an agent-first world*](https://openai.com/index/harness-engineering/)
  — the harness-as-environment framing applied to Codex.
- [`walkinglabs/learn-harness-engineering`](https://github.com/walkinglabs/learn-harness-engineering)
  — beginner tutorial / course that synthesises the above sources into a
  five-subsystem teaching structure (instructions, state, verification,
  scope, session lifecycle). Useful as an onboarding read; the canonical
  references are the Anthropic and OpenAI posts above.
- [`juliusbrussee/caveman`](https://github.com/juliusbrussee/caveman) — the
  snapshot-as-source-of-truth + CI-sync pattern that inspired the snapshot
  file's diff-reviewable shape.
