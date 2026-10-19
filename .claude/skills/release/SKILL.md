---
name: release
description: Cut an Atmosphere release end to end — the only path to release-4x.yml. Use whenever a release, a version cut, "ship it" or publishing to Maven Central/npm/Homebrew/SDKMAN is requested. Runs the preflight in order (open carnet, compatibility review, CI green on HEAD, sample sweep covering HEAD), triggers the workflow, verifies every distribution channel, labels the carnet, and cleans up. Stops at the first failed check.
---

# Release

`release-4x.yml` publishes to five channels that cannot be taken back:

- Maven Central and npm are immutable.
- Homebrew and SDKMAN are announced to users.

This skill is the checklist in front of it. **Run every step in order and stop
at the first one that fails.** A failed check is reported to the maintainer
with the evidence. It is never worked around, and never answered with
"CI is green, so it is probably fine": that reasoning shipped 4.0.75 without a
sweep of its last three user-facing changes.

## Step 1: Open carnet

```bash
gh issue list -R "$(.agents/skills/carnet/carnet.sh tracker)" --state open \
   --json number,title -q '.[] | "#\(.number) \(.title)"'
.agents/skills/carnet/carnet.sh status --short
```

Every open entry is either fixed or **explicitly excluded by the maintainer for
this release**. An exclusion from a previous release does not carry over: ask
again. An entry another session holds in progress blocks the release.

## Step 2: Compatibility review

```bash
PREV=$(git tag --list 'atmosphere-4.*' --sort=-v:refname | head -1)
git log --oneline "$PREV..origin/main" -- ':(glob)modules/**/src/main/**' atmosphere.js/src
```

List every change to a public API, wire format, default, configuration key or
dependency version. Name each one to the maintainer and get a yes before
continuing. Silence is not a yes.

## Step 3: CI green on the exact commit

```bash
git fetch origin && SHA=$(git rev-parse origin/main)
gh run list --commit "$SHA" -L 100 --json name,status,conclusion \
   -q '.[] | "\(.status)\t\(.conclusion)\t\(.name)"'
```

- Pass the full SHA: `--commit` with a short SHA silently matches nothing.
- Every workflow must be `completed` + `success`. A failure is fixed and the
  check starts again from Step 1, because the fix is a new commit.

## Step 4: The sample sweep covers the commit

```bash
git checkout --detach origin/main && ./scripts/validate-sweep-attestation.sh
```

This is the same gate `release-4x.yml` runs first.

- **If it fails**, run the `release-sample-sweep` skill: the full sweep, or its
  Step 4 re-test for the surfaces the gate lists.
- Commit the new `.harness/sweep-attestation.json`, then start again from
  Step 3.
- "The changes have Playwright specs" is not coverage. The sweep drives
  packaged artifacts in a browser and the simulator, and specs do not.
- `skip_sweep_gate` is for an emergency the maintainer declares in words. It is
  never the agent's call.

## Step 5: Trigger

```bash
gh workflow run release-4x.yml --ref main \
  -f release_version=X.Y.Z -f next_dev_version=X.Y.(Z+1)-SNAPSHOT \
  -f publish_js=true -f js_only=false -f dry_run=false
```

Watch the run until it completes. A failed job is diagnosed from its log
before anything else is triggered:

- `gh run rerun <id> --failed` retries only the jobs that failed;
- a full re-run would collide with the tag and the versions that were already
  published.

## Step 6: Verify every channel from outside the workflow

| Channel | Check |
|---|---|
| Maven Central | `curl -s -o /dev/null -w '%{http_code}' https://repo1.maven.org/maven2/org/atmosphere/atmosphere-runtime/X.Y.Z/atmosphere-runtime-X.Y.Z.pom` returns `200` (also check `atmosphere-ai`) |
| npm | `npm view atmosphere.js version` and the `latest` dist-tag |
| GitHub release | `gh release view atmosphere-X.Y.Z` shows not draft, with the zip and `.sha256` |
| Homebrew | the tap formula `Formula/atmosphere.rb` is at `X.Y.Z` |
| SDKMAN | the `Publish CLI to SDKMAN` job is green, and `https://api.sdkman.io/2/broker/download/atmosphere/X.Y.Z/darwinarm64` returns `302` (the default and list endpoints lag behind) |
| main | the pom is `X.Y.(Z+1)-SNAPSHOT` and atmosphere.js is bumped |

Then wait for CI on the bump commit. The `chore(js)` bump is pushed by the
bot and triggers no workflows, so watch the `prepare for next development
iteration` commit before it.

## Step 7: Close out

- `carnet.sh label <n> +X.Y.Z` for every carnet entry the release fixed.
- Remove the release worktrees and branches. A worktree that has submodules
  needs `--force`; check first that it is clean and has no unpushed commits.
- Report to the maintainer: one row per channel with its evidence, anything
  that failed, and anything still open.
