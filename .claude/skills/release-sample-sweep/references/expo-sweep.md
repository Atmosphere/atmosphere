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

This is a native app: Expo SDK 57 / React Native 0.86, no `react-native-web`
dependency and no `web` script in `package.json`. **chrome-devtools cannot drive
it.** Two drivers split the work:

| Job | Driver |
|---|---|
| Read the screen — `ui_describe_all`, `ui_find_element`, `ui_view`, `screenshot`, `launch_app`, `open_url` | iOS simulator MCP (`mcp__ios-simulator__*`, backed by idb) |
| Touch and type — tap a room, type a prompt, Send, Leave | **Maestro** (`~/.maestro/bin/maestro`, 2.10.0; not on `PATH` by default) |

**Why not idb for input.** On Xcode 27 the idb MCP's `ui_tap` / `ui_type` fail with
`SimulatorKit is required for HID interactions`: idb_companion 1.1.8 (2022) looks for
`SimulatorKit.framework` under `Contents/Developer/Library/PrivateFrameworks`, and
Xcode 27 moved it to `Contents/SharedFrameworks`. A shadow `DEVELOPER_DIR` only makes
the companion report "hid succeeded" while no touch arrives. Reads still work. On
Xcode 26 and older the MCP's `ui_tap` / `ui_type` remain usable; check with
`xcodebuild -version` before choosing.

The same evidence discipline applies: assert the **rendered element** from
`ui_describe_all` / `ui_find_element` or a Maestro `extendedWaitUntil: visible:`,
not a log line and not a screenshot you did not read.

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
    --port 9125 --ready-path /atmosphere/console/ \
    --env LLM_MODE=local --env LLM_MODEL=qwen2.5:3b

# 3. Client deps (bun — this sample uses bun.lock, not package-lock.json)
cd samples/spring-boot-ai-classroom/expo-client && bun install

# 4. The SDK must match the Expo Go on the simulator. Expo Go runs exactly one SDK,
#    and the App Store build is always the latest; a mismatch fails at open with
#    "Project is incompatible with this version of Expo Go".
grep '"expo"' package.json                                   # the sample's SDK
xcrun simctl listapps booted | grep -A12 host.exp.Exponent | grep CFBundleShortVersionString
bunx expo install --check                                    # deps match that SDK
```

If Expo Go is ahead of the sample, the sample is behind: upgrade it
(`expo` to the new SDK, then `bunx expo install --fix`, then `bunx tsc --noEmit -p .`
and `bunx expo export --platform ios`) and file it, rather than side-installing an
old Expo Go from github.com/expo/expo-go-releases and sweeping a client no user
can open.

**The port gotcha.** The sweep runs the classroom sample on **9125** while the
client defaults to `:8080`, so the client must be pointed at the sweep port or it
silently fails to connect. Since 4.0.71 that is an environment override, not a
source edit:

```bash
EXPO_PUBLIC_SERVER_URL=http://localhost:9125 bunx expo start --clear
```

**`--clear` is not optional here.** Expo inlines `EXPO_PUBLIC_*` at bundle time
and Metro's transform cache does not invalidate when the variable changes: a
rebuild reuses the previously baked-in URL, so the app connects to the *old*
address and the failure looks like a client bug. Verified 2026-09-01 by exporting
twice — the second bundle still carried the first run's URL until `--clear`.

(Before 4.0.71 this said to edit `SERVER_URL` in `App.tsx` and revert before
committing. That is how a sweep port reaches a user: forget the revert and the
sample ships pointing at 9125.)

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

Drive the input with Maestro and read with the simulator MCP. The flow below
joined Math, sent a prompt and left on Xcode 27 / iOS 27 with Expo Go 57.0.9
(2026-10-14). Two things it encodes on purpose:

- **Selectors are regexes for the room tiles.** React Native merges a touchable's
  children into one accessibility label, so the Math tile reads `π, Math`, not
  `Math`; `tapOn: "Math"` finds nothing. Buttons with a single label (`Send`,
  `Queue`, `Leave`) match exactly, and the prompt box matches its placeholder.
- **`stopApp` first.** Opening the same `exp://` URL in a running Expo Go keeps
  the bundle it already has, including a previous run's `EXPO_PUBLIC_SERVER_URL`,
  and the room then reports "Server unreachable".

