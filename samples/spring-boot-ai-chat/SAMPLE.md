# spring-boot-ai-chat — mirroir-run replay manifest

`mirroir-run --sample samples/spring-boot-ai-chat --scenarios must-pass` drives this sample
end-to-end: spawns the JVM, opens the bundled Atmosphere Console in a real browser, types a
prompt into the chat input, asserts a streamed reply lands in the message list, and shuts the
JVM down cleanly. Verified selectors are real `data-testid` hooks shipped in
`atmosphere.js/src/chat/` (`chat-input`, `chat-send`, `message-bubble`, `status-label`).

The block below is the runner manifest. The runner extracts it via
`extract_yaml_block(SAMPLE.md)`; everything outside the fenced block is documentation only.

```yaml
version: 1
name: spring-boot-ai-chat
description: |
  archetype: chat-streaming
  runtime: spring-boot-4 + built-in
  required scenarios: stream, fallback
session:
  boot_once: true
  boot_ready_port: 8080
  boot_ready_timeout_s: 120
  boot:
    command: "./mvnw -q spring-boot:run -pl samples/spring-boot-ai-chat"
    cwd: "../.."
    env:
      LLM_MODE: "fake"
      ATMOSPHERE_AUTH_ENABLED: "false"
  scenarios:
    must_pass:
      - scenarios/stream.yaml
      - scenarios/fallback.yaml
```

## Why `LLM_MODE=fake` and `ATMOSPHERE_AUTH_ENABLED=false`

The pilot validates the runner+sample wiring, not the LLM provider or the auth round-trip.

`LLM_MODE=fake` routes through `FakeLlmClient`
(`modules/ai/src/main/java/org/atmosphere/ai/AiConfig.java:153-156`), which emits
deterministic simulated streaming text with no real API calls — perfect for CI on a fresh
runner with zero secrets. Supported modes per `AiConfig.configure` are `remote` / `local`
/ `fake`. Phases that require semantic judging (tool calling, RAG, governance) will swap
to `LLM_MODE=local` and add `judge:` steps with the `byte-stable` Ollama profile.

`ATMOSPHERE_AUTH_ENABLED=false` (Spring relaxes-binds `ATMOSPHERE_AUTH_ENABLED` → `atmosphere.auth.enabled`)
intentionally disables the auth gate for the pilot. Auth is enabled by default in the
sample (`application.yml` → `atmosphere.auth.enabled: true`, fail-closed), but the React
console deliberately does not pass `authToken: 'demo-token'` (commented out in
`frontend/src/App.tsx:36` for the WebTransport demo), so the default boot would return
401/500 on the console pageload. A future auth-round-trip scenario will re-enable the gate
and exercise the token handshake explicitly.

## Local reproduction

```bash
# 1. Build the runner once
git clone https://github.com/jfarcand/iphone-mirroir-mcp /tmp/mirroir-mcp
( cd /tmp/mirroir-mcp/runner && cargo build --release )
sudo cp /tmp/mirroir-mcp/runner/target/release/mirroir-run /usr/local/bin/

# 2. Install Playwright once (any node project works as a host)
mkdir -p /tmp/playwright-home && cd /tmp/playwright-home
npm init -y && npm i -D @playwright/test
npx playwright install --with-deps chromium firefox webkit

# 3. Drive the sample from the atmosphere repo root
cd /path/to/atmosphere
./mvnw -q install -pl modules/cpr,modules/spring-boot-starter -am -DskipTests
MIRROIR_PLAYWRIGHT_HOME=/tmp/playwright-home \
  MIRROIR_REPO_ROOT=$PWD \
  mirroir-run --sample samples/spring-boot-ai-chat --scenarios must-pass
```

Exit code 0 means both scenarios passed across their declared browser projects.
