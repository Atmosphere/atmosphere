---
title: "Chapter 19: atmosphere.js Client"
description: "TypeScript client library with WebSocket/SSE/long-polling transport negotiation, room protocol, reconnection, and React/Vue/Svelte/React Native integrations."
sidebar:
  order: 19
---

Every Atmosphere server needs a client. The official TypeScript client library, `atmosphere.js`, provides a transport-agnostic API that works in the browser, Node.js, and React Native. It ships with first-class hooks for React, Vue, Svelte, and React Native.

## Installation

```bash
npm install atmosphere.js
```

The package provides ESM, CommonJS, and TypeScript declarations:

```typescript
import { Atmosphere } from 'atmosphere.js';
```

Framework-specific imports use subpath exports:

```typescript
import { useAtmosphere, useRoom, usePresence } from 'atmosphere.js/react';
import { useAtmosphere, useRoom } from 'atmosphere.js/vue';
import { createAtmosphereStore, createRoomStore } from 'atmosphere.js/svelte';
import { useAtmosphereRN, setupReactNative } from 'atmosphere.js/react-native';
```

## Core API

### The Atmosphere Class

The `Atmosphere` class manages subscriptions with automatic transport selection and fallback:

```typescript
const atmosphere = new Atmosphere({
  logLevel: 'info',
  defaultTransport: 'websocket',
  fallbackTransport: 'sse',
});
```

### Subscribing

Call `subscribe()` with a request configuration and event handlers:

```typescript
const subscription = await atmosphere.subscribe<ChatMessage>(
  {
    url: 'http://localhost:8080/atmosphere/chat',
    transport: 'websocket',
    fallbackTransport: 'sse',
    reconnect: true,
    maxReconnectOnClose: 5,
    reconnectInterval: 2000,
    contentType: 'application/json',
  },
  {
    open: (response) => {
      console.log('Connected via', response.transport);
    },
    message: (response) => {
      console.log('Received:', response.responseBody);
    },
    close: (response) => {
      console.log('Disconnected');
    },
    error: (error) => {
      console.error('Error:', error);
    },
    transportFailure: (reason, request) => {
      console.warn(`${request.transport} failed: ${reason}, trying fallback`);
    },
    reconnect: (request, response) => {
      console.log('Reconnecting...');
    },
    failureToReconnect: (request, response) => {
      console.error('All reconnection attempts exhausted');
    },
  }
);
```

### Sending Messages

Use the subscription's `push()` method:

```typescript
subscription.push('Hello, world!');
subscription.push({ author: 'Alice', text: 'Hello' });
subscription.push(new ArrayBuffer(16));
```

### Closing

```typescript
await subscription.close();
```

## Transport Types

atmosphere.js supports four transport types:

| Transport | Type | Description |
|-----------|------|-------------|
| `websocket` | `WebSocketTransport` | Full-duplex, lowest latency. Default. |
| `sse` | `SSETransport` | Server-Sent Events. Server-to-client streaming. |
| `long-polling` | `LongPollingTransport` | HTTP long-polling. Widest compatibility. |
| `streaming` | `StreamingTransport` | HTTP streaming via chunked transfer. |

Transport negotiation is automatic: if the primary transport fails, the client falls back to the `fallbackTransport` and notifies via the `transportFailure` handler.

## Request Configuration

The `AtmosphereRequest` interface defines all connection options:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `url` | string | (required) | Server endpoint URL |
| `transport` | TransportType | `'websocket'` | Primary transport |
| `fallbackTransport` | TransportType | -- | Fallback if primary fails |
| `reconnect` | boolean | `true` | Auto-reconnect on disconnect |
| `reconnectInterval` | number | -- | Delay between reconnect attempts (ms) |
| `maxReconnectOnClose` | number | -- | Max reconnection attempts |
| `contentType` | string | -- | Content type for messages |
| `timeout` | number | -- | Request timeout (ms) |
| `connectTimeout` | number | -- | Connection timeout (ms) |
| `trackMessageLength` | boolean | -- | Enable message length tracking |
| `enableProtocol` | boolean | -- | Enable Atmosphere protocol |
| `headers` | Record | -- | Custom HTTP headers |
| `sessionToken` | string | -- | Durable session token (sent as `X-Atmosphere-Session-Token`) |
| `heartbeat` | object | -- | Client/server heartbeat intervals |

