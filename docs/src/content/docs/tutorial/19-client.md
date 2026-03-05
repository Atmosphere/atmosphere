---
title: "Chapter 19: atmosphere.js Client"
description: "TypeScript client library with React, Vue, and Svelte integrations for Atmosphere"
---

# Chapter 19: atmosphere.js Client

Every server needs a client. In this chapter we explore `atmosphere.js` -- the official TypeScript client for the Atmosphere Framework. It provides a transport-agnostic API that works in the browser and Node.js, with first-class hooks for React, Vue, and Svelte. We will start with the core API, build up to framework-specific integrations, and finish with the AI streaming protocol.

## What You Will Learn

- The core `atmosphere.subscribe()` API and its request options.
- All callback events and how to handle them.
- Sending data with `subscription.push()`.
- React hooks: `AtmosphereProvider`, `useAtmosphere`, `useRoom`, `usePresence`, `useStreaming`.
- Vue composables: `useAtmosphere`, `useRoom`, `usePresence`, `useStreaming`.
- Svelte stores: `createAtmosphereStore`, `createRoomStore`, `createPresenceStore`, `createStreamingStore`.
- The AI streaming wire protocol.

## Installation

```bash
npm install atmosphere.js
```

The package ships ESM, CommonJS, and TypeScript declarations. Tree-shaking ensures that if you only import the core API, none of the React/Vue/Svelte code is included in your bundle.

---

## Core API

The core API is framework-agnostic. It works in any JavaScript environment that supports `WebSocket`, `EventSource`, or `XMLHttpRequest`.

### Subscribing to an Endpoint

```typescript
import { atmosphere } from 'atmosphere.js';

const subscription = await atmosphere.subscribe(
  {
    url: 'http://localhost:8080/chat',
    transport: 'websocket',
    fallbackTransport: 'long-polling',
  },
  {
    open: (response) => {
      console.log('Connected via', response.transport);
    },
    message: (response) => {
      const body = response.responseBody;
      console.log('Received:', body);
    },
    close: (response) => {
      console.log('Connection closed');
    },
    error: (response) => {
      console.error('Error:', response);
    },
    reconnect: (request, response) => {
      console.log('Reconnecting...');
    },
    transportFailure: (errorMsg, request) => {
      console.warn('Transport failed:', errorMsg);
      console.log('Falling back to:', request.fallbackTransport);
    },
  }
);
```

The `subscribe()` call returns a `Subscription` object. The first argument is the **request** configuration. The second argument is a **callbacks** object.

### Sending Data

Use `subscription.push()` to send data to the server:

```typescript
// Send a string
subscription.push('Hello, World!');

// Send a JSON object (automatically serialized)
subscription.push({ user: 'Alice', text: 'Hello everyone!' });

// Send with specific content type
subscription.push(JSON.stringify({ user: 'Alice', text: 'Hello' }));
```

### Unsubscribing

```typescript
// Close the connection gracefully
subscription.unsubscribe();
```

---

## Request Options

The request object configures how the client connects, reconnects, and communicates with the server.

| Option | Type | Default | Description |
|---|---|---|---|
| `url` | `string` | -- | The server endpoint URL. Required. |
| `transport` | `string` | `'websocket'` | Primary transport: `'websocket'`, `'sse'`, `'long-polling'`, `'streaming'`. |
| `fallbackTransport` | `string` | `'long-polling'` | Transport to use if the primary fails. Set to `''` to disable fallback. |
| `reconnect` | `boolean` | `true` | Whether to automatically reconnect after a disconnect. |
| `reconnectInterval` | `number` | `0` | Milliseconds to wait between reconnection attempts. `0` means reconnect immediately. |
| `maxReconnectOnClose` | `number` | `5` | Maximum number of reconnection attempts before giving up. |
| `trackMessageLength` | `boolean` | `false` | Enable message-length tracking for transports that aggregate multiple messages (streaming, long-polling). |
| `headers` | `object` | `{}` | Custom HTTP headers to send with the request. Not all transports support all headers. |
| `withCredentials` | `boolean` | `false` | Whether to include cookies and auth headers in cross-origin requests. |

