# Regression specs — every sweep issue gets a Playwright test

A sweep finding that is fixed without a test will come back. The rule is:
**one Playwright e2e spec per issue found**, wired into a CI lane, and proven to
fail against the pre-fix artifact.

The sweep is a manual browser pass. Its output is not just fixes — it is
permanent automated coverage for the exact flow that broke.

## Step 1 — pick the home

There are two Playwright suites. Choosing wrong means the spec runs nowhere.

| | `modules/integration-tests/e2e/` | `e2e/tests/` (repo root) |
|---|---|---|
| Config | `modules/integration-tests/playwright.config.ts` | `e2e/playwright.config.ts` |
| Boot | The spec boots the sample itself via `startSample(SAMPLES['x'])` | An external harness boots it; the spec reads `ATMO_E2E_BASE_URL` |
| Registration | A `projects[]` entry + a leg in `.github/workflows/e2e.yml` | A job in `.github/workflows/foundation-e2e.yml` |
| Gate tier | `pw:boot:` / `pw:quarkus:` / `pw:dev:` | `fnd:` |

Decision rule:

1. The sample already has a Playwright **project** → extend that spec. Cheapest
   and keeps the coverage in one place.
2. The sample is in the `fnd:` tier of `scripts/release-gate-samples.sh` →
   extend its `e2e/tests/<name>.spec.ts`.
3. The sample has **no** spec (a `smoke:` entry in the gate's coverage map) →
   add a new project in `modules/integration-tests/`, and **upgrade the coverage
   map entry from `smoke:` to `pw:boot:`**. A smoke entry is a declared coverage
   gap; closing one is part of the fix.

Read the current mapping before choosing:

```bash
scripts/release-gate-samples.sh --list
```

## Step 2 — make the sample bootable by the fixture

Specs in `modules/integration-tests/e2e/` boot through
`e2e/fixtures/sample-server.ts`. If the sample has no `SAMPLES[...]` entry, add
one — it is what makes the spec boot the **packaged jar** (the artifact level
the sweep exists to cover):

```ts
'spring-boot-<sample>': {
  name: 'spring-boot-<sample>',
  dir: 'spring-boot-<sample>',
  port: 81xx,                       // unique across the file — collisions fail the run
  type: 'spring-boot',              // | 'quarkus' | 'jetty-war' | 'embedded-jetty'
  readyPath: '/atmosphere/agent/x', // HTTP + WS readiness probe
  httpOnlyReady: true,              // set when the sample serves no WebSocket at readyPath
  env: { /* only what the sample genuinely needs */ },
},
```

The fixture waits for the TCP port, then HTTP, then (unless `httpOnlyReady`) a
real WebSocket open — that ordering is what removes the "HTTP answers but the WS
layer isn't up yet" race. Don't work around it with a sleep.

## Step 3 — write the spec against the failure, not the feature

```ts
import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;
test.beforeAll(async () => { server = await startSample(SAMPLES['spring-boot-x']); });
test.afterAll(async () => { await server?.stop(); });

test.describe('<sample> — <what broke>', () => {
  test('<the exact flow that failed in the sweep>', async ({ page }) => {
    await page.goto(`${server.baseUrl}/atmosphere/console/`);
    await expect(page.getByTestId('atmosphere-connection-status')).toContainText('Connected');

    await page.getByTestId('chat-input').fill('<the prompt that reproduced it>');
    await page.getByTestId('chat-send').click();

    // Assert the thing that was broken. For the 2026-07 langchain4j skew that
    // is "a tool card appears and no error frame renders" — not "a reply
    // arrives", which was true before the fix too.
    await expect(page.getByTestId('tool-card')).toBeVisible();
    await expect(page.getByTestId('message-list')).not.toContainText('IllegalArgumentException');
  });
});
```

Rules:

- **Assert the specific behaviour that broke.** A spec that would have passed
  before the fix is worthless. `expect(true).toBe(true)` and "the page loaded"
  are forbidden.
- **Use the Console test ids** from `driving-recipes.md`, not text matching, for
  anything structural.
- **Deterministic assertions where possible.** Prefer a tool card, an error
  frame's absence, a status transition, a replayed event count over LLM prose.
  If the flow needs a model, gate on `LLM_MODE=fake` behaviour or a
  deterministic tool.
- **Neutral names in fixtures/personas** — Alice / Alex / Bob. Never the
  maintainer's private handle.
- **No `@flaky` tag as a workaround.** If it's non-deterministic, the assertion
  is wrong.

## Step 4 — register it so CI runs it

For `modules/integration-tests/`:

```ts
// playwright.config.ts — projects[]
{ name: '<project-name>', testMatch: /<file>\.spec\.ts/ },
```

```yaml
# .github/workflows/e2e.yml — add to an existing group's `projects:` list
projects: "…,<project-name>"
```

A project with no workflow leg never runs. That exact gap is why the e2e.yml
group list carries the comment about specs being "unmapped by any matrix leg".

For `e2e/tests/`: add a job to `.github/workflows/foundation-e2e.yml` that boots
the sample, sets `ATMO_E2E_BASE_URL`, and runs
`npx playwright test tests/<file>.spec.ts --reporter=list`.

Then update `scripts/release-gate-samples.sh`:

- `coverage_of()` — point the sample at the new project / spec.
- `shard_samples()` — every mapped sample must sit in **exactly one** shard;
  `verify_map` fails the gate otherwise.

## Step 5 — prove it bites

A regression test is unproven until it has been observed to fail:

```bash
git stash                       # or check out the pre-fix artifact
<run the new spec>              # MUST fail, naming the real symptom
git stash pop
<run the new spec>              # MUST pass
```

Record both outcomes in the ledger and in the commit body. "Added a test, it
passes" is not evidence — the 2026-07 lint was only trusted because
re-introducing `1.15.0` made it fail and name the offending file.

## Step 6 — add a build-time gate when the issue is a class

Some findings are one instance of a repeatable mistake. Where a cheap static
check can close the whole class, ship it alongside the spec:

- Version skew between a sample pom and the reactor →
  `SampleLangChain4jVersionLintTest` scans every sample pom and fails on a
  hardcoded version.
- A new sample directory with no coverage → already gated by `verify_map` in
  `scripts/release-gate-samples.sh`.

A gate must never walk its own artifact — a check that greps the very file it
validates will be satisfied by that file's own citations. Exclude the artifact
from the walk and add a regression proving a comment-only mention reads as zero.

## Checklist per issue

- [ ] Spec written, asserting the specific broken behaviour
- [ ] Sample bootable by the fixture (`SAMPLES` entry if new)
- [ ] Project registered in the Playwright config
- [ ] Workflow leg added (`e2e.yml` group or `foundation-e2e.yml` job)
- [ ] `release-gate-samples.sh` coverage map + shard updated
- [ ] Proven to fail pre-fix and pass post-fix, both recorded
- [ ] Build-time lint added if the issue is a class, not an instance