## Connection States

The subscription state reflects the connection lifecycle:

| State | Description |
|-------|-------------|
| `disconnected` | Not connected |
| `connecting` | Connection in progress |
| `connected` | Connection established |
| `reconnecting` | Attempting to reconnect |
| `suspended` | Connection suspended |
| `closed` | Connection closed |
| `error` | Connection error |

## AtmosphereRooms API

The `AtmosphereRooms` class provides a high-level room API with presence tracking. It works with the server-side `RoomManager` and `RoomInterceptor`.

```typescript
import { Atmosphere, AtmosphereRooms } from 'atmosphere.js';

const atmosphere = new Atmosphere();
const rooms = new AtmosphereRooms(atmosphere, {
    url: '/atmosphere/chat',
    transport: 'websocket',
});

const lobby = await rooms.join('lobby', { id: 'alice' }, {
    message: (data, member) => console.log(`${member.id}: ${data}`),
    join: (event) => console.log(`${event.member.id} joined`),
    leave: (event) => console.log(`${event.member.id} left`),
});

lobby.broadcast('Hello!');
lobby.sendTo('bob', 'Direct message');
lobby.members;  // Map of member ID → RoomMember
lobby.leave();

// Leave all rooms and close the connection
await rooms.leaveAll();
```

### AtmosphereRooms Methods

| Method | Description |
|--------|-------------|
| `join(roomName, member, handlers)` | Join a room and return a `Promise<RoomHandle>` |
| `leave(roomName)` | Leave a specific room |
| `leaveAll()` | Leave all rooms and close the connection |
| `room(name)` | Get a `RoomHandle` by name (or `undefined`) |
| `joinedRooms()` | List all joined room names |

### Room Handle

The `RoomHandle` returned by `rooms.join()` provides:

| Property/Method | Description |
|----------------|-------------|
| `name` | The room name |
| `members` | Current members (`ReadonlyMap<string, RoomMember>`) |
| `broadcast(data)` | Broadcast to all members |
| `sendTo(memberId, data)` | Send a direct message |
| `leave()` | Leave the room |

### Room Message Protocol

Messages exchanged between client and server follow the `RoomMessage` envelope format:

```typescript
interface RoomMessage {
  type: 'join' | 'leave' | 'broadcast' | 'direct' | 'presence';
  room: string;
  data?: unknown;
  target?: string;        // for direct messages
  member?: RoomMember;
}
```

## Framework Hooks

atmosphere.js ships first-class hooks for React, Vue, and Svelte that manage the full Atmosphere lifecycle -- connection, reconnection, cleanup on unmount, and reactive state. The hooks are tree-shakeable sub-path imports; only the framework you import gets bundled.

### API Summary

| Hook / Store | React | Vue | Svelte |
|-------------|-------|-----|--------|
| Raw subscription | `useAtmosphere` | `useAtmosphere` | `createAtmosphereStore` |
| Room (messages + presence) | `useRoom` | `useRoom` | `createRoomStore` |
| Presence only | `usePresence` | `usePresence` | `createPresenceStore` |
| Provider / context | `AtmosphereProvider` | *(none needed)* | *(none needed)* |

All hooks handle automatic connection on mount/first subscriber, automatic cleanup on unmount/last unsubscribe, reconnection via Atmosphere's built-in reconnect, and full TypeScript generics for message types.

### React

Import from `atmosphere.js/react`. All React hooks require an `AtmosphereProvider` ancestor.

#### AtmosphereProvider

Wrap your app with the provider to share an `Atmosphere` instance:

```tsx
import { AtmosphereProvider } from 'atmosphere.js/react';

function App() {
  return (
    <AtmosphereProvider config={{ logLevel: 'info' }}>
      <ChatRoom />
    </AtmosphereProvider>
  );
}
```