### Request Examples

**WebSocket with aggressive reconnection:**

```typescript
{
  url: '/chat',
  transport: 'websocket',
  reconnect: true,
  reconnectInterval: 1000,
  maxReconnectOnClose: 20,
}
```

**SSE with no fallback (server push only):**

```typescript
{
  url: '/events',
  transport: 'sse',
  fallbackTransport: '',
  reconnect: true,
}
```

**Long-polling with authentication:**

```typescript
{
  url: '/secure/chat',
  transport: 'long-polling',
  headers: { Authorization: 'Bearer eyJhbGc...' },
  withCredentials: true,
}
```

---

## Callback Events

Callbacks are the primary way to react to lifecycle events and incoming data.

| Callback | Signature | When It Fires |
|---|---|---|
| `open` | `(response) => void` | Connection established. `response.transport` contains the negotiated transport. |
| `message` | `(response) => void` | A message arrives from the server. `response.responseBody` contains the payload. |
| `close` | `(response) => void` | Connection closed (cleanly or due to error). |
| `error` | `(response) => void` | An error occurred on the connection. |
| `reconnect` | `(request, response) => void` | A reconnection attempt is about to start. You can modify `request` to change behavior. |
| `transportFailure` | `(errorMsg, request) => void` | The primary transport failed and the client is about to try the fallback. |

### Handling Messages

The `message` callback receives a `response` object. The most important field is `responseBody`:

```typescript
message: (response) => {
  // Raw string
  const raw = response.responseBody;

  // Parse JSON if your server sends JSON
  try {
    const data = JSON.parse(raw);
    console.log(`${data.user}: ${data.text}`);
  } catch {
    console.log('Raw message:', raw);
  }
}
```

### Reconnection Flow

When the connection drops, `atmosphere.js` follows this sequence:

1. **`close`** fires immediately.
2. After `reconnectInterval` milliseconds, **`reconnect`** fires.
3. The client attempts to re-establish the connection.
4. If successful, **`open`** fires again with the new transport.
5. If the attempt fails and `maxReconnectOnClose` has not been reached, go back to step 2.
6. If all attempts are exhausted, **`error`** fires with a final status.

```typescript
{
  reconnect: (request, response) => {
    console.log(`Reconnect attempt (transport: ${request.transport})`);
    // You can modify the request here, e.g., switch transport
  },
  open: (response) => {
    if (response.request.reconnectInterval > 0) {
      console.log('Reconnected successfully!');
    }
  },
}
```

---

## React Hooks

React hooks provide a declarative, idiomatic way to use Atmosphere in React applications. All hooks require an `<AtmosphereProvider>` ancestor in the component tree.

### Setting Up the Provider

```tsx
import { AtmosphereProvider } from 'atmosphere.js/react';

function App() {
  return (
    <AtmosphereProvider>
      <Chat />
      <Sidebar />
    </AtmosphereProvider>
  );
}
```

The provider manages the Atmosphere client instance and shares it across all hooks in the subtree. It handles cleanup on unmount.

### useAtmosphere

The foundational hook. Subscribe to any Atmosphere endpoint:

```tsx
import { useAtmosphere } from 'atmosphere.js/react';

interface ChatMessage {
  user: string;
  text: string;
  timestamp: number;
}

function Chat() {
  const { data, state, push } = useAtmosphere<ChatMessage>({
    request: {
      url: '/chat',
      transport: 'websocket',
      fallbackTransport: 'long-polling',
    },
  });

  const [input, setInput] = useState('');

  const send = () => {
    if (input.trim()) {
      push({ user: 'Me', text: input, timestamp: Date.now() });
      setInput('');
    }
  };

  return (
    <div>
      <p>Status: {state}</p>
      {data && <p>Last message: {data.user}: {data.text}</p>}
      <input value={input} onChange={(e) => setInput(e.target.value)} />
      <button onClick={send} disabled={state !== 'connected'}>
        Send
      </button>
    </div>
  );
}
```

