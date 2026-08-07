# Expo pass — the React Native client

**Location:** `samples/spring-boot-ai-classroom/expo-client/` (its
`package.json` name is `expo-classroom`, which is why it is sometimes
misremembered as a top-level `samples/expo-classroom/` — that directory does not
exist).

It is not a Maven module and is not listed in `cli/samples.json`, so neither the
reactor build, `scripts/release-gate-samples.sh`, nor the Playwright suites
touch it. **This sweep is its only gate.** Treat it as its own row, not as part
of sample #25.

## Why it needs a different driver

This is a native app: Expo SDK 55 / React Native 0.83, no `react-native-web`
dependency and no `web` script in `package.json`. **chrome-devtools cannot drive
it.** Use the iOS simulator MCP (`mcp__ios-simulator__*`) — `launch_app`,
`ui_describe_all`, `ui_find_element`, `ui_tap`, `ui_type`, `screenshot`.

The same evidence discipline applies: assert the **rendered element** from
`ui_describe_all` / `ui_find_element`, not a log line and not a screenshot you
did not read.

## What it actually exercises

It consumes `atmosphere.js` via `file:../../../atmosphere.js` — the **local
build**, not the npm package. So it is the only pre-release check that the
`./react-native` export of the client library works in a real RN runtime:

- `setupReactNative({ netInfo })` — EventSource polyfill, ReadableStream
  detection, transport recommendation
- `AtmosphereProvider` — client context
- `useStreamingRN` — WebSocket connection with AppState + NetInfo awareness

## Preconditions

```bash
# 1. atmosphere.js dist must be current — the Expo client links the built output
cd atmosphere.js && npm run build

# 2. Backend: the classroom sample, booted like any other sweep sample
.claude/skills/release-sample-sweep/scripts/sweep-sample.sh start spring-boot-ai-classroom \
    --port 9125 --ready-path /atmosphere/classroom/general \
    --env LLM_MODE=local --env LLM_MODEL=qwen2.5:3b

# 3. Client deps (bun — this sample uses bun.lock, not package-lock.json)
cd samples/spring-boot-ai-classroom/expo-client && bun install
```

**The port gotcha.** `App.tsx` hardcodes `SERVER_URL`, and its README assumes
the backend on `:8080`. The sweep runs the classroom sample on **9125**, so
`SERVER_URL` must be pointed at the sweep port or the client silently fails to
connect. Edit it for the run and **revert before committing** — a stray edit to
a sample is a dirty-tree violation, and shipping the sweep port would break the
documented flow for users.

Host addressing, per the client README:

| Target | `SERVER_URL` host |
|---|---|
| iOS simulator | `localhost` |
| Android emulator | `10.0.2.2` |
| Physical device (Expo Go) | the machine's LAN IP |

## The pass

```bash
bunx expo start        # then press `i` for the iOS simulator
```

Drive with the simulator MCP:

```
open_simulator / get_booted_sim_id
launch_app
ui_describe_all        → confirm the 4 rooms render: Math, Code, Science, General
ui_tap                 → join a room
ui_type + send         → a prompt
ui_describe_all        → assert the AI reply text is present AND grew between
                         two reads (that is what proves streaming, not a final blob)
screenshot             → evidence for the report
```

### Headline assertions

| # | Assertion | How |
|---|---|---|
| 1 | Connects to the backend over WebSocket | Backend log shows the connection; the room UI leaves its connecting state |
| 2 | All four rooms render and are selectable | `ui_describe_all` lists Math / Code / Science / General |
| 3 | AI response streams **text-by-text** | Two `ui_describe_all` reads during one turn show a growing string — a single atomic message means `useStreamingRN` collapsed |
| 4 | AppState-aware suspend | Background the app (`press_home`-equivalent), return, confirm it reconnects rather than dying |
| 5 | NetInfo offline banner | Toggle the simulator's network off: the offline banner shows and sends are suppressed, not silently dropped |

Assertions 4 and 5 are the ones no other test in the repo covers — they are the
reason this pass exists. If the simulator makes the network toggle impractical,
say so and mark that row PARTIAL; do not quietly drop it.

## Teardown

```bash
# stop the Expo dev server by PID — never pkill
# revert the SERVER_URL edit
git checkout -- samples/spring-boot-ai-classroom/expo-client/App.tsx
# stop the backend
.claude/skills/release-sample-sweep/scripts/sweep-sample.sh stop spring-boot-ai-classroom
```

Confirm `git status --porcelain` is clean before moving on.

## Regression coverage

The Playwright suites cannot reach this client, so a finding here does **not**
get a `.spec.ts`. The honest options, in order of preference:

1. A unit/integration test in `atmosphere.js` covering the `./react-native`
   export path that broke (`npm test` in `atmosphere.js`, vitest).
2. If the defect is genuinely RN-runtime-only, record it in the report as
   covered-by-manual-sweep-only and say so plainly — an uncovered class named in
   the report beats a fake gate.

Do not add a Playwright project for it. A spec that cannot run is worse than a
declared gap.