You can also pass a pre-built instance:

```tsx
<AtmosphereProvider instance={myAtmosphere}>
```

#### useAtmosphere

Low-level hook for a raw Atmosphere subscription. Connects on mount, disconnects on unmount, re-connects when URL or transport changes.

```tsx
import { useAtmosphere } from 'atmosphere.js/react';

function Notifications() {
  const { data, state, push, error } = useAtmosphere<Notification>({
    request: { url: '/atmosphere/notifications', transport: 'websocket' },
  });

  if (state === 'connected' && data) {
    return <div>{data.text}</div>;
  }
  return <div>Connecting...</div>;
}
```

**Return type:**

| Field | Type | Description |
|-------|------|-------------|
| `subscription` | `Subscription \| null` | The active subscription (null before connected) |
| `state` | `ConnectionState` | `'disconnected'` / `'connected'` / `'reconnecting'` / `'closed'` / `'error'` |
| `data` | `T \| null` | Last received message (generic) |
| `error` | `Error \| null` | Last error |
| `push` | `(msg) => void` | Send a message (string, object, or ArrayBuffer) |

**Options:**

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `request` | `AtmosphereRequest` | *(required)* | Connection config (url, transport, etc.) |
| `enabled` | `boolean` | `true` | Set to `false` to defer connection |

#### useRoom

Joins a room and tracks members, messages, and presence:

```tsx
import { useRoom } from 'atmosphere.js/react';

interface ChatMessage {
  text: string;
}

function Chat() {
  const { joined, members, messages, broadcast, sendTo, error } = useRoom<ChatMessage>({
    request: { url: '/atmosphere/room', transport: 'websocket' },
    room: 'lobby',
    member: { id: 'alice', metadata: { name: 'Alice' } },
  });

  return (
    <div>
      <p>{joined ? `Online: ${members.length}` : 'Joining...'}</p>
      <ul>
        {messages.map((m, i) => (
          <li key={i}><b>{m.member.id}</b>: {m.data.text}</li>
        ))}
      </ul>
      <button onClick={() => broadcast({ text: 'Hello!' })}>Send</button>
    </div>
  );
}
```

**Return type:**

| Field | Type | Description |
|-------|------|-------------|
| `joined` | `boolean` | Whether the room has been joined |
| `members` | `RoomMember[]` | Current room members |
| `messages` | `Array<{ data: T; member: RoomMember }>` | Messages received (append-only) |
| `broadcast` | `(data: T) => void` | Broadcast to all room members |
| `sendTo` | `(memberId: string, data: T) => void` | Direct message to one member |
| `error` | `Error \| null` | Last error |

#### usePresence

Convenience wrapper around `useRoom` that exposes only presence state, no messages:

```tsx
import { usePresence } from 'atmosphere.js/react';

function OnlineIndicator() {
  const { members, count, isOnline } = usePresence({
    request: { url: '/atmosphere/room', transport: 'websocket' },
    room: 'lobby',
    member: { id: currentUser.id },
  });

  return (
    <div>
      <span>{count} online</span>
      {isOnline('bob') && <span>Bob is here!</span>}
    </div>
  );
}
```

**Return type:**

| Field | Type | Description |
|-------|------|-------------|
| `joined` | `boolean` | Whether we have joined the room |
| `members` | `RoomMember[]` | Current online members |
| `count` | `number` | Number of members online |
| `isOnline` | `(memberId: string) => boolean` | Check if a specific member is online |

### Vue

Vue composables work without a provider -- pass an `Atmosphere` instance or let each composable create one. All return values are Vue `Ref` objects and update reactively in templates and watchers.

#### useAtmosphere

```vue
<script setup lang="ts">
import { useAtmosphere } from 'atmosphere.js/vue';

const { data, state, push } = useAtmosphere<ChatMessage>({
  url: '/atmosphere/chat',
  transport: 'websocket',
});
</script>

<template>
  <div>State: {{ state }}</div>
  <div v-if="data">{{ data }}</div>
  <button @click="push(JSON.stringify({ text: 'Hello' }))">Send</button>
</template>
```