The `state` field is one of: `'connecting'`, `'connected'`, `'disconnected'`, `'error'`.

### useRoom

Join a named room with presence tracking and message broadcasting:

```tsx
import { useRoom } from 'atmosphere.js/react';

interface ChatMessage {
  text: string;
}

function ChatRoom({ roomName, userId }: { roomName: string; userId: string }) {
  const { joined, members, messages, broadcast } = useRoom<ChatMessage>({
    request: { url: '/atmosphere/room', transport: 'websocket' },
    room: roomName,
    member: { id: userId },
  });

  return (
    <div>
      <h2>Room: {roomName}</h2>
      {!joined && <p>Joining room...</p>}

      <div>
        <h3>Members ({members.length})</h3>
        <ul>
          {members.map((m) => (
            <li key={m.id}>{m.id}</li>
          ))}
        </ul>
      </div>

      <div>
        <h3>Messages</h3>
        {messages.map((msg, i) => (
          <div key={i}>
            <strong>{msg.member.id}:</strong> {msg.data.text}
          </div>
        ))}
      </div>

      <button onClick={() => broadcast({ text: 'Hello room!' })}>
        Send to Room
      </button>
    </div>
  );
}
```

### usePresence

A lightweight hook focused on who is online, without message history:

```tsx
import { usePresence } from 'atmosphere.js/react';

function OnlineIndicator({ userId }: { userId: string }) {
  const { members, count, isOnline } = usePresence({
    request: { url: '/atmosphere/room', transport: 'websocket' },
    room: 'lobby',
    member: { id: userId },
  });

  return (
    <div>
      <p>{count} users online</p>
      <ul>
        {members.map((m) => (
          <li key={m.id}>
            {m.id} {isOnline(m.id) ? '(online)' : '(away)'}
          </li>
        ))}
      </ul>
    </div>
  );
}
```

### useStreaming

Purpose-built for AI/LLM token streaming. Handles the Atmosphere AI wire protocol automatically:

```tsx
import { useStreaming } from 'atmosphere.js/react';

function AiAssistant() {
  const { fullText, isStreaming, stats, routing, send, reset } = useStreaming({
    request: { url: '/ai/chat', transport: 'websocket' },
  });

  const [prompt, setPrompt] = useState('');

  return (
    <div>
      <div>
        <input
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          placeholder="Ask a question..."
        />
        <button
          onClick={() => { send(prompt); setPrompt(''); }}
          disabled={isStreaming}
        >
          {isStreaming ? 'Generating...' : 'Send'}
        </button>
        <button onClick={reset} disabled={isStreaming}>
          Clear
        </button>
      </div>

      {/* The response streams in token by token */}
      <div style={{ whiteSpace: 'pre-wrap' }}>{fullText}</div>

      {/* Metadata from the server */}
      {routing && <small>Model: {routing.model}</small>}
      {stats && <small> | Tokens: {stats.totalTokens}</small>}
    </div>
  );
}
```

The `useStreaming` hook returns:

| Field | Type | Description |
|---|---|---|
| `fullText` | `string` | The accumulated response text, updated as tokens arrive. |
| `isStreaming` | `boolean` | `true` while the server is actively sending tokens. |
| `stats` | `{ totalTokens: number } \| null` | Token count and other stats, populated when the stream completes. |
| `routing` | `{ model: string } \| null` | Model routing info from metadata messages. |
| `send` | `(prompt: string) => void` | Send a prompt to the server. |
| `reset` | `() => void` | Clear the accumulated text and stats. |

---

## Vue Composables

