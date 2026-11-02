# `.harness/` — AI-Assisted Engineering Instrumentation

Atmosphere's harness — the engineering scaffold around the AI agents that
contribute to this repo. The framing comes from the harness-engineering
pattern documented by Anthropic ([*Effective harnesses for long-running
agents*](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents),
[*Harness design for long-running application development*](https://www.anthropic.com/engineering/harness-design-long-running-apps))
and OpenAI ([*Harness engineering: leveraging Codex in an agent-first
world*](https://openai.com/index/harness-engineering/)): the model is the
engine, the harness is the rails — instructions, state, verification,
scope, and session lifecycle around the model. This directory holds the
**inputs of the gates** that keep prose claims about this repo honest
against running code: snapshots, registries and allowlists that the
validators in `scripts/` read. The point is **not** utilization metrics,
which invite Goodhart's Law. Each file here exists because a validator
reads it; a file nothing reads does not belong here.

---

## Files

| File | Read by | Runs in |
|------|---------|---------|
| `capabilities.snapshot.json` | `regen-capability-snapshot.sh --check`, `validate-capability-claims.sh`, `regen-skillcards.sh`, `validate-runtime-overlay-coverage.sh`, `CapabilitySnapshotTest`, `SkillCardSnapshotTest` | pre-push; CI (`mvnw test`, `cli-e2e.yml`); release (`release-4x.yml`); `sign-skillcards.yml` |
| `enumeration-allowlist.txt` | `validate-capability-claims.sh` | pre-push |
| `sweep-attestation.json` | `validate-sweep-attestation.sh` (self-test `test-sweep-attestation.sh`) | release (`release-4x.yml`); CI runs the self-test |
| `blog-claims.json` | `validate-blog-claims.sh` | release (`release-4x.yml`) |
| `playwright-project-exclusions.txt` | `validate-playwright-project-coverage.sh`, `test-playwright-project-coverage.sh` | pre-push; CI (`ci.yml`, `e2e.yml`) |
| `doc-version-allowlist.txt` | `validate-doc-version-alignment.sh`, `validate-atmosphere-doc-version.sh` | pre-push; CI (`doc-version-guard.yml`); release |
| `atmosphere-doc-version-allowlist.txt` | `validate-atmosphere-doc-version.sh` | pre-push; CI (`doc-version-guard.yml`, `ci.yml`); release (`release-4x.yml`, `release-preflight.sh`) |
| `thirdparty-version-allowlist.txt` | `validate-doc-thirdparty-versions.sh` | pre-push |
| `doc-symbol-allowlist.txt` | `validate-doc-symbols.sh` | pre-push |
| `phantom-javadoc-allowlist.txt` | `validate-no-phantom-javadoc-refs.sh` | pre-push |
| `orphan-class-allowlist.txt` | `validate-no-orphan-classes.sh` | pre-push |
| `external-class-allowlist.txt` | `validate-backend-class-refs.sh` | pre-push |
| `private-handle-allowlist.txt` | `validate-no-private-handle.sh` | pre-push |
| `facts.json` | `validate-facts-registry.sh`, `.claude/workflows/doc-drift-audit.js` | pre-push |
| `drift-log.md` | `validate-drift-log.sh` (structure only) | pre-push, when the log or its validator changes |
| `crewai-e2e-success.png` | cited by `CHANGELOG.md` only | nothing; removed at the next release cut together with that citation |
| `skillcard-scan-report.json` | gitignored output of `scan-skillcards.sh`, not an input | — |

"pre-push" means `scripts/pre-push-validate.sh`, which runs a gate only
when the push touches the paths that gate watches. A gate that runs
nowhere else is skipped by a merge made on GitHub, so its silence is not
evidence that nothing drifted.

Adding a file here means adding an input to a gate. If you can't name the
validator that reads it and where that validator runs, don't add it.

---

## Validators

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
The drift-log is frozen (see below). The validator checks structure only:

1. File exists and has at least one `## YYYY-MM-DD` section.
2. No future-dated section headers.
3. Sections in chronological order (oldest top, newest bottom).
4. Every header of a section older than today that is on `origin/main`
   still exists verbatim. Section bodies are not compared.
5. No section is dated after the freeze date (`FROZEN_ON`).

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

## The drift-log is frozen

`drift-log.md` recorded agent claims that diverged from ground truth
(claim, truth, slip path, gate added) from 2026-05 to 2026-11-02. Its
May–June backlog produced most of the gates above. After that nothing
read it, and new lessons went to private agent memory instead, so it was
frozen on 2026-11-02 and the Stop hook that asked for entries was
removed. It stays as history: gate scripts cite its entries by number.

A caught drift is now filed as a carnet issue
(`.agents/skills/carnet/carnet.sh create`) with the claim, the truth, the
slip path and the gate that would catch the class. The issue has an owner
and is closed with the commit that fixes and gates it.

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

### Verifying a claim with a zero-context reviewer

`.claude/agents/goldfish-reviewer.md` defines a reviewer that starts
without CLAUDE.md, AGENTS.md or memory and has read-only tools. Use it
when the agent that wrote a claim should not be the one checking it:
`doc-drift-audit.js` runs its Verify stage with it and hands each
verifier only the claim and its location, never the auditor's evidence.
Agent definitions load when a session starts, so a session begun before
the file existed cannot use it; the workflow then reports INCOMPLETE.

---

## Why a `.harness/` directory at all

The directory is self-contained and clearly scoped — anyone seeing
`.harness/` knows it's project-engineering plumbing, not runtime code.
That's intentional: it keeps the boundary visible so:

- A future contributor finds every gate input in one place, with the
  validator that reads it, by reading the table above.
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
