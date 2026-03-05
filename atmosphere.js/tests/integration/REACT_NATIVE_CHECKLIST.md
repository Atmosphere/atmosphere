# React Native Integration Test Checklist

Manual checklist to run against a real Expo app before releasing.
Mock-only unit tests don't catch real platform compatibility issues.

## Setup
- Start the `spring-boot-ai-classroom` backend: `./mvnw spring-boot:run -pl samples/spring-boot-ai-classroom`
- Start the Expo app: `cd samples/expo-classroom && bun install && bunx expo start`
- Update `SERVER_URL` in `App.tsx` to point at your machine's LAN IP if using a physical device

## Transport Tests

- [ ] WebSocket connects on Expo Go (iOS)
- [ ] WebSocket connects on Expo Go (Android)
- [ ] Long-polling fallback works when WebSocket is blocked (e.g. via server config)
- [ ] SSE polyfill works (ReadableStream path) on Expo SDK 52+ / RN 0.76+
- [ ] SSE polyfill works (text fallback path) on Expo SDK 49 / RN 0.72 (if testable)
- [ ] Streaming transport works on Expo SDK 52+

## Lifecycle Tests

- [ ] App background -> foreground: connection resumes correctly
- [ ] Airplane mode on -> connection suspended, no crash
- [ ] Airplane mode off -> reconnects and resumes
- [ ] Switch between WiFi and cellular -> reconnects gracefully
- [ ] App killed and restarted -> fresh connection established

## Classroom Functionality

- [ ] Room selector displays all 4 rooms (Math, Code, Science, General)
- [ ] Joining a room connects to the correct endpoint
- [ ] Sending a message shows user bubble immediately
- [ ] AI response streams token-by-token in assistant bubble
- [ ] Streaming cursor (|) appears during streaming, disappears on complete
- [ ] Stats bar shows token count, elapsed time, tokens/sec after completion
- [ ] "Leave" button returns to room selector and disconnects
- [ ] Multiple devices in the same room see the same streamed response

## Engine Compatibility

- [ ] Hermes engine (default in Expo/RN) — all features work
- [ ] JSC engine (alternative) — all features work (if testable)

## Edge Cases

- [ ] Rapid room switching doesn't leave zombie connections
- [ ] Sending while offline shows no crash (send is suppressed)
- [ ] Very long AI response doesn't cause OOM or rendering lag
- [ ] Server restart mid-stream -> error shown, reconnect works