Returns the same fields as the React `useAtmosphere` hook, but as Vue `Ref` values (reactive).

#### useRoom

```vue
<script setup lang="ts">
import { useRoom } from 'atmosphere.js/vue';

const { joined, members, messages, broadcast, sendTo } = useRoom<ChatMessage>(
  { url: '/atmosphere/room', transport: 'websocket' },
  'lobby',
  { id: 'alice' },
);
</script>

<template>
  <div v-if="joined">
    <p>{{ members.length }} members online</p>
    <div v-for="msg in messages" :key="msg.data.text">
      <b>{{ msg.member.id }}</b>: {{ msg.data.text }}
    </div>
    <button @click="broadcast({ text: 'Hello!' })">Send</button>
  </div>
  <div v-else>Joining room...</div>
</template>
```

**Signature:** `useRoom<T>(request, roomName, member, instance?)`

All returned values (`joined`, `members`, `messages`, `error`) are Vue `Ref` objects. Cleanup is automatic via `onUnmounted`.

#### usePresence

```vue
<script setup lang="ts">
import { usePresence } from 'atmosphere.js/vue';

const { members, count, isOnline } = usePresence(
  { url: '/atmosphere/room', transport: 'websocket' },
  'lobby',
  { id: currentUser.id },
);
</script>

<template>
  <span>{{ count }} online</span>
</template>
```

`count` is a Vue `computed` ref -- automatically recalculated when members change.

### Svelte

Svelte hooks use the Svelte store contract (`subscribe` method). Use `$store` auto-subscription syntax. The store auto-connects when the first subscriber appears and disconnects when all subscribers are gone.

#### createAtmosphereStore

```svelte
<script>
  import { createAtmosphereStore } from 'atmosphere.js/svelte';

  const { store: chat, push } = createAtmosphereStore({
    url: '/atmosphere/chat',
    transport: 'websocket',
  });
  // $chat.state, $chat.data, $chat.error
</script>

<p>State: {$chat.state}</p>
{#if $chat.data}
  <p>{JSON.stringify($chat.data)}</p>
{/if}
<button on:click={() => push(JSON.stringify({ text: 'Hello' }))}>Send</button>
```

**Store state:**

| Field | Type | Description |
|-------|------|-------------|
| `state` | `ConnectionState` | Connection state |
| `data` | `T \| null` | Last received message |
| `error` | `Error \| null` | Last error |
| `subscription` | `Subscription \| null` | Active subscription |

#### createRoomStore

```svelte
<script>
  import { createRoomStore } from 'atmosphere.js/svelte';

  const { store: lobby, broadcast, sendTo } = createRoomStore(
    { url: '/atmosphere/room', transport: 'websocket' },
    'lobby',
    { id: 'alice' },
  );
</script>

{#if $lobby.joined}
  <p>{$lobby.members.length} members online</p>
  {#each $lobby.messages as msg}
    <p><b>{msg.member.id}</b>: {msg.data}</p>
  {/each}
  <button on:click={() => broadcast('Hello!')}>Send</button>
{:else}
  <p>Joining room...</p>
{/if}
```

**Store state:**

| Field | Type | Description |
|-------|------|-------------|
| `joined` | `boolean` | Whether the room has been joined |
| `members` | `RoomMember[]` | Current room members |
| `messages` | `Array<{ data: T; member: RoomMember }>` | Received messages |
| `error` | `Error \| null` | Last error |

#### createPresenceStore

```svelte
<script>
  import { createPresenceStore } from 'atmosphere.js/svelte';

  const presence = createPresenceStore(
    { url: '/atmosphere/room', transport: 'websocket' },
    'lobby',
    { id: currentUser.id },
  );
</script>

<span>{$presence.count} online</span>
{#each $presence.members as m}
  <span>{m.id}</span>
{/each}
```

**Store state:**

