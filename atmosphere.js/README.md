# atmosphere.js

TypeScript client for the Atmosphere Framework. Supports WebSocket, SSE, Long-Polling, Streaming, and WebTransport transports.

[![npm version](https://img.shields.io/npm/v/atmosphere.js)](https://www.npmjs.com/package/atmosphere.js)
[![TypeScript](https://img.shields.io/badge/TypeScript-6.0-blue)](https://www.typescriptlang.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## Features

- TypeScript with full type safety and IntelliSense support
- Multiple transports: WebSocket, SSE, Long-polling, Streaming, WebTransport
- Automatic reconnection with exponential backoff
- Tree-shakeable — import only what you need
- No runtime dependencies; React, Vue, Svelte, React Native, and the chat UI's markdown renderers are optional peer dependencies
- Promise-based API with async/await
- Comprehensive test coverage

## Installation

```bash
npm install atmosphere.js
```

The React chat UI components in `atmosphere.js/chat` render markdown with `react-markdown` and
`remark-gfm`, which are optional peer dependencies: install them (with `react`) only if you
import that entry point.

```bash
npm install atmosphere.js react react-markdown remark-gfm
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
  transport: TransportType;         // 'websocket' | 'sse' | 'long-polling' | 'streaming' | 'webtransport'
  fallbackTransport?: TransportType;// Transport to use if primary fails
  contentType?: string;             // Content-Type header
  timeout?: number;                 // Request timeout in milliseconds
  reconnect?: boolean;              // Enable auto-reconnection
  reconnectInterval?: number;       // Base of the reconnect backoff (ms)
  maxReconnectOnClose?: number;     // Maximum reconnection attempts
  retryStrategy?: RetryStrategy;    // Reconnect delay policy (see "Configuring fallback + reconnect")
  trackMessageLength?: boolean;     // Enable message length tracking
  messageDelimiter?: string;        // Delimiter for split messages
  enableProtocol?: boolean;         // Enable Atmosphere protocol
  headers?: Record<string, string | (() => string)>; // Custom headers, re-read on every request
  headerDelivery?: 'auto' | 'headers' | 'query';     // How fetch requests carry them (default 'auto')
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
const state = subscription.state; // 'disconnected' | 'connecting' | 'connected' | 'reconnecting' | 'suspended' | 'closed' | 'error'

// The server's tracking id (X-Atmosphere-tracking-id) of this connection
const uuid = subscription.uuid;

// Close the subscription
await subscription.close();

// Event emitter style
subscription.on('custom-event', (data) => {
  console.log(data);
});

subscription.off('custom-event', handler);
```

`subscription.uuid` is the id the server knows the connection by (the
`AtmosphereResource` uuid). It is read from the transport on every access: it
is `'0'` until the Atmosphere protocol handshake arrives, which requires
`enableProtocol: true`, and it is the fallback transport's id when the
fallback took over. `subscription.id` is a client-side counter (`sub-N`).

Over long-polling, streaming and SSE, `close()` also tells the server the
connection is gone: it sends Atmosphere's close message
(`X-Atmosphere-Transport=close` with the `uuid` as `X-Atmosphere-tracking-id`,
plus the request's URL parameters and `headers`, as a `keepalive` POST), which
the server's default `OnDisconnectInterceptor` answers by releasing the
`AtmosphereResource`. While the subscription is open, a `pagehide` listener
sends the same message with `navigator.sendBeacon` (falling back to a
`keepalive` fetch) when the page is hidden or unloaded; `close()` removes it.
The beacon adds `X-Atmosphere-Close-Reason=going-away`: the page is reloading
or navigating and may come back, which is what a WebSocket's 1001 close tells
the server, while the message `close()` sends has no reason, a client that
left. The server exposes the reason to its disconnect listeners (an AI run is
kept going for its reattach grace on a going-away, cancelled at once on a
leave); a server that predates it treats both alike.
No message is sent while `uuid` is `'0'`, so it requires `enableProtocol: true`.
WebSocket and WebTransport send none: closing the socket tells the server. A
second `close()` sends nothing, and a failed close message is logged at debug
level, never thrown.

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

A header value may be a function: it is called for every request (each poll, each
`push`), and plain values are re-read every time too, so a value can change during the
subscription:

```typescript
headers: { 'X-Last-Seen-Id': () => String(lastSeenId) }
```

How the headers and `authToken` (sent as `X-Atmosphere-Auth`) travel:

- WebSocket, WebTransport and the SSE `EventSource` cannot set request headers, so they
  are always query parameters; the Atmosphere server turns them back into request headers.
- Requests made with `fetch` (long-polling and streaming connections, and every message
  pushed over long-polling, streaming or SSE) send them as real HTTP headers, out of the URL
  and so out of access logs, when the request is same-origin, or when there is no page
  origin (React Native). A cross-origin request keeps them as query parameters: custom
  headers make the browser send a CORS preflight, and the server's `CorsInterceptor` allows
  only a fixed list of request headers. Set `headerDelivery: 'headers'` when your server's
  CORS policy allows them, or `'query'` to keep every value in the URL.
- A header fetch cannot carry (an invalid name, a forbidden one such as `Cookie`,
  `Content-Type`, or a value outside Latin-1 or with a line break) stays a query parameter.

On the server, read these values with `request.getHeader(name)`, which sees both forms; a
value sent as a header is not returned by `request.getParameter(name)`. On HTTP transports
the server percent-decodes (UTF-8) the query parameters it maps to headers, so a value that
needed URL-encoding (a space, `&`, `+`, non-ASCII) reads the same from `getHeader` whether it
travelled as a header or a query parameter; WebSocket requests already get their headers
from the container-decoded parameters. A malformed escape such as `%zz`, or one that decodes
to a control character such as CR or LF, is kept as sent. Earlier server releases returned
the HTTP query-parameter values still percent-encoded: an application that decodes them
itself must stop doing so, or set `org.atmosphere.cpr.decodeQueryStringHeaders` to `false`
to keep the raw values.

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
      if (error.willRetry) {
        console.warn(`Attempt ${error.attempt} failed, reconnecting:`, error.message);
      } else {
        console.error('Connection error:', error);
      }
    },
    close: (response) => {
      console.log('Connection closed:', response.reasonPhrase);
    }
  });
} catch (error) {
  console.error('Failed to connect:', error);
}
```

The `error` handler fires for every failed connection attempt, including those the client
recovers from on its own, so the `Error` it receives is an `AtmosphereError` with two extra
fields. `willRetry` is `true` when the transport follows the failure with an automatic
reconnect attempt and `false` when nothing more is attempted for it: `reconnect` is off, the
reconnect quota is spent (`failureToReconnect` fires as well), the initial SSE, streaming,
long-polling or WebTransport connection failed (`subscribe()` rejects or falls back), or a
message was not delivered (`isSendError`). `attempt` is 0 for the initial connection or a
drop of an open one, and `n` for the n-th reconnect attempt since the connection was last open.

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

#### `useStreaming` -- AI/LLM text streaming

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

Returns `{ fullText, streamingTexts, isStreaming, progress, metadata, error, send, reset, close }`.

#### `useChat` -- AI chat state on top of streaming

`useChat` wraps `useStreaming` with an AI-SDK-style chat contract: `messages`,
`input`, `setInput`, `append`, `handleSubmit`, `reload`, and `stop`. Use it when
you want optimistic user bubbles plus token-level assistant rendering without
hand-writing message bookkeeping in every sample.

```tsx
import { useChat } from 'atmosphere.js/react';

function AiChat() {
  const { messages, input, setInput, handleSubmit, isLoading, error } = useChat({
    request: { url: '/ai/chat', transport: 'websocket' },
    sendOptions: { maxCost: 0.10, maxLatencyMs: 5000 },
  });

  return (
    <form onSubmit={handleSubmit}>
      {messages.map((message) => (
        <p key={message.id} data-role={message.role}>{message.content}</p>
      ))}
      {error && <p role="alert">{error}</p>}
      <input value={input} onChange={(e) => setInput(e.target.value)} />
      <button disabled={isLoading}>Send</button>
    </form>
  );
}
```

Returns `{ messages, input, setInput, append, handleSubmit, reload, stop,
isLoading, progress, metadata, stats, routing, aiEvents, connectionStatus }`.
`stop()` closes the active streaming subscription; remount the hook or toggle
`enabled` to open a fresh transport after a hard stop.

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
  // $store.fullText, $store.isStreaming, $store.streamingTexts, $store.progress
</script>

<button on:click={() => send('What is Atmosphere?')}>Ask</button>
<p>{$store.fullText}</p>
{#if $store.isStreaming}<span>Generating...</span>{/if}
```

### React Native / Expo

Import from `atmosphere.js/react-native`. Call `setupReactNative()` once at app startup.
All hooks require an `<AtmosphereProvider>` ancestor.

> **Full guide:** [React Native client](https://atmosphere.github.io/docs/clients/react-native/)

#### Setup

```tsx
import { setupReactNative, AtmosphereProvider } from 'atmosphere.js/react-native';

setupReactNative(); // installs polyfills, detects capabilities

export default function App() {
  return (
    <AtmosphereProvider config={{ logLevel: 'info' }}>
      <Chat />
    </AtmosphereProvider>
  );
}
```

#### `useAtmosphereRN<T>` -- subscribe with AppState + NetInfo

```tsx
import { useAtmosphereRN } from 'atmosphere.js/react-native';

function Chat() {
  const { data, state, push, isConnected } = useAtmosphereRN<ChatMessage>({
    request: { url: 'https://example.com/chat', transport: 'websocket' },
    backgroundBehavior: 'suspend', // 'suspend' | 'disconnect' | 'keep-alive'
  });
  // ...
}
```

Returns `{ subscription, state, data, error, push, isConnected, isInternetReachable }`.

#### `useStreamingRN` -- AI streaming with AppState + NetInfo

```tsx
import { useStreamingRN } from 'atmosphere.js/react-native';

function AiChat() {
  const { fullText, isStreaming, isConnected, send, reset } = useStreamingRN({
    request: { url: 'https://example.com/ai/chat', transport: 'websocket' },
    backgroundBehavior: 'keep-alive', // default; or 'suspend' | 'disconnect'
  });
  // ...
}
```

Returns the same fields as `useStreaming` plus `isConnected`.

`backgroundBehavior` defaults to `keep-alive` here, unlike `useAtmosphereRN`: an answer
that is streaming when the app is backgrounded keeps arriving. `suspend` suspends the
subscription until the app returns (frames that arrive meanwhile are dropped), and
`disconnect` closes the stream and opens a new one on return.

#### Installation

```bash
bun add atmosphere.js
bun add @react-native-community/netinfo  # optional, for network-aware reconnection
```

---

## Resilience: disconnect, reconnect, transport fallback

Atmosphere's signature strength is keeping a connection alive across network blips,
server restarts, and transport-level failures. Every subscription exposes the full
classic Atmosphere 3.x lifecycle so your UI can react to every step:

| Hook                    | Fires when                                                              |
|-------------------------|-------------------------------------------------------------------------|
| `open`                  | Initial transport is established                                        |
| `reopen`                | Connection re-established after a disconnect                            |
| `reconnect`             | A reconnection attempt has begun                                        |
| `close`                 | Transport closed (clean shutdown)                                       |
| `error`                 | Transport-level error; `error.willRetry` says if a reconnect follows    |
| `transportFailure`      | Primary transport failed; client is falling back to `fallbackTransport` |
| `clientTimeout`         | Client-side heartbeat watchdog expired                                  |
| `failureToReconnect`    | `maxReconnectOnClose` exhausted — terminal state                        |

`open` fires only after `subscribe()` has resolved, on every transport, so an `open`
handler can use the subscription: `const sub = await atmosphere.subscribe(request,
{ open: () => sub.push('hello') })` works. Events the transport reports while
`subscribe()` is still connecting are held and delivered, in order, from a task that runs
after the `await` continuation, or with the next network event if that comes first; they
are dropped if the subscription is closed before then. With `enableProtocol: true`, `open`
also waits for the protocol handshake, so the tracking id is known; long-polling waits for
the first poll's body, and a long-polling reconnect, which the server answers without a
handshake, fires `reopen` on its first successful response.

### `ConnectionStatus` — the unified view

Wiring eight hooks per consumer is busywork, so atmosphere.js ships a `ConnectionStatus`
primitive that collapses them into a small state machine plus a transient event indicator:

```typescript
import { Atmosphere, ConnectionStatus } from 'atmosphere.js';

const status = new ConnectionStatus();
status.onChange((snap) => {
  console.log(snap.phase, snap.lastEvent, snap.transport, snap.attempt);
  // phase:      'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed' | 'lost'
  // lastEvent:  one of the 8 hooks above (or null)
  // transport:  currently active transport (updates after fallback)
  // attempt:    reconnect attempt counter (resets to 0 on open)
  // viaFallback: true once a transportFailure has been observed
  // lastError:  most recent error, or null
});

const atmosphere = new Atmosphere();
const sub = await atmosphere.subscribe(request, status.wrap({
  message: (msg) => console.log(msg),
}));
```

`status.wrap()` preserves any handlers you already had — you can mix your own `message`,
`error`, etc. with the resilience tracking. No double-subscription, no duplicated callbacks.

### Framework integration

Every framework hook now exposes a reactive `connectionStatus` snapshot and a matching
`<ConnectionStatusBadge />` component (vanilla styles by default, easy to override):

```tsx
// React
import { useStreaming, ConnectionStatusBadge } from 'atmosphere.js/react';

const { connectionStatus, fullText, send } = useStreaming({
  request,
  onTransportFailure: (reason) => console.warn('Fell back:', reason),
  onFailureToReconnect: () => alert('Connection lost — refresh to retry'),
});

return <ConnectionStatusBadge status={connectionStatus} />;
// Renders: "Connected · websocket" / "Reconnecting… · websocket" / "Connection lost · websocket"
```

```vue
<!-- Vue -->
<script setup lang="ts">
import { useAtmosphere, ConnectionStatusBadge } from 'atmosphere.js/vue';
const { data, connectionStatus, push } = useAtmosphere({ url, transport: 'websocket' });
</script>
<template>
  <ConnectionStatusBadge :status="connectionStatus" />
</template>
```

```tsx
// React Native (Expo)
import { useStreamingRN, ConnectionStatusBadgeRN } from 'atmosphere.js/react-native';

const { connectionStatus, send } = useStreamingRN({ request });
return <ConnectionStatusBadgeRN status={connectionStatus} />;
```

### Configuring fallback + reconnect

Resilience behavior is driven by the request options:

```typescript
const request = {
  url: '/atmosphere/chat',
  transport: 'websocket',
  fallbackTransport: 'long-polling',  // tried if WebSocket fails
  reconnect: true,                     // off unless set
  reconnectInterval: 5000,             // base delay of the backoff (ms)
  maxReconnectOnClose: 10,             // give up after N attempts
  heartbeat: { client: 30000 },        // watchdog interval (ms)
};
```

Without a `retryStrategy`, reconnect attempt `n` waits `reconnectInterval * 2^min(n - 1, 5)`
times a random jitter in `[0.5, 1)`, so `reconnectInterval: 5000` grows to between 80 s and
160 s. Set `retryStrategy` for another policy: `linear` (`initialDelay * n`), `exponential`
(`initialDelay * 2^(n - 1)`, capped at `maxDelay`, or at 32x when it is absent) or `custom`
(`customCalculate(n)`). `maxDelay` caps every strategy, strategy delays carry no jitter,
`initialDelay` falls back to `reconnectInterval`, and `maxAttempts`, when set, replaces
`maxReconnectOnClose`. A constant 5 s delay:

```typescript
const request = {
  url: '/atmosphere/chat',
  transport: 'websocket',
  reconnect: true,
  retryStrategy: { strategy: 'custom', customCalculate: () => 5000, maxAttempts: 20 },
};
```

The classic fallback chain — WebSocket → SSE → streaming → long-polling — is implemented
end-to-end. On `transportFailure`, the client tears down the failed transport, fires the
hook, and connects via `fallbackTransport`. Subsequent reconnects use the fallback unless
the server signals otherwise.

Over SSE, streaming and long-polling each `push` is its own HTTP POST. A POST the server
answers `503` (it could not take the message yet) is sent again after its `Retry-After`
(1 s when absent, at most 5 s), up to 3 attempts in all. A message still refused, or
answered with any other non-2xx status, is reported to the `error` handler with an `Error`
whose `name` is `'AtmosphereSendError'` (test it with `isSendError(error)`): the message was
not delivered, but the connection is still up, so the React, Vue and Svelte hooks record the
error without moving their connection state to `'error'`; `subscribeStreaming` passes
`undelivered = true` as the second argument of `onError`.

To reach an Atmosphere `@AiEndpoint` over SSE, streaming or long-polling (including when
one of them takes over as the `fallbackTransport`), set `enableProtocol: true`. These
transports learn their tracking id from the protocol handshake; without it they send `0`,
which names no connection, so the server answers every prompt `400` and each one is
reported as an `AtmosphereSendError`. WebSocket does not need it. See
[Prompt routing over HTTP transports](../modules/ai/README.md#prompt-routing-over-http-transports).

### Offline queue — survive disconnect without losing user input

Messages typed while the transport is disconnected can be queued locally and drained
automatically on the next `open` event. The shipped primitive is `OfflineQueue` and
each framework exposes a reactive hook around it.

```tsx
// React
import { useAtmosphere, useOfflineQueue, ConnectionStatusBadge } from 'atmosphere.js/react';

function Chat() {
  const offline = useOfflineQueue<string>({ maxSize: 50 });

  const { push, connectionStatus } = useAtmosphere<string>({
    request: {
      url: '/atmosphere/chat',
      transport: 'websocket',
      fallbackTransport: 'sse',
      offlineQueue: offline.queue,         // ← transport drains this on reconnect
    },
  });

  const send = (text: string) => {
    if (connectionStatus.phase === 'open') {
      push(text);
    } else {
      offline.enqueue(text);                // ← queued offline, drained on reopen
    }
  };

  return (
    <div>
      <ConnectionStatusBadge status={connectionStatus} />
      {offline.size > 0 && <span>{offline.size} queued</span>}
      {/* … */}
    </div>
  );
}
```

The same hook ships for Vue (`useOfflineQueue`, composable returning refs), Svelte
(`createOfflineQueueStore`, readable store), and React Native (re-exported from the
React entry point — pure-React, no DOM-only globals). The underlying `OfflineQueue`
primitive owns:

| State        | Meaning                                                    |
|--------------|------------------------------------------------------------|
| `pending`    | Queued offline, waiting for the next reconnect             |
| `sent`       | Delivered to the transport, awaiting server confirmation   |
| `confirmed`  | Server acknowledged delivery via `queue.acknowledge(id)`   |
| `failed`     | Drain or send failed; surfaced via `onFailed` handler      |

`drain on reconnect` is enabled by default and runs inside `BaseTransport`'s `open`
handler. Server-side ACKs ride on the broadcast echo back to the sender for chat-style
samples — call `queue.acknowledge(messageId)` from your `onMessage` handler when you
recognize your own message. A future `RoomProtocolCodec` change will surface
server-confirmed ids automatically; until then the `'confirmed'` state is opt-in.

### History sync — exactly-what-you-missed replay on reconnect

When a client reconnects after a disconnect, naive cache replay sends *every* buffered
message and the UI shows duplicates. atmosphere.js's `MessageHistorySync` primitive
tracks the largest server-assigned `id` you've seen and sends it back as `sinceId` on
the next join — the server then replays only entries with `id > sinceId`.

```tsx
// React
import { useAtmosphere, useMessageHistory } from 'atmosphere.js/react';

function Chat() {
  const history = useMessageHistory({
    storage: window.localStorage,                 // persist across reloads
    storageKey: 'atmosphere:chat:lobby:lastSeenId',
  });

  const { push } = useAtmosphere<string>({
    request: { url: '/atmosphere/chat', transport: 'websocket' },
    onMessage: (raw) => {
      const parsed = JSON.parse(String(raw.responseBody));
      history.observe(parsed);                    // advances on `id` fields
      // … render message …
    },
    onReopen: () => {
      // Re-join carrying the cursor so the server skips messages we already have
      push(JSON.stringify({
        type: 'join', room: 'lobby', memberId, sinceId: history.lastSeenId,
      }));
    },
  });
}
```

Server-side, enable history on the room:

```java
Room lobby = roomManager.room("lobby");
lobby.enableHistory(50);  // ring buffer of the last 50 broadcasts
```

`RoomProtocolCodec` now emits an `id` field on every message and accepts `sinceId`
on the join frame; `RoomProtocolInterceptor` replays history entries with
`id > sinceId` when the cursor is present, falling back to the legacy
`BroadcasterCache` replay when `sinceId` is omitted (legacy clients keep working).

The same primitive ships for Vue (`useMessageHistory` composable returning a `Ref<number>`),
Svelte (`createMessageHistoryStore`, readable store of `lastSeenId`), and React Native
(re-exported from the React entry point). The cursor is optional persistence: pass
`storage: window.localStorage` for survive-reload semantics; omit for in-memory only.

### Optimistic updates — render before the round-trip resolves

For chat-style UIs where the user expects their message to appear instantly,
`useOptimistic` wraps the same `OfflineQueue.track` primitive into a reactive
"sending → confirmed | failed" state machine.

```tsx
// React
import { useOptimistic } from 'atmosphere.js/react';

function Chat() {
  // confirmAfterMs auto-confirms if no rollback fires within the window —
  // useful when the server does not echo broadcasts back to the sender.
  const optimistic = useOptimistic<{ text: string }>({ confirmAfterMs: 600 });

  const send = (text: string) => {
    const handle = optimistic.send({ text });
    push(JSON.stringify({ type: 'broadcast', room: 'lobby', data: text }));
    // If the server *does* echo a confirmation: optimistic.commit(handle.id)
    // If the server returns an error:           optimistic.rollback(handle.id, 'reason')
  };

  return optimistic.messages.map((m) => (
    <ChatBubble
      key={m.id}
      text={m.data.text}
      sending={m.state === 'sent'}
      failed={m.state === 'failed'}
      error={m.error}
    />
  ));
}
```

Same surface for Vue (`useOptimistic` composable with `ComputedRef`s),
Svelte (`createOptimisticStore`), and React Native (re-export). The hook
ships with these states (`MessageState`):

| State        | Meaning                                                                |
|--------------|------------------------------------------------------------------------|
| `sent`       | In flight — render the bubble in an "optimistic" visual                |
| `confirmed`  | Server-ack'd via `commit(id)` or auto-confirmed via `confirmAfterMs`   |
| `failed`     | Rolled back via `rollback(id, error)` — show retry / undo affordance   |

The optimistic and offline-queue layers share the same `OfflineQueue`
primitive under the hood, so message ids are namespaced consistently.

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
{"type": "streaming-text",    "data": "Hello",        "sessionId": "abc-123", "seq": 1}
{"type": "progress", "data": "Thinking...",   "sessionId": "abc-123", "seq": 2}
{"type": "metadata", "key": "model",  "value": "gpt-4", "sessionId": "abc-123", "seq": 3}
{"type": "complete", "data": "Done",          "sessionId": "abc-123", "seq": 10}
{"type": "error",    "data": "Rate limited",  "sessionId": "abc-123", "seq": 11}
```

Message types: `streaming-text`, `progress`, `complete`, `error`, `metadata`.

### `parseStreamingMessage(raw)`

Low-level decoder that parses a raw string into a `StreamingMessage`, or returns `null` if it is not a valid streaming protocol message. This is an internal utility used by `subscribeStreaming`:

```typescript
import { parseStreamingMessage } from 'atmosphere.js/streaming/decoder';

const msg = parseStreamingMessage('{"type":"streaming-text","data":"Hi","sessionId":"s1","seq":1}');
if (msg) {
  console.log(msg.type, msg.data); // "streaming-text" "Hi"
}
```

> **Note**: Most applications should use `subscribeStreaming` or framework hooks (`useStreaming`) instead of calling this directly.

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
  onStreamingText: (streamingText, seq) => process.stdout.write(streamingText),
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

### Lifecycle hooks (resilience)

`subscribeStreaming` and `useStreaming` expose four lifecycle callbacks that pair
with the server-side `@AiEndpoint` resilience primitives. Wire these on every
sample so the user sees what's happening rather than a frozen UI:

| Hook | Server primitive | UI cue |
|------|------------------|--------|
| `onOpen` | initial connect / reconnect succeeds | clear any "reconnecting" indicator |
| `onClose` | `AiStreamingSession.cancelInflight` aborts in-flight LLM | surface "session interrupted" |
| `onReconnect` | `@AiEndpoint(streamCache=UUIDBroadcasterCache.class)` replays cached frames | keep conversation visible, show transient banner |
| `onClientTimeout` | `@AiEndpoint(heartbeatSeconds=N)` watchdog expired | "connection lost — retrying" |

```typescript
const { fullText, connectionState, isReconnecting, send } = useStreaming({
  request: { url: '/ai/chat', transport: 'websocket', reconnect: true },
  onOpen: () => console.info('connected'),
  onClose: () => console.info('closed'),
  onReconnect: () => console.info('reconnecting'),
  onClientTimeout: () => console.warn('heartbeat watchdog'),
});

// connectionState: 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'closed' | 'error'
{isReconnecting && <Banner>Reconnecting… cached frames will replay</Banner>}
{connectionState === 'closed' && <Banner severity="error">Connection closed</Banner>}
```

The `samples/spring-boot-ai-chat/frontend` reference wires this end-to-end and
is the template for every other AI sample frontend.

---

## Interactions API

Typed client for the server's stateful **Interactions API** (`/api/interactions`,
`org.atmosphere.interactions`). An interaction is one agent turn carrying a durable
`steps[]` log; turns chain via `previousInteractionId`. Use it for the
**background "fire-and-retrieve"** pattern — launch a long-running turn, get its id
immediately, and poll the durable timeline (retrievable after a disconnect):

```ts
import { InteractionsClient } from 'atmosphere.js/interactions';

const client = new InteractionsClient(); // same-origin; or { baseUrl, headers, fetch }

// Launch detached and poll the durable step timeline until it finishes.
const run = await client.create({ message: 'Summarize the repo', background: true });
const done = await client.pollUntilTerminal(run.id, {
  onUpdate: (it) => render(it.steps),
});

// Or stream progressive updates with an async iterator (HTTP polling):
for await (const it of client.watch(run.id)) {
  render(it.steps);
}

// LIVE over the socket — durable steps pushed as they happen, no polling.
const sub = await client.subscribe(run.id, {
  onStep: (step) => append(step),
  onTerminal: ({ status }) => console.log('done', status),  // auto-closes
});
// sub.close() to stop early

// Continue the conversation — chains via previousInteractionId.
const next = await client.continue(done.id, { message: 'Now list the risks' });
```

`create` · `get` · `list({ conversationId })` · `continue` · `cancel` ·
`pollUntilTerminal` · `watch` · `subscribe`. Errors throw `InteractionsError`
(`.status`, `.message`). `subscribe` opens an Atmosphere WebSocket (fallback
SSE) to the server's per-interaction stream channel: the server replays the
steps captured so far on connect, then pushes each new durable step live;
frames are deduped by sequence and the subscription auto-closes on the terminal
frame.

The Atmosphere Console (`/atmosphere/console/`) ships a full **Interactions** tab
built on this surface; `samples/spring-boot-coding-agent` and
`samples/spring-boot-multi-agent-startup-team` enable it.

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
npm run typecheck

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
- [Documentation](https://atmosphere.github.io/docs/clients/javascript/)
- [Issues](https://github.com/Atmosphere/atmosphere/issues)

---

Maintained by the Atmosphere team.
