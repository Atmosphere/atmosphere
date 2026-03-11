# Expo AI Classroom

React Native / Expo client for the [AI Classroom](../README.md) backend. Demonstrates `atmosphere.js/react-native` connecting to a real Atmosphere backend with AI streaming.

## What it does

- Connects to the `spring-boot-ai-classroom` backend via WebSocket
- 4 classroom rooms: **Math**, **Code**, **Science**, **General**
- AI responses stream text-by-text using `useStreamingRN`
- AppState-aware: suspends connection when app goes to background
- NetInfo-aware: shows offline banner, suppresses sends when offline

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

2. **Configure the server URL:**

Edit `App.tsx` and set `SERVER_URL` to your machine's LAN IP if testing on a physical device:

```typescript
const SERVER_URL = 'http://192.168.1.100:8080';
```

For Android emulator, `10.0.2.2` maps to the host. For iOS simulator, `localhost` works.

3. **Install and start:**

```bash
cd samples/expo-classroom
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

The app uses three things from `atmosphere.js/react-native`:

- **`setupReactNative()`** — Called once at startup. Installs the EventSource polyfill, detects ReadableStream support, recommends transports. Pass `{ netInfo: NetInfo }` for network-aware reconnection.
- **`AtmosphereProvider`** — React context providing the Atmosphere client instance.
- **`useStreamingRN`** — Hook that manages the WebSocket connection with AppState/NetInfo awareness.

```typescript
import NetInfo from '@react-native-community/netinfo';
import { setupReactNative, AtmosphereProvider, useStreamingRN } from 'atmosphere.js/react-native';

setupReactNative({ netInfo: NetInfo });

function Classroom({ room }) {
  const { fullText, isStreaming, isConnected, send, reset } = useStreamingRN({
    request: {
      url: `http://your-server:8080/atmosphere/classroom/${room}`,
      transport: 'websocket',
    },
  });
  // ...
}
```

## Transport Compatibility

| Transport | Status |
|-----------|--------|
| WebSocket | Full support (primary) |
| Long-Polling | Full support (fallback) |
| SSE | Via polyfill (ReadableStream on RN 0.73+) |
| Streaming | Requires ReadableStream (RN 0.73+) |