| Field | Type | Description |
|-------|------|-------------|
| `joined` | `boolean` | Whether we have joined the room |
| `members` | `RoomMember[]` | Current online members |
| `count` | `number` | Number of members online |

## React Native Support

Import from `atmosphere.js/react-native`:

```typescript
import { setupReactNative, useAtmosphereRN, useStreamingRN } from 'atmosphere.js/react-native';

// Call once at app startup
setupReactNative();

// Or with optional NetInfo for network-aware reconnection
import NetInfo from '@react-native-community/netinfo';
setupReactNative({ netInfo: NetInfo });
```

React Native hooks (`useAtmosphereRN`, `useStreamingRN`) handle app lifecycle events (background/foreground) and optional NetInfo integration for network-aware reconnection. The core hooks (`useRoom`, `usePresence`) are re-exported and work as-is in React Native.

## Durable Session Tokens

atmosphere.js supports durable sessions (see [Chapter 17](/docs/tutorial/17-durable-sessions/)) via the `sessionToken` request property:

```typescript
const sub = await atmosphere.subscribe({
  url: '/atmosphere/chat',
  transport: 'websocket',
  sessionToken: localStorage.getItem('atmosphere-session'),
}, {
  open: (response) => {
    // Store the server-assigned token for reconnection
    const token = response.headers['X-Atmosphere-Session-Token'];
    if (token) localStorage.setItem('atmosphere-session', token);
  },
});
```

## Client-side Interceptors

Interceptors transform messages before sending or after receiving. They are applied in order for outgoing messages and in reverse order for incoming messages, like a middleware stack.

```typescript
const atmosphere = new Atmosphere({
    interceptors: [{
        name: 'json-wrapper',
        onOutgoing: (data) => JSON.stringify({ payload: data }),
        onIncoming: (body) => JSON.parse(body).payload,
    }],
});
```

You can chain multiple interceptors. Each one receives the output of the previous:

```typescript
const atmosphere = new Atmosphere({
  interceptors: [
    {
      name: 'json',
      onOutgoing: (data) => typeof data === 'string' ? data : JSON.stringify(data),
      onIncoming: (body) => JSON.parse(body),
    },
  ],
});
```

## Pairing with @ManagedService

The client connects to server endpoints defined with `@ManagedService`. A minimal server-client pair:

**Server (Java):**

```java
@ManagedService(path = "/atmosphere/chat")
public class Chat {

    @Inject private AtmosphereResource r;

    @Ready
    public void onReady() {
        // Client connected
    }

    @Message
    public String onMessage(String message) {
        return message; // Echo to all subscribers
    }
}
```

**Client (TypeScript):**

```typescript
const sub = await atmosphere.subscribe(
  { url: '/atmosphere/chat', transport: 'websocket' },
  { message: (res) => console.log(res.responseBody) }
);

sub.push('Hello from atmosphere.js');
```

## API Reference

Complete type reference for the atmosphere.js client library.

### Atmosphere

Main client class. Manages subscriptions with automatic transport fallback.

```typescript
const atm = new Atmosphere({
    logLevel: 'info',                  // 'debug' | 'info' | 'warn' | 'error' | 'silent'
    defaultTransport: 'websocket',     // Default transport for all subscriptions
    fallbackTransport: 'long-polling', // Default fallback
    interceptors: [],                  // Client-side interceptors
});

atm.version;           // '5.0.0'
```

| Method | Description |
|--------|-------------|
| `subscribe(request, handlers)` | Connect to an Atmosphere endpoint. Returns `Promise<Subscription>`. |
| `closeAll()` | Close all active subscriptions |
| `getSubscriptions()` | Get all active subscriptions (`Map<string, Subscription>`) |

### AtmosphereRequest

