# atmosphere.js -- TypeScript Client

TypeScript client for the Atmosphere Framework. Supports WebSocket, SSE, and Long-Polling transports with first-class React, Vue, and Svelte hooks.

## npm Coordinates

```bash
npm install atmosphere.js
```

## Quick Start

```typescript
import { atmosphere } from 'atmosphere.js';

const subscription = await atmosphere.subscribe({
  url: 'http://localhost:8080/chat',
  transport: 'websocket',
}, {
  message: (response) => console.log('Received:', response.responseBody),
  open: (response) => console.log('Connected via:', response.transport),
  close: (response) => console.log('Connection closed'),
  error: (error) => console.error('Error:', error),
});

subscription.push({ user: 'John', message: 'Hello World' });
```

## React Hooks

All hooks require an `<AtmosphereProvider>` ancestor.

```tsx
import { AtmosphereProvider } from 'atmosphere.js/react';

function App() {
  return (
    <AtmosphereProvider>
      <Chat />
    </AtmosphereProvider>
  );
}
```

### useAtmosphere

Subscribe to an endpoint:

```tsx
import { useAtmosphere } from 'atmosphere.js/react';

function Chat() {
  const { data, state, push } = useAtmosphere<Message>({
    request: { url: '/chat', transport: 'websocket' },
  });

  return state === 'connected'
    ? <button onClick={() => push({ text: 'Hello' })}>Send</button>
    : <p>Connecting...</p>;
}
```

### useRoom

Join a room with presence:

```tsx
import { useRoom } from 'atmosphere.js/react';

function ChatRoom() {
  const { joined, members, messages, broadcast } = useRoom<ChatMessage>({
    request: { url: '/atmosphere/room', transport: 'websocket' },
    room: 'lobby',
    member: { id: 'user-1' },
  });

  return (
    <div>
      <p>{members.length} online</p>
      {messages.map((m, i) => <div key={i}>{m.member.id}: {m.data.text}</div>)}
      <button onClick={() => broadcast({ text: 'Hi' })}>Send</button>
    </div>
  );
}
```

### usePresence

Lightweight presence tracking:

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

### useStreaming

AI/LLM text streaming:

```tsx
import { useStreaming } from 'atmosphere.js/react';

function AiChat() {
  const { fullText, isStreaming, stats, routing, send } = useStreaming({
    request: { url: '/ai/chat', transport: 'websocket' },
  });

  return (
    <div>
      <button onClick={() => send('Explain WebSockets')} disabled={isStreaming}>Ask</button>
      <p>{fullText}</p>
      {stats && <small>{stats.totalStreamingTexts} streaming texts</small>}
    </div>
  );
}
```

## Vue Composables

Vue composables do not require a provider -- they create or accept an Atmosphere instance directly.

### useAtmosphere

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
  <button @click="push({ text: 'Hello!' })">Send</button>
</template>
```

### useRoom

```vue
<script setup lang="ts">
import { useRoom } from 'atmosphere.js/vue';

const { members, messages, broadcast } = useRoom<ChatMessage>(
  { url: '/atmosphere/room', transport: 'websocket' },
  'lobby',
  { id: 'user-1' },
);
</script>
```

### usePresence

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

### useStreaming

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

## Svelte Stores

Svelte integrations use the store pattern -- each factory returns a Svelte-compatible readable store plus action functions.

### createAtmosphereStore

```svelte
<script>
  import { createAtmosphereStore } from 'atmosphere.js/svelte';

  const { store: chat, push } = createAtmosphereStore({ url: '/chat', transport: 'websocket' });
</script>

<p>Status: {$chat.state}</p>
<button on:click={() => push({ text: 'Hello!' })}>Send</button>
```

### createRoomStore

```svelte
<script>
  import { createRoomStore } from 'atmosphere.js/svelte';

  const { store: lobby, broadcast } = createRoomStore(
    { url: '/atmosphere/room', transport: 'websocket' },
    'lobby',
    { id: 'user-1' },
  );
</script>

<p>Members: {$lobby.members.map(m => m.id).join(', ')}</p>
<button on:click={() => broadcast({ text: 'Hello!' })}>Broadcast</button>
```

### createPresenceStore

```svelte
<script>
  import { createPresenceStore } from 'atmosphere.js/svelte';

  const presence = createPresenceStore(
    { url: '/atmosphere/room', transport: 'websocket' },
    'lobby',
    { id: 'user-1' },
  );
</script>

<p>{$presence.count} users online</p>
```

### createStreamingStore

```svelte
<script>
  import { createStreamingStore } from 'atmosphere.js/svelte';

  const { store, send, reset } = createStreamingStore({
    url: '/ai/chat',
    transport: 'websocket',
  });
</script>

<button on:click={() => send('What is Atmosphere?')}>Ask</button>
<p>{$store.fullText}</p>
{#if $store.isStreaming}<span>Generating...</span>{/if}
```

## AI Streaming Wire Protocol

The server sends JSON messages using the Atmosphere AI streaming protocol:

```json
{"type": "streaming-text",    "data": "Hello",      "sessionId": "abc-123", "seq": 1}
{"type": "progress", "data": "Thinking...", "sessionId": "abc-123", "seq": 2}
{"type": "metadata", "key": "model",  "value": "gpt-4", "sessionId": "abc-123", "seq": 3}
{"type": "complete", "data": "Done",        "sessionId": "abc-123", "seq": 10}
{"type": "error",    "data": "Rate limited","sessionId": "abc-123", "seq": 11}
```

Use `subscribeStreaming` for framework-agnostic streaming, or the hooks above for React/Vue/Svelte.

## Request Options

| Option | Type | Description |
|--------|------|-------------|
| `url` | `string` | Endpoint URL |
| `transport` | `string` | `'websocket'`, `'sse'`, `'long-polling'`, `'streaming'` |
| `fallbackTransport` | `string` | Transport to use if primary fails |
| `reconnect` | `boolean` | Enable auto-reconnection |
| `reconnectInterval` | `number` | Time between reconnections (ms) |
| `maxReconnectOnClose` | `number` | Maximum reconnection attempts |
| `trackMessageLength` | `boolean` | Enable message length tracking |
| `headers` | `object` | Custom headers |
| `withCredentials` | `boolean` | Include credentials |

## Browser Compatibility

- Chrome/Edge: last 2 versions
- Firefox: last 2 versions + ESR
- Safari: last 2 versions
- Mobile Safari (iOS): last 2 versions
- Chrome Android: last 2 versions

## See Also

- [AI Integration](ai.md) -- server-side `@AiEndpoint` and `StreamingSession`
- [Rooms & Presence](rooms.md) -- server-side room management
- [wAsync Java Client](client-java.md)
- [Module README](../atmosphere.js/README.md)
