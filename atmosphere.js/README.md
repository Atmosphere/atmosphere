# atmosphere.js

Modern TypeScript client for the Atmosphere Framework - WebSocket, SSE, and Comet support for real-time web applications.

[![npm version](https://img.shields.io/npm/v/atmosphere.js)](https://www.npmjs.com/package/atmosphere.js)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.4-blue)](https://www.typescriptlang.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## Features

✨ **Modern TypeScript** - Full type safety and IntelliSense support  
🚀 **Multiple Transports** - WebSocket, SSE, Long-polling, Streaming  
🔄 **Auto-reconnection** - Intelligent reconnection with exponential backoff  
📦 **Tree-shakeable** - Import only what you need  
🎯 **Zero Runtime Dependencies** - React, Vue, and Svelte are optional peer dependencies
🔌 **Promise-based API** - Modern async/await support
🧪 **Well Tested** - Comprehensive test coverage

## Installation

```bash
npm install atmosphere.js
```

## Quick Start

```typescript
import { atmosphere } from 'atmosphere.js';

// Subscribe to an endpoint
const subscription = await atmosphere.subscribe({
  url: 'http://localhost:8080/chat',
  transport: 'websocket',
}, {
  message: (response) => {
    console.log('Received:', response.responseBody);
  },
  open: (response) => {
    console.log('Connected with transport:', response.transport);
  },
  close: (response) => {
    console.log('Connection closed');
  },
  error: (error) => {
    console.error('Error:', error);
  }
});

// Send a message
subscription.push({ 
  user: 'John', 
  message: 'Hello World' 
});

// Close the connection
await subscription.close();
```

## API Reference

### Creating an Atmosphere Instance

```typescript
import { Atmosphere } from 'atmosphere.js';

const atmosphere = new Atmosphere({
  logLevel: 'info',
  defaultTransport: 'websocket',
  fallbackTransport: 'long-polling'
});
```

### Subscribe to an Endpoint

```typescript
const subscription = await atmosphere.subscribe(
  {
    url: 'http://localhost:8080/chat',
    transport: 'websocket',
    reconnect: true,
    reconnectInterval: 5000,
    maxReconnectOnClose: 10,
    trackMessageLength: false,
    headers: {
      'Authorization': 'Bearer token123'
    }
  },
  {
    message: (response) => { /* handle message */ },
    open: (response) => { /* handle open */ },
    close: (response) => { /* handle close */ },
    error: (error) => { /* handle error */ },
    reconnect: (request, response) => { /* handle reconnect */ }
  }
);
```

### Request Options

```typescript
interface AtmosphereRequest {
  url: string;                      // Endpoint URL
  transport: TransportType;         // 'websocket' | 'sse' | 'long-polling' | 'streaming' | 'jsonp'
  fallbackTransport?: TransportType;// Transport to use if primary fails
  contentType?: string;             // Content-Type header
  timeout?: number;                 // Request timeout in milliseconds
  reconnect?: boolean;              // Enable auto-reconnection
  reconnectInterval?: number;       // Time between reconnections (ms)
  maxReconnectOnClose?: number;     // Maximum reconnection attempts
  trackMessageLength?: boolean;     // Enable message length tracking
  messageDelimiter?: string;        // Delimiter for split messages
  enableProtocol?: boolean;         // Enable Atmosphere protocol
  headers?: Record<string, string>; // Custom headers
  withCredentials?: boolean;        // Include credentials
}
```

### Subscription Methods

```typescript
// Send a message
subscription.push('Hello');                    // String
subscription.push({ message: 'Hello' });       // Object (auto-stringified)
subscription.push(new ArrayBuffer(8));         // Binary data

// Get current state
const state = subscription.state; // 'disconnected' | 'connecting' | 'connected' | 'reconnecting' | 'closed' | 'error'

// Close the subscription
await subscription.close();

// Event emitter style
subscription.on('custom-event', (data) => {
  console.log(data);
});

subscription.off('custom-event', handler);
```

## Examples

### Basic WebSocket Connection

```typescript
import { atmosphere } from 'atmosphere.js';

const subscription = await atmosphere.subscribe({
  url: 'ws://localhost:8080/chat',
  transport: 'websocket'
}, {
  message: (response) => {
    console.log(response.responseBody);
  }
});

subscription.push('Hello server!');
```

### With Reconnection

```typescript
const subscription = await atmosphere.subscribe({
  url: 'http://localhost:8080/chat',
  transport: 'websocket',
  reconnect: true,
  reconnectInterval: 3000,
  maxReconnectOnClose: 10
}, {
  message: (response) => {
    console.log('Message:', response.responseBody);
  },
  reconnect: (request, response) => {
    console.log('Reconnecting... Attempt:', request);
  },
  open: (response) => {
    console.log('Connection established');
  }
});
```

### Custom Headers and Authentication

```typescript
const subscription = await atmosphere.subscribe({
  url: 'http://localhost:8080/secure-chat',
  transport: 'websocket',
  headers: {
    'Authorization': `Bearer ${authToken}`,
    'X-Custom-Header': 'value'
  },
  withCredentials: true
}, {
  message: (response) => {
    console.log(response.responseBody);
  }
});
```

### Type-Safe Messages

```typescript
interface ChatMessage {
  user: string;
  message: string;
  timestamp: number;
}

const subscription = await atmosphere.subscribe<ChatMessage>({
  url: 'http://localhost:8080/chat',
  transport: 'websocket'
}, {
  message: (response) => {
    // response.responseBody is typed as ChatMessage
    const msg = response.responseBody;
    console.log(`${msg.user}: ${msg.message}`);
  }
});
```

### Multiple Subscriptions

```typescript
const chat = await atmosphere.subscribe({
  url: 'http://localhost:8080/chat',
  transport: 'websocket'
}, {
  message: (response) => console.log('Chat:', response.responseBody)
});

const notifications = await atmosphere.subscribe({
  url: 'http://localhost:8080/notifications',
  transport: 'websocket'
}, {
  message: (response) => console.log('Notification:', response.responseBody)
});

// Close all subscriptions
await atmosphere.closeAll();
```

### Error Handling

```typescript
try {
  const subscription = await atmosphere.subscribe({
    url: 'http://localhost:8080/chat',
    transport: 'websocket'
  }, {
    error: (error) => {
      console.error('Connection error:', error);
    },
    close: (response) => {
      console.log('Connection closed:', response.reasonPhrase);
    }
  });
} catch (error) {
  console.error('Failed to connect:', error);
}
```

## Framework Hooks

atmosphere.js ships with first-class integrations for React, Vue, and Svelte. Each framework
integration is a separate entry point that can be imported independently and is fully
tree-shakeable.

### React

Import from `atmosphere.js/react`. All hooks require an `<AtmosphereProvider>` ancestor.

#### Setup

```tsx
import { AtmosphereProvider } from 'atmosphere.js/react';

function App() {
  return (
    <AtmosphereProvider config={{ logLevel: 'info' }}>
      <Chat />
    </AtmosphereProvider>
  );
}
```

#### `useAtmosphere<T>` -- subscribe to an endpoint

```tsx
import { useAtmosphere } from 'atmosphere.js/react';

function Chat() {
  const { data, state, push } = useAtmosphere<ChatMessage>({
    request: { url: '/chat', transport: 'websocket' },
  });

  return (
    <div>
      <p>Status: {state}</p>
      <p>Last message: {JSON.stringify(data)}</p>
      <button onClick={() => push({ text: 'Hello!' })}>Send</button>
    </div>
  );
}
```

Returns `{ subscription, state, data, error, push }`.

#### `useRoom<T>` -- join a room with presence

```tsx
import { useRoom } from 'atmosphere.js/react';

function Lobby() {
  const { joined, members, messages, broadcast, sendTo } = useRoom<ChatMessage>({
    request: { url: '/atmosphere/room', transport: 'websocket' },
    room: 'lobby',
    member: { id: 'user-1' },
  });

  return (
    <div>
      <p>Members: {members.map(m => m.id).join(', ')}</p>
      <button onClick={() => broadcast({ text: 'Hello room!' })}>Broadcast</button>
      <button onClick={() => sendTo('user-2', { text: 'Hey' })}>DM user-2</button>
    </div>
  );
}
```

Returns `{ joined, members, messages, broadcast, sendTo, error }`.

#### `usePresence` -- lightweight presence tracking

```tsx
import { usePresence } from 'atmosphere.js/react';

function OnlineUsers() {
  const { members, count, isOnline } = usePresence({
    request: { url: '/atmosphere/room', transport: 'websocket' },
    room: 'lobby',
    member: { id: currentUser.id },
  });

  return <p>{count} users online. Alice is {isOnline('alice') ? 'here' : 'away'}.</p>;
}
```

Returns `{ joined, members, count, isOnline }`.

#### `useStreaming` -- AI/LLM token streaming

```tsx
import { useStreaming } from 'atmosphere.js/react';

function AiChat() {
  const { fullText, isStreaming, send, reset, progress, metadata, error } = useStreaming({
    request: { url: '/ai/chat', transport: 'websocket' },
  });

  return (
    <div>
      <button onClick={() => send('What is Atmosphere?')}>Ask</button>
      <button onClick={reset}>Clear</button>
      <p>{fullText}</p>
      {isStreaming && <span>{progress ?? 'Generating...'}</span>}
    </div>
  );
}
```

Returns `{ fullText, tokens, isStreaming, progress, metadata, error, send, reset, close }`.

### Vue

Import from `atmosphere.js/vue`. Vue composables do not require a provider -- they create
or accept an Atmosphere instance directly.

#### `useAtmosphere<T>`

```vue
<script setup lang="ts">
import { useAtmosphere } from 'atmosphere.js/vue';

const { data, state, push } = useAtmosphere<ChatMessage>({
  url: '/chat',
  transport: 'websocket',
});
</script>

<template>
  <p>Status: {{ state }}</p>
  <p>{{ data }}</p>
  <button @click="push({ text: 'Hello!' })">Send</button>
</template>
```

#### `useRoom<T>`

```vue
<script setup lang="ts">
import { useRoom } from 'atmosphere.js/vue';

const { members, messages, broadcast, sendTo } = useRoom<ChatMessage>(
  { url: '/atmosphere/room', transport: 'websocket' },
  'lobby',
  { id: 'user-1' },
);
</script>
```

#### `usePresence`

```vue
<script setup lang="ts">
import { usePresence } from 'atmosphere.js/vue';

const { members, count, isOnline } = usePresence(
  { url: '/atmosphere/room', transport: 'websocket' },
  'lobby',
  { id: currentUser.id },
);
</script>
```

#### `useStreaming`

```vue
<script setup lang="ts">
import { useStreaming } from 'atmosphere.js/vue';

const { fullText, isStreaming, send, reset } = useStreaming({
  url: '/ai/chat',
  transport: 'websocket',
});
</script>

<template>
  <button @click="send('What is Atmosphere?')">Ask</button>
  <p>{{ fullText }}</p>
  <span v-if="isStreaming">Generating...</span>
</template>
```

### Svelte

Import from `atmosphere.js/svelte`. Svelte integrations use the store pattern -- each
factory returns a Svelte-compatible readable store plus action functions.

#### `createAtmosphereStore<T>`

```svelte
<script>
  import { createAtmosphereStore } from 'atmosphere.js/svelte';

  const { store: chat, push } = createAtmosphereStore({ url: '/chat', transport: 'websocket' });
  // $chat.state, $chat.data, $chat.error
</script>

<p>Status: {$chat.state}</p>
<p>{JSON.stringify($chat.data)}</p>
<button on:click={() => push({ text: 'Hello!' })}>Send</button>
```

#### `createRoomStore<T>`

```svelte
<script>
  import { createRoomStore } from 'atmosphere.js/svelte';

  const { store: lobby, broadcast, sendTo } = createRoomStore(
    { url: '/atmosphere/room', transport: 'websocket' },
    'lobby',
    { id: 'user-1' },
  );
  // $lobby.joined, $lobby.members, $lobby.messages
</script>

<p>Members: {$lobby.members.map(m => m.id).join(', ')}</p>
<button on:click={() => broadcast({ text: 'Hello!' })}>Broadcast</button>
```

#### `createPresenceStore`

```svelte
<script>
  import { createPresenceStore } from 'atmosphere.js/svelte';

  const presence = createPresenceStore(
    { url: '/atmosphere/room', transport: 'websocket' },
    'lobby',
    { id: 'user-1' },
  );
  // $presence.joined, $presence.members, $presence.count
</script>

<p>{$presence.count} users online</p>
```

#### `createStreamingStore`

```svelte
<script>
  import { createStreamingStore } from 'atmosphere.js/svelte';

  const { store, send, reset } = createStreamingStore({
    url: '/ai/chat',
    transport: 'websocket',
  });
  // $store.fullText, $store.isStreaming, $store.tokens, $store.progress
</script>

<button on:click={() => send('What is Atmosphere?')}>Ask</button>
<p>{$store.fullText}</p>
{#if $store.isStreaming}<span>Generating...</span>{/if}
```

---

## Rooms and Presence

The room system provides a high-level API for joining named rooms, broadcasting messages,
sending direct messages, and tracking who is online. It works with the server-side
`RoomManager` and `RoomInterceptor`.

### Framework-agnostic usage

```typescript
import { Atmosphere } from 'atmosphere.js';
import { AtmosphereRooms } from 'atmosphere.js'; // or from the internal module

const atmosphere = new Atmosphere();
const rooms = new AtmosphereRooms(atmosphere, {
  url: 'ws://localhost:8080/atmosphere/room',
  transport: 'websocket',
});

// Join a room
const lobby = await rooms.join('lobby', { id: 'user-1' }, {
  joined: (roomName, memberList) => {
    console.log(`Joined ${roomName}, members:`, memberList);
  },
  message: (data, sender) => {
    console.log(`${sender.id}: ${data}`);
  },
  join: (event) => {
    console.log(`${event.member.id} joined at ${event.timestamp}`);
  },
  leave: (event) => {
    console.log(`${event.member.id} left`);
  },
  error: (err) => {
    console.error('Room error:', err);
  },
});

// Broadcast to all members
lobby.broadcast({ text: 'Hello everyone!' });

// Direct message to a specific member
lobby.sendTo('user-2', { text: 'Private message' });

// Check current members
console.log('Members:', [...lobby.members.values()]);

// Leave the room
lobby.leave();

// Or leave all rooms and close the connection
await rooms.leaveAll();
```

### RoomMember

Each member has a required `id` field and an optional `info` record for metadata:

```typescript
interface RoomMember {
  readonly id: string;
  readonly info?: Record<string, unknown>;
}
```

### Presence events

Presence events are delivered as `PresenceEvent` objects:

```typescript
interface PresenceEvent {
  readonly type: 'join' | 'leave';
  readonly room: string;
  readonly member: RoomMember;
  readonly timestamp: number;
}
```

For framework-specific usage, see `useRoom` / `usePresence` (React), `useRoom` / `usePresence` (Vue), and `createRoomStore` / `createPresenceStore` (Svelte) in the [Framework Hooks](#framework-hooks) section above.

---

## AI Streaming

atmosphere.js includes a streaming decoder and subscription helper for AI/LLM endpoints
that use the Atmosphere AI streaming wire protocol (server-side `@AiEndpoint` and
`DefaultStreamingSession`).

### Wire protocol

Each message from the server is a JSON object with `type`, `sessionId`, and `seq` fields:

```json
{"type": "token",    "data": "Hello",        "sessionId": "abc-123", "seq": 1}
{"type": "progress", "data": "Thinking...",   "sessionId": "abc-123", "seq": 2}
{"type": "metadata", "key": "model",  "value": "gpt-4", "sessionId": "abc-123", "seq": 3}
{"type": "complete", "data": "Done",          "sessionId": "abc-123", "seq": 10}
{"type": "error",    "data": "Rate limited",  "sessionId": "abc-123", "seq": 11}
```

Message types: `token`, `progress`, `complete`, `error`, `metadata`.

### `parseStreamingMessage(raw)`

Low-level decoder that parses a raw string into a `StreamingMessage`, or returns `null` if it is not a valid streaming protocol message:

```typescript
import { parseStreamingMessage } from 'atmosphere.js';

const msg = parseStreamingMessage('{"type":"token","data":"Hi","sessionId":"s1","seq":1}');
if (msg) {
  console.log(msg.type, msg.data); // "token" "Hi"
}
```

### `subscribeStreaming(atmosphere, request, handlers)`

Framework-agnostic helper that creates a subscription, parses streaming messages
automatically (with dedup via sequence numbers), and dispatches to handler callbacks:

```typescript
import { Atmosphere } from 'atmosphere.js';
import { subscribeStreaming } from 'atmosphere.js';

const atmosphere = new Atmosphere();
const handle = await subscribeStreaming(atmosphere, {
  url: '/ai/chat',
  transport: 'websocket',
}, {
  onToken: (token, seq) => process.stdout.write(token),
  onProgress: (message) => console.log('Progress:', message),
  onComplete: (summary) => console.log('\nDone!', summary),
  onError: (error) => console.error('Error:', error),
  onMetadata: (key, value) => console.log(`${key}: ${value}`),
});

// Send a prompt to start streaming
handle.send('Explain virtual threads in Java 21');

// Session ID assigned by the server
console.log('Session:', handle.sessionId);

// Close when done
await handle.close();
```

For framework-specific wrappers, see `useStreaming` (React/Vue) and `createStreamingStore` (Svelte) in the [Framework Hooks](#framework-hooks) section above.

---

## Browser Compatibility

- Chrome/Edge: Last 2 versions
- Firefox: Last 2 versions + ESR
- Safari: Last 2 versions
- Mobile Safari (iOS): Last 2 versions
- Chrome Android: Last 2 versions

## Development

```bash
# Install dependencies
npm install

# Run tests
npm test

# Run tests with UI
npm run test:ui

# Run tests with coverage
npm run test:ci

# Build
npm run build

# Development mode (watch)
npm run dev

# Type checking
npm run type-check

# Linting
npm run lint

# Format code
npm run format
```

## License

Apache License 2.0 - see [LICENSE](LICENSE) file for details

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Links

- [Atmosphere Framework](https://github.com/Atmosphere/atmosphere)
- [Documentation](https://github.com/Atmosphere/atmosphere/wiki)
- [Issues](https://github.com/Atmosphere/atmosphere/issues)

---

**Built with ❤️ by the Atmosphere team**
