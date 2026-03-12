# Atmosphere E2E Tests

End-to-end Playwright tests that verify WebSocket broadcast across multiple browser windows using the embedded Jetty chat sample.

## Prerequisites

- **JDK 21+** and **Maven** (the chat server is a Java/Jetty application)
- **Node.js 18+**
- The `atmosphere-runtime` module must be built first: `cd .. && ./mvnw install -pl modules/cpr -DskipTests`
- The embedded-jetty-websocket-chat sample must compile: `cd .. && ./mvnw compile -pl samples/embedded-jetty-websocket-chat`

## Setup

```bash
npm install
npx playwright install chromium
```

## Running Tests

The Playwright config automatically starts the chat server via `mvn exec:java`. Just run:

```bash
# Headless (CI-friendly)
npm test

# Headed (watch the browsers)
npm run test:headed
```

If the chat server is already running on `http://localhost:8080`, Playwright will reuse it (set `CI=true` to force a fresh start).

## What the Tests Cover

1. **Three browsers receive broadcast message** -- Opens 3 browser contexts, has one send a message, and verifies all 3 display it.
2. **Message ordering is preserved across browsers** -- Sends 3 rapid messages and verifies they arrive in order on all browsers.
3. **Disconnect and reconnect** -- Closes a browser page, reopens it, and verifies the reconnected client receives new messages.

## Project Structure

```
e2e-tests/
├── package.json
├── playwright.config.ts    # Chromium config + webServer for embedded Jetty chat
├── tsconfig.json
├── tests/
│   └── websocket-broadcast.spec.ts
└── README.md
```
