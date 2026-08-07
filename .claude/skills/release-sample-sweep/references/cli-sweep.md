# CLI pass — `atmosphere` is a release artifact too

The CLI is the documented Quick Start ("Atmosphere CLI (recommended)") in
`samples/README.md`. It is how most users reach a sample, and it ships as four
separate distributions. A sample sweep that skips it validates the destination
and not the road.

## What CI already covers — do not re-litigate this by hand

Verified from `.github/workflows/cli-e2e.yml`, `cli-install.yml`, and
`cli/test-cli.sh`:

| Lane | Covers |
|---|---|
| `cli/test-cli.sh` | `list` (+`--tag`, `--category`, combined), `info` (all samples + error cases), `run` **argument validation only**, `new` validation + a network-gated clone, `new --runtime`, `new --routing`, the npx shim, `plugins`, `skills` (offline) |
| `cli/e2e-test-cli-runtime.sh` | Runtime E2E |
| `cli/e2e-test-runtime-overlay.sh` | Scaffold + compile all runtimes, then boot and assert `AgentRuntime.name()` |
| `cli-install.yml` | The curl installer on macOS + Linux → `version`, `list`, `list --tag ai`, `info spring-boot-chat`; npx `--help` and `--list-templates` |

## What CI does not cover — this is the manual pass

| Gap | Why it matters at release time |
|---|---|
| `atmosphere run <sample>` **actually booting**, then driven in a browser | `test-cli.sh` validates arguments; nothing boots a sample through the CLI and looks at the UI. The CLI's build/cache path is different from `java -jar` |
| `atmosphere new <template>` resolving the parent POM **from Maven Central** | Scaffolded projects resolve `atmosphere-project` from Central. If the release is not on Central yet, scaffolding is broken for every new user |
| `compose` | Zero matches in `test-cli.sh` and `e2e-test-cli-runtime.sh` |
| `checkpoint` | Zero matches in either script |
| `import` | One match in `e2e-test-cli-runtime.sh` — thin |
| `list` / `info` counts vs reality | The registry can drift from `ls samples/` |
| The **published** distributions | npm, the Homebrew tap, `install.sh`, SDKMAN are published artifacts; CI tests the in-repo copies |

## Cold cache — the trap that fakes a pass

`atmosphere run` caches built jars at `$ATMOSPHERE_HOME/cache/v$VERSION`
(default `$HOME/.atmosphere`), verified by sha256 when a checksum file exists.
A cached jar from a previous version boots the **old** artifact and reports a
green sweep for code you are not shipping.

Do **not** delete the maintainer's real cache. Point the CLI at a throwaway home
instead — same effect, no ownership violation:

```bash
export ATMOSPHERE_HOME="$(mktemp -d)/atmosphere-sweep"
```

Two more version subtleties, both verified in `cli/atmosphere`:

- `VERSION="4.0.63"` is **pinned in the script** and bumped by the release
  workflow's Phase 6, *after* publishing. Pre-release, the in-repo CLI still
  names the previous version — expected, not a finding.
- `get_samples_json` prefers the `samples.json` sitting next to the script and
  otherwise downloads from `main` on GitHub. So the **in-repo** CLI reads the
  working tree, while an **installed** CLI reads `main`. Be explicit about which
  one you are testing.

## The pass

Run this after the sample sweep, against the same build.

### 1. Registry truth

```bash
ls -d samples/*/ | grep -v shared-resources | wc -l
cli/atmosphere list | wc -l                       # reconcile against the above
cli/atmosphere list --tag ai
cli/atmosphere info spring-boot-ai-chat           # must print a usable `atmosphere run` line
cli/atmosphere version
```

A sample present on disk but missing from `list` is a `cli/samples.json` drift
finding — sample changes are supposed to update `samples.json`, the `cmd_new`
template map, the READMEs, and CI in the same commit.

### 2. `atmosphere run` → browser (the core gap)

Pick one sample per packaging so the CLI's build path is covered where it
actually differs: **spring-boot**, **quarkus**, and an **executable-jar**.

```bash
export ATMOSPHERE_HOME="$(mktemp -d)/atmosphere-sweep"
cli/atmosphere run spring-boot-ai-chat --port 9201 --env LLM_MODE=local --env LLM_MODEL=qwen2.5:3b
```

Then drive it in the browser exactly as in Phase 1 —
`http://localhost:9201/atmosphere/console/`, same assertions, same evidence
collection. The point is that the **CLI-produced artifact** streams, not that
the CLI printed a URL.

Also confirm:

- The first run **builds** (cold cache) and says so; the second run reports
  `Using cached … (verified)`.
- `--port` reaches the app (it maps to `-Dserver.port`).
- `runnable=false` samples are refused with guidance — `chat` and `grpc-chat`
  are expected to be rejected by `run`, which is correct behaviour, not a bug.

### 3. `atmosphere new` → scaffold, compile, run

```bash
cd "$(mktemp -d)"
atmosphere new my-app --template rag        # sparse-clones the mapped sample
cd my-app && ./mvnw -q compile              # resolves the parent from Maven Central
```

This is the release-order-sensitive one. Before the release is on Central the
scaffold **cannot** compile against the new version — so either run it against
the current released version pre-release, or re-run it post-publish. Say which
you did; do not report a pre-publish failure as a CLI bug, and do not report a
pass against the old version as if it covered the new one.

Cover each template in the `cmd_new` map. Every template sparse-clones a sample
listed in `cli/samples.json` — there is no standalone generator, so a template
pointing at a renamed sample breaks silently.

### 4. The thin-coverage commands

Exercise `compose`, `import`, and `checkpoint` by hand — they have no or nearly
no automated coverage, so the sweep is their only gate.

```bash
cli/atmosphere compose --help
cli/atmosphere import --help
cli/atmosphere checkpoint help
```

Then drive at least the primary path of each. Anything broken here gets the same
treatment as a sample finding: root cause, fix, and a **test in
`cli/test-cli.sh`** proven to bite (the CLI's regression home — a Playwright
spec is the wrong vehicle for a shell CLI).

### 5. Distributions

Pre-release, verify the in-repo copies are coherent:

```bash
sh cli/install.sh                 # curl installer path
command -v atmosphere && atmosphere version
npx create-atmosphere-app --list-templates
```

`cli/homebrew/atmosphere.rb` is a **reference copy**; the published formula
lives in the separate `Atmosphere/homebrew-tap` repo and is rewritten by the
release workflow (`Formula/atmosphere.rb`). The in-repo file carrying an older
version is therefore expected — check the tap, not this file, when verifying
what users install.

Post-publish, re-verify against what was actually shipped: `brew install` from
the tap, `npx create-atmosphere-app@<version>`, and `install.sh` fetched from
the web. A cancelled or partial release publishes immutable artifacts — verify,
never assume.

## CLI ledger section

| Check | Command | Result | Evidence |
|---|---|---|---|
| Registry count matches `ls samples/` | | | |
| `list` / `list --tag` / `info` | | | |
| `run` spring-boot → browser-driven | | | |
| `run` quarkus → browser-driven | | | |
| `run` executable-jar → browser-driven | | | |
| Cold-cache build, then cached-and-verified | | | |
| `runnable=false` refused with guidance | | | |
| `new` per template: scaffold + compile | | | |
| `compose` / `import` / `checkpoint` | | | |
| `install.sh` / npx | | | |
| Post-publish: tap / npm / install.sh | | | |
