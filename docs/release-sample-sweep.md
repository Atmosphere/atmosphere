# The Release Sample Sweep

Before every Atmosphere release, someone boots every sample the way a user would
and drives it in a real browser. That ritual is written down as an agent skill —
`.claude/skills/release-sample-sweep/` — so it runs the same way every time
instead of being re-derived from memory each release.

This page is for humans: what the sweep does, what it will do to your machine,
how to run it (with or without an agent), and what you have to keep in sync when
you add a sample.

> **Not a replacement for CI.** `ci.yml`, `e2e.yml`, and
> `release-gate-samples.yml` still gate every push. The sweep covers the layer
> those cannot reach — see [Why it exists](#why-it-exists).

## What it gates

Three user-facing surfaces, three drivers. All three are release gates.

| Surface | What | Driven with |
|---|---|---|
| **Samples** (31) | Everything under `samples/`, booted from its packaged artifact | A real browser (Chrome DevTools), or the wire protocol for the headless ones |
| **Expo client** (1) | `samples/spring-boot-ai-classroom/expo-client/` | The iOS simulator — it is a native app, a browser cannot reach it |
| **CLI** | `atmosphere run` / `new` / `compose` / `import` / `checkpoint`, plus the curl installer, npx, the Homebrew tap and SDKMAN | Shell, then a browser against whatever `atmosphere run` booted |

The sample count is not hardcoded anywhere authoritative — it is checked against
`ls samples/` (minus `shared-resources`) and `cli/samples.json` at the start of
every run.

## Why it exists

CI builds and tests **modules**. The sweep exercises **packaged artifacts in a
browser**. Real bugs live in the gap, and they are invisible to a green build:

| Sweep | Bug | Why CI was green |
|---|---|---|
| 2026-06-30 | `quarkus-ai-chat` would not start at all — OpenTelemetry api/common version skew introduced by a dependency bump | Module tests never boot the sample's fast-jar |
| 2026-07-17 | `spring-boot-orchestration-demo` crashed on every tool-using turn — the sample's `pom.xml` hardcoded `langchain4j-open-ai:1.15.0` while the reactor manages 1.17.0 | The module compiled and tested against 1.17.0; only the sample's own jar bundled 1.15.0 |

Both were fatal for a user following the README, and both compiled cleanly.
That is the class of defect this sweep exists to catch.

Two more structural gaps it covers:

- The **Expo client** is not a Maven module, is not in `cli/samples.json`, and
  cannot be reached by the Playwright suites. The sweep is its only gate.
- The **CLI** has good CI coverage of `list`/`info`, argument validation, the
  runtime overlays and the installers — but nothing in CI boots a sample through
  `atmosphere run` and looks at the resulting UI, and `compose` / `checkpoint`
  have no automated coverage at all.

## How it works

Five phases. The important structural rule is that **step 1 is collect-only** —
nothing is fixed while the sweep is running, because fixing changes the artifact
under test and silently invalidates every sample already verified against the
old one.

```
Step 0   Preconditions  build the reactor, start Ollama, open the ledger
Step 1a  Samples        31 samples: launch → drive → collect → verdict → teardown
Step 1b  Expo client    the RN client in the iOS simulator
Step 1c  CLI            run / new / compose / import / checkpoint + distributions
Step 2   Triage         classify every finding, rank by blast radius
Step 3   Fix            root-cause fix + a regression test per issue
Step 4   Re-test        the failures in full, plus a blast-radius subset of the passes
Step 5   Report         vault report, CI green
```

Findings are classified before anything is fixed — framework bug, sample bug,
config/environment, or **model limitation**. That last one matters: a small local
model emitting invalid tool-call arguments is not a framework regression, and the
sweep requires proving it on a capable model before either conclusion is written
down.

### Every issue gets a regression test

A finding that is fixed without a test comes back. Each one gets a test in the
suite that can actually run it:

| Issue on | Regression home |
|---|---|
| Sample, Console, or framework | A Playwright spec, registered as a project and wired into an `e2e.yml` leg + the `release-gate-samples.sh` coverage map |
| CLI | A case in `cli/test-cli.sh` |
| Expo / React Native | An `atmosphere.js` vitest over the `./react-native` export |

The test must be **proven to fail before the fix**, not just pass after. Where a
build-time lint can close the whole class it gets one too — the langchain4j fix
shipped `SampleLangChain4jVersionLintTest`, which fails the build if any sample
pom hardcodes a LangChain4j version.

### Re-testing after a fix

A PASS is evidence about one artifact. Once a fix changes that artifact, passes
recorded against the old one are only still valid if the fix could not reach
them. The skill carries a blast-radius table — touch `modules/cpr` and you
re-drive one sample per container and per transport; touch the Console bundle and
you re-drive one per Console surface class; touch a root-pom dependency version
and you re-sweep everything.

Whatever is deliberately **not** re-driven has to be named in the report with a
reason. Silent subsetting reads as "everything was re-verified".

## Running it

### With Claude Code

```
/release-sample-sweep
```

The skill drives the whole procedure and keeps a ledger as it goes.

### By hand

The procedure is plain shell plus a browser; nothing about it requires an agent.

```bash
# 0. Preconditions
./mvnw install -DskipTests -Pfastinstall     # framework + every sample jar
./scripts/sync-console-bundle.sh --check     # the Console you will drive must be current
ollama list                                  # qwen2.5:3b + qwen2.5:7b-instruct-q4_K_M

# 1. Boot one sample from its packaged artifact and leave it running
.claude/skills/release-sample-sweep/scripts/sweep-sample.sh start spring-boot-ai-chat \
    --port 9101 --ready-path /atmosphere/console/ \
    --env LLM_MODE=local --env LLM_MODEL=qwen2.5:3b

# 2. Drive http://localhost:9101/atmosphere/console/ in your browser

# 3. Collect the server side, then tear down
.claude/skills/release-sample-sweep/scripts/sweep-sample.sh warnings spring-boot-ai-chat
.claude/skills/release-sample-sweep/scripts/sweep-sample.sh stop spring-boot-ai-chat
```

`sweep-sample.sh status` lists what is running; `stop-all` tears everything down.
Ports, ready-paths, per-sample environment and the headline assertion for each
sample are in `.claude/skills/release-sample-sweep/references/sample-matrix.md`.

### Prerequisites

- A full reactor build (`./mvnw install -DskipTests -Pfastinstall`)
- **Ollama** running locally with `qwen2.5:3b` and `qwen2.5:7b-instruct-q4_K_M`.
  The sweep is keyless by design — no API key, no quota. Quota starvation is
  what reduced nine samples to plumbing-only in the 2026-06 sweep.
- Chrome, plus the Chrome DevTools MCP server if you are driving it with an agent
- Xcode + an iOS simulator for the Expo pass
- Docker for `spring-boot-coding-agent`'s sandbox and `spring-boot-browser-agent`
- Roughly **1 GB of disk** for one version's sample jars under `samples/*/target/`

The cost is dominated by JVM boots and LLM turns, not by the tooling.

## What it does to your machine

Worth knowing before you start it:

- **Starts one JVM at a time**, from `samples/<name>/target/`, on ports 9101+
  (18810 for `quarkus-ai-chat`). Some of those jars are 100 MB+ and the JVMs are
  not small.
- **Opens browser pages** — a fresh one per sample, closed after.
- **Writes a ledger** to `claude_docs/sample-sweep-<date>.md`, which is a
  gitignored symlink into the Obsidian vault. Nothing lands in the repo.
- **Writes logs and pidfiles** to `target/sweep/`.
- **May temporarily edit `expo-client/App.tsx`** to repoint `SERVER_URL` at the
  sweep port. The procedure requires reverting it and confirming a clean tree.
- **Uses a throwaway `ATMOSPHERE_HOME`** for the CLI pass, so your real
  `~/.atmosphere` cache is never touched.

And what it will **not** do:

- **It will not touch a process it did not start.** If a port is occupied, the
  launcher refuses to boot and you pick another port — a green probe against
  someone else's process is a false pass. `pkill` is never used; everything is
  killed by PID, and teardown verifies the port was actually released.
- **It will not delete your CLI cache**, your `~/.m2`, or anything outside
  `target/`.
- **It will not commit or push.** Fixes are ordinary commits you review.

## What you get

A report in the vault under `Claude Outputs/`, promoted from the ledger:

- The full matrix — one row per sample, with port, transport, verdict, and a
  concrete sentence of evidence, plus the console errors and server warnings seen
- Warnings recorded even for passing samples. The warning inventory is half the
  value of the sweep; a warning that shows up on twenty samples is a framework
  finding hiding as noise.
- Issues found, root causes, fix commits, and the regression test each one got
- Honest caveats — what was gated on a missing key, Docker or a collector, and
  what was not verified this run

Verdicts are **PASS** (headline feature observed rendered), **PARTIAL** (plumbing
proven, feature not observed, reason named) or **FAIL**. "The server started",
"HTTP 200" and "the bytes were in the DOM" are explicitly not passes. Nothing is
ever recorded as "flaky".

## Keeping it accurate when you change a sample

Adding, renaming, or removing a sample touches more than `samples/`. In the same
commit:

| Update | Why |
|---|---|
| `cli/samples.json` | The CLI registry — `list`, `info`, and `run` read it |
| The `cmd_new` template map in `cli/atmosphere` | Templates sparse-clone a sample; a renamed sample breaks scaffolding silently |
| `scripts/release-gate-samples.sh` coverage map + shard | A sample directory with no entry **fails the gate** — this one is enforced |
| `modules/integration-tests/e2e/fixtures/sample-server.ts` | So Playwright specs can boot it |
| `.claude/skills/release-sample-sweep/references/sample-matrix.md` | Port, ready-path, and the headline assertion for the manual pass |
| `samples/README.md` | The human-facing catalogue |

Only the release-gate coverage map is enforced by a build gate today. The rest is
discipline, which is exactly why the sweep re-derives the sample count from the
filesystem instead of trusting any single list.

## Relationship to CI

| Lane | Scope |
|---|---|
| `ci.yml` | Full reactor build and unit tests |
| `e2e.yml` | Playwright projects — the fixture boots each sample's packaged jar |
| `foundation-e2e.yml` | The root `e2e/tests/` suite against an externally booted sample |
| `release-gate-samples.yml` | Boots every runnable sample from its packaged artifact and asserts — nightly and as a release precondition |
| **This sweep** | The browser and native layer on top: rendering, streaming, transports, tool cards, console errors, the CLI, the Expo client |

`scripts/release-gate-samples.sh --list` prints the current automated coverage
map, including which samples are still smoke-only. Those are declared gaps, not
silent ones — closing one is part of fixing whatever the sweep finds there.

## Where the machinery lives

| Path | Purpose |
|---|---|
| `.claude/skills/release-sample-sweep/SKILL.md` | The procedure |
| `.../references/sample-matrix.md` | Per-sample port, boot type, drive surface, headline assertion |
| `.../references/driving-recipes.md` | Browser recipes per surface class, plus the Console test-id inventory |
| `.../references/expo-sweep.md` | The Expo/React Native pass |
| `.../references/cli-sweep.md` | The CLI pass, and what CI already covers |
| `.../references/regression-specs.md` | Writing and wiring the Playwright regression spec |
| `.../references/retest-subset.md` | Blast radius → what to re-drive after a fix |
| `.../references/troubleshooting.md` | Traps that have actually bitten past sweeps |
| `.../assets/ledger-template.md` | The ledger |
| `.../scripts/sweep-sample.sh` | Boot one sample from its packaged artifact and leave it up |