Vue composables work without a provider component. Each composable creates or accepts an Atmosphere connection directly.

### useAtmosphere

```vue
<script setup lang="ts">
import { useAtmosphere } from 'atmosphere.js/vue';

interface ChatMessage {
  user: string;
  text: string;
}

const { data, state, push } = useAtmosphere<ChatMessage>({
  url: '/chat',
  transport: 'websocket',
});

const input = ref('');

function send() {
  if (input.value.trim()) {
    push({ user: 'Me', text: input.value });
    input.value = '';
  }
}
</script>

<template>
  <p>Status: {{ state }}</p>
  <div v-if="data">{{ data.user }}: {{ data.text }}</div>
  <input v-model="input" @keyup.enter="send" />
  <button @click="send" :disabled="state !== 'connected'">Send</button>
</template>
```

### useRoom

```vue
<script setup lang="ts">
import { useRoom } from 'atmosphere.js/vue';

const { members, messages, broadcast } = useRoom<{ text: string }>(
  { url: '/atmosphere/room', transport: 'websocket' },
  'lobby',
  { id: 'user-1' },
);
</script>

<template>
  <p>{{ members.length }} members online</p>
  <div v-for="(msg, i) in messages" :key="i">
    {{ msg.member.id }}: {{ msg.data.text }}
  </div>
  <button @click="broadcast({ text: 'Hello!' })">Send</button>
</template>
```

### usePresence

```vue
<script setup lang="ts">
import { usePresence } from 'atmosphere.js/vue';

const { members, count, isOnline } = usePresence(
  { url: '/atmosphere/room', transport: 'websocket' },
  'lobby',
  { id: 'user-1' },
);
</script>

<template>
  <p>{{ count }} users online</p>
  <span v-for="m in members" :key="m.id">
    {{ m.id }}: {{ isOnline(m.id) ? 'online' : 'away' }}
  </span>
</template>
```

### useStreaming

```vue
<script setup lang="ts">
import { useStreaming } from 'atmosphere.js/vue';

const { fullText, isStreaming, stats, routing, send, reset } = useStreaming({
  url: '/ai/chat',
  transport: 'websocket',
});

const prompt = ref('');
</script>

<template>
  <input v-model="prompt" placeholder="Ask a question..." />
  <button @click="send(prompt); prompt = ''" :disabled="isStreaming">
    {{ isStreaming ? 'Generating...' : 'Send' }}
  </button>
  <button @click="reset" :disabled="isStreaming">Clear</button>
  <div style="white-space: pre-wrap">{{ fullText }}</div>
  <small v-if="routing">Model: {{ routing.model }}</small>
  <small v-if="stats"> | Tokens: {{ stats.totalTokens }}</small>
</template>
```

---

## Svelte Stores

Svelte integrations use the store pattern. Each factory returns a readable Svelte store plus action functions.

### createAtmosphereStore

```svelte
<script>
  import { createAtmosphereStore } from 'atmosphere.js/svelte';

  const { store: chat, push } = createAtmosphereStore({
    url: '/chat',
    transport: 'websocket',
  });

  let input = '';

  function send() {
    if (input.trim()) {
      push({ user: 'Me', text: input });
      input = '';
    }
  }
</script>

<p>Status: {$chat.state}</p>
{#if $chat.data}
  <p>{$chat.data.user}: {$chat.data.text}</p>
{/if}
<input bind:value={input} on:keypress={(e) => e.key === 'Enter' && send()} />
<button on:click={send} disabled={$chat.state !== 'connected'}>Send</button>
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

<p>{$lobby.members.length} members online</p>
{#each $lobby.messages as msg, i}
  <div>{msg.member.id}: {msg.data.text}</div>
{/each}
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
{#each $presence.members as m}
  <span>{m.id}: {$presence.isOnline(m.id) ? 'online' : 'away'}</span>
{/each}
```

### createStreamingStore

