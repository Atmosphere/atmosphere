# Expo AI Classroom

React Native / Expo client for the [AI Classroom](../README.md) backend. Demonstrates `atmosphere.js/react-native` connecting to a real Atmosphere backend with AI streaming.

## What it does

- Connects to the `spring-boot-ai-classroom` backend via WebSocket
- 4 classroom rooms: **Math**, **Code**, **Science**, **General**
- AI responses stream text-by-text using `useStreamingRN`
- AppState-aware: suspends connection when app goes to background
- Connection-aware: a question asked while the stream is down is queued and
  sent on reconnect — never dropped (see [Offline behaviour](#offline-behaviour))

## Prerequisites

- [Bun](https://bun.sh/) 1.0+
- Expo CLI (comes with `bunx expo`)
- The `spring-boot-ai-classroom` backend running on your machine
- [Embacle](https://github.com/dravr-ai/dravr-embacle) (recommended) — turns your Claude Code, Copilot, or Cursor license into an LLM provider

## Running

1. **Start the backend with Embacle:**

```bash
# Start Embacle first (see https://github.com/dravr-ai/dravr-embacle)
# Then from the atmosphere root:
LLM_BASE_URL=http://localhost:3000/v1 LLM_API_KEY=embacle LLM_MODEL=copilot:claude-sonnet-4.6 \
  ./mvnw spring-boot:run -pl samples/spring-boot-ai-classroom
```

The server starts on `http://localhost:8080`. Without Embacle or an API key, it runs in demo mode with simulated responses.

2. **Configure the server URL** (only if it is not the default):

Set `EXPO_PUBLIC_SERVER_URL` — no source edit needed:

```bash
# physical device in Expo Go: your machine's LAN IP
EXPO_PUBLIC_SERVER_URL=http://192.168.1.100:8080 bunx expo start

# a server on a non-default port
EXPO_PUBLIC_SERVER_URL=http://localhost:9125 bunx expo start
```

Without it the platform defaults apply: `10.0.2.2` on the Android emulator (which
maps to the host), `localhost` on the iOS simulator.

> **Pass `--clear` when you change this.** Expo inlines `EXPO_PUBLIC_*` at bundle
> time and Metro's transform cache does **not** invalidate when the variable
> changes — a rebuild silently reuses the previously baked-in URL, so the app
> connects to the old address and looks like a client bug. Use
> `bunx expo start --clear` (or `expo export --clear`) after changing it.

3. **Install and start:**

```bash
cd samples/spring-boot-ai-classroom/expo-client
bun install
bunx expo start
```

4. **Open in Expo Go** on your phone, or press `i` for iOS simulator / `a` for Android emulator.

## Rooms

| Room | Persona | Color |
|------|---------|-------|
| Math | Mathematics tutor | Light Gold |
| Code | Programming mentor | Gold |
| Science | Science educator | Warm Gold |
| General | General assistant | Dark Gold |

## How it works

The app uses four things from `atmosphere.js/react-native`:

- **`setupReactNative()`** — Called once at startup. Installs the EventSource polyfill, detects ReadableStream support, recommends transports. Pass `{ netInfo: NetInfo }` for network-aware reconnection.
- **`AtmosphereProvider`** — React context providing the Atmosphere client instance.
- **`useStreamingRN`** — Hook that manages the WebSocket connection with AppState/NetInfo awareness.
- **`useOfflineQueue`** — Holds questions typed while the stream is down; the transport flushes it on reconnect.

```typescript
import NetInfo from '@react-native-community/netinfo';
import {
  setupReactNative, AtmosphereProvider, useStreamingRN, useOfflineQueue,
} from 'atmosphere.js/react-native';

setupReactNative({ netInfo: NetInfo });

function Classroom({ room }) {
  const offline = useOfflineQueue({ maxSize: 25 });
  const { fullText, isStreaming, canSend, send, reset } = useStreamingRN({
    request: {
      // Each room is a path segment — the backend routes on {room}.
      url: `http://your-server:8080/atmosphere/classroom/${room}`,
      transport: 'websocket',
      offlineQueue: offline.queue,
    },
  });
  // ...
}
```

## Offline behaviour

`send()` reports what happened to every message, so nothing disappears:

| Outcome | When | What the UI does |
|---|---|---|
| `sent` | `canSend` is true | Normal user bubble, answer streams in |
| `queued` | Stream unusable, `offlineQueue` configured | Dashed "Queued · will send on reconnect" bubble; flushed automatically on reopen |
| `rejected` | Stream unusable, no queue configured | Error banner, text stays in the input box |

Two flags, deliberately not the same thing:

- **`isConnected`** — NetInfo only: does the handset have a network. With the
  server stopped but the phone on Wi-Fi, this stays `true`.
- **`canSend`** — device reachable **and** stream open. This is what the Send
  button and the banner consult; the button reads "Queue" when it is false.

To see it: start the app, stop the backend, and ask a question. The badge
flips to reconnecting, the banner explains the server is unreachable, the
button says "Queue", and the question parks itself until you restart the
server.

## Transport Compatibility

| Transport | Status |
|-----------|--------|
| WebSocket | Full support (primary) |
| Long-Polling | Full support (fallback) |
| SSE | Via polyfill (ReadableStream on RN 0.73+) |
| Streaming | Requires ReadableStream (RN 0.73+) |
