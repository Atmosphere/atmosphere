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
  agent registers; admin plane sees it.
- **`coding-agent.spec.ts`** — sample #2 UI loads; `coding-agent` registers
  with the admin plane; Sandbox provider wiring succeeded at startup.

## What they do NOT prove (yet)

- Full-flight LLM responses (requires `OPENAI_API_KEY` and costs money).
- Cross-channel continuity (Slack ↔ web handoff requires bot tokens).
- Cross-runtime parity (ran only against the default Spring AI runtime).
- Mid-stream `AgentResumeHandle` reattach (requires orchestrated
  disconnect/reconnect — queued for a later pass).

These are all on the Phase 4 checklist; this suite is the Playwright
scaffolding those later passes slot into without re-inventing the
harness.

## CI integration

The `atmosphere-e2e` package is not yet wired into the Maven reactor;
the Playwright suite runs as its own lane. When the CI matrix lands,
each sample spins up via `mvn spring-boot:run` in the background, waits
for the admin endpoint to return 200, and runs the corresponding spec.