```svelte
<script>
  import { createStreamingStore } from 'atmosphere.js/svelte';

  const { store, send, reset } = createStreamingStore({
    url: '/ai/chat',
    transport: 'websocket',
  });

  let prompt = '';
</script>

<input bind:value={prompt} placeholder="Ask a question..." />
<button on:click={() => { send(prompt); prompt = ''; }} disabled={$store.isStreaming}>
  {$store.isStreaming ? 'Generating...' : 'Send'}
</button>
<button on:click={reset} disabled={$store.isStreaming}>Clear</button>
<div style="white-space: pre-wrap">{$store.fullText}</div>
{#if $store.routing}<small>Model: {$store.routing.model}</small>{/if}
{#if $store.stats}<small> | Tokens: {$store.stats.totalTokens}</small>{/if}
```

---

## AI Streaming Wire Protocol

The `useStreaming` hook (and its Vue/Svelte equivalents) consume a JSON-based wire protocol. Understanding this protocol is important if you are building a custom server handler or debugging streaming behavior.

### Message Types

Each message from the server is a JSON object with a `type` field:

| Type | Fields | Description |
|---|---|---|
| `token` | `data`, `sessionId`, `seq` | A single token of the generated response. The client appends `data` to `fullText`. |
| `progress` | `data`, `sessionId`, `seq` | A progress indicator (e.g., "Thinking..."). Optional. |
| `metadata` | `key`, `value`, `sessionId`, `seq` | Key-value metadata. When `key` is `"model"`, the client populates `routing.model`. |
| `complete` | `data`, `sessionId`, `seq` | The stream is finished. The client sets `isStreaming = false` and populates `stats`. |
| `error` | `data`, `sessionId`, `seq` | An error occurred. The client sets `isStreaming = false` and reports the error. |

### Example Stream

Here is a complete token stream for the prompt "What is WebSocket?":

```json
{"type":"token","data":"Web","sessionId":"sess-001","seq":1}
{"type":"token","data":"Socket","sessionId":"sess-001","seq":2}
{"type":"token","data":" is","sessionId":"sess-001","seq":3}
{"type":"token","data":" a","sessionId":"sess-001","seq":4}
{"type":"token","data":" protocol","sessionId":"sess-001","seq":5}
{"type":"token","data":" for","sessionId":"sess-001","seq":6}
{"type":"token","data":" full","sessionId":"sess-001","seq":7}
{"type":"token","data":"-duplex","sessionId":"sess-001","seq":8}
{"type":"token","data":" communication.","sessionId":"sess-001","seq":9}
{"type":"metadata","key":"model","value":"gpt-4","sessionId":"sess-001","seq":10}
{"type":"complete","data":"","sessionId":"sess-001","seq":11}
```

At the end of this stream:

- `fullText` = `"WebSocket is a protocol for full-duplex communication."`
- `isStreaming` = `false`
- `routing` = `{ model: "gpt-4" }`
- `stats` = `{ totalTokens: 9 }`

### Sequence Numbers

The `seq` field is a monotonically increasing integer per session. It allows the client to detect out-of-order delivery (rare but possible with certain transports) and request retransmission. The `useStreaming` hook buffers out-of-order tokens and replays them in order.

### Session IDs

The `sessionId` ties all messages in a single generation to one logical session. If you send multiple prompts over the same connection, each prompt gets a new `sessionId`. The hook uses this to separate interleaved streams.

---

## Building a Complete Chat Application

Let us tie the core API and React hooks together with a server-side handler to build a working chat application.

### Server (Spring Boot)

```java
@ManagedService(path = "/chat")
public class ChatService extends ManagedAtmosphereHandler {

    @Override
    public void onMessage(AtmosphereResource resource, String message) {
        resource.getBroadcaster().broadcast(message);
    }
}
```

### Client (React)