```typescript
{
    url: string;                           // Endpoint URL (required)
    transport: TransportType;              // 'websocket' | 'sse' | 'long-polling' | 'streaming'
    fallbackTransport?: TransportType;     // Fallback if primary fails
    contentType?: string;                  // Default: 'text/plain'
    trackMessageLength?: boolean;          // Length-prefix messages (recommended: true)
    messageDelimiter?: string;             // Default: '|'
    enableProtocol?: boolean;              // Atmosphere protocol handshake
    timeout?: number;                      // Inactivity timeout (ms)
    connectTimeout?: number;               // Connection timeout (ms)
    reconnect?: boolean;                   // Auto-reconnect (default: true)
    reconnectInterval?: number;            // Base reconnect delay (ms)
    maxReconnectOnClose?: number;          // Max reconnect attempts (default: 5)
    maxRequest?: number;                   // Max long-polling request cycles
    headers?: Record<string, string>;      // Custom HTTP headers
    withCredentials?: boolean;             // Include cookies (CORS)
    heartbeat?: {
        client?: number;                   // Client heartbeat interval (ms)
        server?: number;                   // Expected server heartbeat (ms)
    };
}
```

### SubscriptionHandlers

```typescript
{
    open?: (response: AtmosphereResponse) => void;
    message?: (response: AtmosphereResponse) => void;
    close?: (response: AtmosphereResponse) => void;
    error?: (error: Error) => void;
    reopen?: (response: AtmosphereResponse) => void;
    reconnect?: (request: AtmosphereRequest, response: AtmosphereResponse) => void;
    transportFailure?: (reason: string, request: AtmosphereRequest) => void;
    clientTimeout?: (request: AtmosphereRequest) => void;
    failureToReconnect?: (request: AtmosphereRequest, response: AtmosphereResponse) => void;
}
```

### Subscription

Returned by `subscribe()`.

```typescript
sub.id;                    // string — unique subscription ID
sub.state;                 // ConnectionState

sub.push(message);         // Send string | object | ArrayBuffer
await sub.close();         // Disconnect
sub.suspend();             // Pause receiving
await sub.resume();        // Resume receiving

sub.on(event, handler);    // Add event listener
sub.off(event, handler);   // Remove event listener
```

### ConnectionState

```typescript
'disconnected' | 'connecting' | 'connected' | 'reconnecting' | 'suspended' | 'closed' | 'error'
```

### RoomHandle

```typescript
handle.name;                         // Room name
handle.members;                      // ReadonlyMap<string, RoomMember>
handle.broadcast(data);              // Send to all members
handle.sendTo(memberId, data);       // Direct message
handle.leave();                      // Leave the room
```

### RoomMember

```typescript
{ id: string; metadata?: Record<string, unknown>; }
```

### PresenceEvent

```typescript
{ type: 'join' | 'leave'; room: string; member: RoomMember; timestamp: number; }
```

### RoomMessage (Wire Format)

```typescript
{ type: 'join' | 'leave' | 'broadcast' | 'direct' | 'presence';
  room: string;
  data?: unknown;
  member?: RoomMember;
  target?: string; }
```

### AtmosphereInterceptor (Client-Side)

```typescript
{
    name?: string;
    onOutgoing?: (data: string | ArrayBuffer) => string | ArrayBuffer;
    onIncoming?: (body: string) => string;
}
```

Applied in order for outgoing; reverse order for incoming (middleware stack pattern).

## Java Client (wAsync)

`atmosphere-wasync` is an async Java client for connecting to Atmosphere endpoints. It supports WebSocket, SSE, streaming, long-polling, and gRPC transports, powered by `java.net.http` (JDK 21+).

### Dependency

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-wasync</artifactId>
    <version>LATEST</version> <!-- check Maven Central for latest -->
</dependency>
```

### Quick Start

```java
Client client = Client.newClient();

Request request = client.newRequestBuilder()
    .uri("ws://localhost:8080/atmosphere/chat")
    .transport(Request.TRANSPORT.WEBSOCKET)
    .build();

Socket socket = client.create();
socket.on(Event.OPEN, r -> System.out.println("Connected"))
      .on(Event.MESSAGE, msg -> System.out.println("Received: " + msg))
      .on(Event.ERROR, e -> ((Throwable) e).printStackTrace())
      .on(Event.CLOSE, r -> System.out.println("Disconnected"));

