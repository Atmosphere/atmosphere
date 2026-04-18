# Atmosphere E2E — Playwright Suite

End-to-end tests for the Atmosphere v0.5 foundation — admin control plane
plus the two proof samples (`personal-assistant`, `coding-agent`).

## Running

```bash
cd e2e
npm install
npm run install:browsers

# Start the sample in another terminal first, then:
npm run test:admin              # admin control plane smoke
npm run test:personal-assistant # sample #1 happy path
npm run test:coding-agent       # sample #2 happy path (ATMO_E2E_BASE_URL=http://localhost:8081)
```

## What these tests prove

- **`admin.spec.ts`** — admin UI loads with zero console errors; agent list
  endpoint returns an array; state controller endpoints respond.
- **`personal-assistant.spec.ts`** — sample #1 UI loads; `primary-assistant`
  agent registers; admin plane sees it. Additionally, a schedule request
  drives the `@AiTool` loop through `OpenAiCompatibleClient` and asserts
  the tool-call card **and** the narrative response both render — the
  regression surface for the strict OpenAI-compat tool-round-trip wire
  shape (`tool_calls` on the assistant message, `name` on the tool-role
  reply). Skipped when no `LLM_API_KEY` / `OPENAI_API_KEY` /
  `GEMINI_API_KEY` is set.
- **`coding-agent.spec.ts`** — sample #2 UI loads; `coding-agent` registers
  with the admin plane; Sandbox provider wiring succeeded at startup.
  A clone request also drives the full Sandbox flow against Docker and
  asserts the literal README bytes reach the client — the regression
  surface for the `session.stream()` vs `session.send()` mix-up (the
  first routes text through the LLM as fresh user input; only the second
  streams to the UI). Skipped when `SKIP_SANDBOX_E2E` is set on CI
  runners without Docker.

## What they do NOT prove (yet)

- Cross-channel continuity (Slack ↔ web handoff requires bot tokens).
- Cross-runtime parity (ran only against the default Spring AI runtime).
- Mid-stream `AgentResumeHandle` reattach (requires orchestrated
  disconnect/reconnect — queued for a later pass).

These are all on the Phase 4 checklist; this suite is the Playwright
scaffolding those later passes slot into without re-inventing the
harness.

## CI status (honest)

**These specs do NOT gate merges.** The `atmosphere-e2e` package is not
wired into any workflow under `.github/workflows/` — `e2e.yml` runs the
separate Playwright matrix under `modules/integration-tests/` against the
atmosphere.js client, not this suite. Treat the specs here as
*manual regression evidence* (run them after landing a foundation change
that would break them) rather than as gating CI.

Wiring the suite to CI is tracked for v4.0.40. The shape will be:
a dedicated job that spins up `personal-assistant` and `coding-agent`
via `java -jar target/*.jar`, waits for their `/api/console/info`
endpoints to return 200, runs the matching spec, and tears them down.
Specs that require an LLM (`schedule request fires tool call …`,
`research request …`, `draft request …`) already
`test.skip(!process.env.LLM_API_KEY && …)` so they run on-demand only.

Until the job lands:

```bash
cd e2e
ATMO_E2E_BASE_URL=http://localhost:8080 npx playwright test tests/personal-assistant.spec.ts
ATMO_E2E_BASE_URL=http://localhost:8080 npx playwright test tests/coding-agent.spec.ts
```

after booting the respective sample jar with the env vars documented in
`samples/spring-boot-personal-assistant/README.md` and
`samples/spring-boot-coding-agent/README.md`.