```tsx
import { AtmosphereProvider, useAtmosphere } from 'atmosphere.js/react';
import { useState } from 'react';

interface ChatMessage {
  user: string;
  text: string;
}

function ChatApp() {
  const { data, state, push } = useAtmosphere<ChatMessage>({
    request: {
      url: '/chat',
      transport: 'websocket',
      fallbackTransport: 'long-polling',
      reconnect: true,
      maxReconnectOnClose: 10,
    },
  });

  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [username] = useState(() => `User-${Math.random().toString(36).slice(2, 6)}`);

  // Accumulate messages
  if (data && (messages.length === 0 || messages[messages.length - 1] !== data)) {
    setMessages((prev) => [...prev, data]);
  }

  const send = () => {
    if (input.trim() && state === 'connected') {
      push({ user: username, text: input });
      setInput('');
    }
  };

  return (
    <div>
      <h1>Atmosphere Chat</h1>
      <p>Status: {state} | You are: {username}</p>

      <div style={{ height: 400, overflow: 'auto', border: '1px solid #ccc' }}>
        {messages.map((msg, i) => (
          <div key={i}>
            <strong>{msg.user}:</strong> {msg.text}
          </div>
        ))}
      </div>

      <input
        value={input}
        onChange={(e) => setInput(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && send()}
        placeholder="Type a message..."
      />
      <button onClick={send} disabled={state !== 'connected'}>
        Send
      </button>
    </div>
  );
}

export default function App() {
  return (
    <AtmosphereProvider>
      <ChatApp />
    </AtmosphereProvider>
  );
}
```

---

## Transport Selection Guide

Choosing the right transport depends on your deployment environment and requirements:

| Transport | Direction | Proxy-Safe | Latency | Use When |
|---|---|---|---|---|
| `websocket` | Full-duplex | No (some proxies strip Upgrade) | Lowest | Default choice for modern deployments |
| `sse` | Server-to-client only | Yes | Low | Server push only, or when WebSocket is blocked |
| `long-polling` | Simulated full-duplex | Yes | Medium | Universal fallback, corporate proxies |
| `streaming` | Server-to-client | Varies | Low | Continuous data push without reconnection overhead |

The recommended pattern is `transport: 'websocket'` with `fallbackTransport: 'long-polling'`. This gives you optimal performance when WebSocket is available and universal compatibility when it is not.

---

## Browser Compatibility

- Chrome/Edge: last 2 versions
- Firefox: last 2 versions + ESR
- Safari: last 2 versions
- Mobile Safari (iOS): last 2 versions
- Chrome Android: last 2 versions

For React Native support, see the [React Native Client](/docs/clients/react-native/) reference.

---

## Summary

In this chapter you learned:

- The **core API** (`atmosphere.subscribe` / `subscription.push`) provides transport-agnostic real-time communication.
- **Request options** control transport selection, reconnection behavior, and authentication.
- **Callback events** (`open`, `message`, `close`, `error`, `reconnect`, `transportFailure`) give you full lifecycle control.
- **React hooks** (`useAtmosphere`, `useRoom`, `usePresence`, `useStreaming`) offer a declarative API behind an `AtmosphereProvider`.
- **Vue composables** provide the same API without needing a provider component.
- **Svelte stores** follow the Svelte store contract, returning readable stores plus action functions.
- The **AI streaming wire protocol** uses typed JSON messages (`token`, `metadata`, `complete`, `error`) with session IDs and sequence numbers.

In the [next chapter](/docs/tutorial/20-grpc-kotlin/), we explore advanced transports and language support -- gRPC, the Kotlin DSL, virtual threads, and where to go from here.

## See Also

- [atmosphere.js Reference](/docs/clients/javascript/) -- compact API reference
- [React Native Client](/docs/clients/react-native/) -- mobile support
- [wAsync Java Client](/docs/clients/java/) -- Java client for server-to-server communication
- [AI Integration](/docs/reference/ai/) -- server-side `@AiEndpoint` and `StreamingSession`