socket.open(request);
socket.fire("Hello from Java!");
```

### Socket API

The `Socket` is the connection handle -- register event handlers, connect, and send messages:

```java
// Event handlers (chainable)
socket.on(Event.OPEN, handler)
      .on(Event.MESSAGE, handler)
      .on(Event.ERROR, handler)
      .on(Event.CLOSE, handler);

// Connect
socket.open(request);

// Send data (returns CompletableFuture<Void>)
socket.fire("text message");
socket.fire(byteArray);

// State
socket.status();   // Socket.STATUS — OPEN, CLOSE, ERROR, REOPENED

// Disconnect
socket.close();
```

### Events

| Event | When Fired |
|-------|-----------|
| `Event.OPEN` | Connection established |
| `Event.MESSAGE` | Data received |
| `Event.ERROR` | Error occurred |
| `Event.CLOSE` | Connection closed |
| `Event.REOPENED` | Reconnected after disconnect |
| `Event.HEADERS` | Response headers received |
| `Event.STATUS` | HTTP status received |
| `Event.TRANSPORT` | Transport negotiated |

### Transports

| Transport | URI Scheme | Description |
|-----------|-----------|-------------|
| `WEBSOCKET` | `ws://` / `wss://` | Full-duplex WebSocket |
| `SSE` | `http://` / `https://` | Server-Sent Events |
| `STREAMING` | `http://` / `https://` | HTTP streaming |
| `LONG_POLLING` | `http://` / `https://` | Long-polling |
| `GRPC` | `grpc://` | gRPC bidirectional streaming |

### Transport Fallback

```java
Request request = client.newRequestBuilder()
    .uri("http://localhost:8080/atmosphere/chat")
    .transport(Request.TRANSPORT.WEBSOCKET)
    .fallbackTransport(Request.TRANSPORT.LONG_POLLING)
    .build();
```

### Encoders and Decoders

Transform objects before sending and after receiving:

```java
// Encoder: Java object → wire format
public class JacksonEncoder implements Encoder<Message, String> {
    @Override
    public String encode(Message m) {
        return objectMapper.writeValueAsString(m);
    }
}

// Decoder: wire format → Java object
public class JacksonDecoder implements Decoder<String, Message> {
    @Override
    public Message decode(String s) {
        return objectMapper.readValue(s, Message.class);
    }
}

// Register on request
Request request = client.newRequestBuilder()
    .uri("ws://localhost:8080/chat")
    .transport(Request.TRANSPORT.WEBSOCKET)
    .encoder(new JacksonEncoder())
    .decoder(new JacksonDecoder())
    .build();
```

### gRPC Transport

Connect to an Atmosphere gRPC server:

```java
Client client = Client.newClient();
Request request = client.newRequestBuilder()
    .uri("grpc://localhost:9090/chat")
    .transport(Request.TRANSPORT.GRPC)
    .build();

Socket socket = client.create();
socket.on(Event.MESSAGE, msg -> System.out.println("Received: " + msg))
      .open(request);

socket.fire("Hello via gRPC!");
```

## Summary

- atmosphere.js is a TypeScript client library supporting WebSocket, SSE, long-polling, and streaming transports
- Transport negotiation and fallback are automatic
- The `AtmosphereRooms` API provides join/leave/broadcast/direct messaging with presence tracking
- Client-side interceptors transform messages in a middleware stack pattern
- Framework-specific hooks are available for React (`useAtmosphere`, `useRoom`, `usePresence`), Vue (`useAtmosphere`, `useRoom`, `usePresence`), Svelte (`createAtmosphereStore`, `createRoomStore`, `createPresenceStore`), and React Native (`useAtmosphereRN`)
- Durable session tokens are supported via the `sessionToken` request property
- The Java client (wAsync) supports WebSocket, SSE, streaming, long-polling, and gRPC transports with encoders/decoders
- The client pairs with `@ManagedService` on the server with no special configuration

Next up: [Chapter 20: gRPC & Kotlin](/docs/tutorial/20-grpc-kotlin/) covers the gRPC transport and virtual threads.