```yaml
# classroom.yaml — run: ~/.maestro/bin/maestro --device <booted-udid> test classroom.yaml
# openLink names the Metro port of `bunx expo start --port 8141`.
appId: host.exp.Exponent
---
- stopApp
- openLink: exp://127.0.0.1:8141
- extendedWaitUntil:
    visible: ".*Math"
    timeout: 90000
- tapOn: ".*Math"
- extendedWaitUntil:
    visible: "Send"
    timeout: 30000
- tapOn: "Ask a math question..."
- inputText: "What is 7 times 8?"
- tapOn: "Send"
- extendedWaitUntil:
    visible: "What is 7 times 8?"
    timeout: 20000
- takeScreenshot: classroom-after-send
- tapOn: "Leave"
- extendedWaitUntil:
    visible: ".*Science"
    timeout: 20000
```

`Send` reads `Queue` while the stream is not open — a wait on `Send` that times out
means the room never connected, not that the selector is wrong.

Between Send and Leave, read the reply with the MCP: two `ui_describe_all` reads
during the turn must show the AI text present AND grown — that is what proves
streaming, not a final blob. `maestro hierarchy` prints the same tree when the
MCP is unavailable. The backend log line `Classroom prompt in room 'math'` confirms
the prompt reached the room it was typed in.

### Headline assertions

| # | Assertion | How |
|---|---|---|
| 1 | Connects to the backend over WebSocket | Backend log shows the connection; the room UI leaves its connecting state |
| 2 | All four rooms render and are selectable | `ui_describe_all` lists Math / Code / Science / General |
| 3 | AI response streams **text-by-text** | Two `ui_describe_all` reads during one turn show a growing string — a single atomic message means `useStreamingRN` collapsed |
| 4 | Stream survives backgrounding | Background the app (`press_home`-equivalent), return, confirm the stream is still usable. The sample uses the `useStreamingRN` default `backgroundBehavior: 'keep-alive'`, so the server should log no disconnect; the `suspend`/`disconnect` wiring is unit-tested in `use-streaming-rn-app-state.test.ts` |
| 5 | NetInfo offline banner | **Do not drive this one.** See below — record it PARTIAL against its unit coverage |

Assertion 4 is the only one that exercises a real OS background/foreground
cycle — it is the reason this pass exists.

**Assertion 5 is off-limits to the sweep.** The simulator has no network of its
own: it rides the host's. Taking it offline means turning off the machine's
Wi-Fi, which severs every session the maintainer has open — remote shells, other
agent sessions, calls, downloads — and an interrupted sweep can strand the
machine offline indefinitely. That happened on 2026-08-23 and it is never
acceptable. **Never run `networksetup`, `ifconfig down`, or any other host
network command.** Record assertion 5 as PARTIAL, name the reason, and cite the
unit coverage that does gate it:
`tests/unit/react-native/use-streaming-rn-offline-send.test.ts` and
`tests/unit/hooks/use-offline-queue.test.ts`. If a real offline drill is ever
needed, ask the maintainer to perform it at a time of their choosing.

## Teardown

```bash
# stop the Expo dev server by PID — never pkill
# revert the SERVER_URL edit
git checkout -- samples/spring-boot-ai-classroom/expo-client/App.tsx
# stop the backend
.claude/skills/release-sample-sweep/scripts/sweep-sample.sh stop spring-boot-ai-classroom --port 9125
```

The browser lane may have its own spring-boot-ai-classroom up at the same time
on another port; `--port` stops this lane's instance only.

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
