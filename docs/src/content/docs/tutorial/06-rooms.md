---
title: "Rooms & Presence"
description: "Higher-level room abstraction with presence tracking, member metadata, message history, and AI virtual members"
sidebar:
  order: 6
---

The [previous chapter](/docs/tutorial/05-broadcaster/) covered Broadcasters -- the low-level pub/sub primitive in Atmosphere. Broadcasters are powerful but sometimes too low-level: they deal in `AtmosphereResource` UUIDs (connection-scoped, unstable across reconnects) and have no built-in concept of "who is in the channel" or "what did I miss."

The **Room** API, introduced in Atmosphere 4.0, sits on top of Broadcasters and adds:

- **Presence tracking** -- know who joins and leaves, with metadata (display name, avatar, role)
- **Stable member identity** -- `RoomMember` IDs survive reconnects
- **Message history** -- new joiners automatically receive the last N messages
- **Virtual members** -- AI agents that participate like human members
- **Direct messaging** -- send to a specific member by UUID
- **Authorization** -- control who can join, broadcast, or send direct messages

## RoomManager

All rooms are created and managed through `RoomManager`. You get a singleton instance per `AtmosphereFramework`:

```java
RoomManager rooms = RoomManager.getOrCreate(framework);
```

This is the idiomatic approach -- `getOrCreate` returns the same `RoomManager` for the same framework, so all parts of your application share the same room registry. It also stores the instance in the servlet context for cross-component access.

If you need an isolated `RoomManager` (for testing, or for a separate room namespace), use `create`:

```java
RoomManager isolated = RoomManager.create(framework);
```

### Listing and Querying Rooms

```java
// Get all active rooms
Collection<Room> allRooms = rooms.all();

// Check if a room exists
boolean exists = rooms.exists("lobby");

// Count active rooms
int count = rooms.count();
```

### Destroying Rooms

```java
// Destroy a single room (removes all members, releases Broadcaster)
boolean wasDestroyed = rooms.destroy("lobby");

// Destroy all rooms
rooms.destroyAll();
```

## Creating and Joining Rooms

### Get or Create a Room

```java
Room lobby = rooms.room("lobby");
```

The `room()` method is lazy: it creates the room on first access and returns the existing room on subsequent calls. Under the hood, it creates a Broadcaster at `/atmosphere/room/lobby`.

### Joining a Room

The simplest join -- just an `AtmosphereResource`:

```java
Room lobby = rooms.room("lobby");
lobby.join(resource);
```

Both `join` and `leave` return the `Room` itself, so calls can be chained:

```java
rooms.room("lobby").join(resource).enableHistory(50);
```

### Joining with Member Metadata

In most applications, you want to associate application-level identity with each connection:

```java
var member = new RoomMember("alice", Map.of(
    "displayName", "Alice Chen",
    "avatar", "https://example.com/alice.png",
    "role", "moderator"
));

lobby.join(resource, member);
```

`RoomMember` is a record with two fields:

```java
public record RoomMember(String id, Map<String, Object> metadata) {
    // id: application-level identifier (e.g. username), stable across reconnects
    // metadata: arbitrary key-value pairs, defensively copied to be unmodifiable
}
```

The `id` is distinct from `AtmosphereResource.uuid()`. The resource UUID changes every time the client reconnects; the `RoomMember.id` stays the same. This distinction is important for presence tracking and for clients that need to display consistent identities.

A convenience constructor is available when you don't need metadata:

```java
var member = new RoomMember("alice");
lobby.join(resource, member);
```

### Leaving a Room

```java
lobby.leave(resource);
```

Leaving fires a `LEAVE` presence event and removes the resource from the underlying Broadcaster. If the client disconnects (network failure, tab close), Atmosphere automatically removes the resource, which triggers the leave.

### Querying Members

```java
// All connected resources
Set<AtmosphereResource> connected = lobby.members();

// Count
int online = lobby.size();

// Is anyone here?
boolean empty = lobby.isEmpty();

// Is a specific resource in this room?
boolean isMember = lobby.contains(resource);

// Application-level member info (UUID -> RoomMember)
Map<String, RoomMember> info = lobby.memberInfo();

// Get member info for a specific resource
Optional<RoomMember> alice = lobby.memberOf(resource);
alice.ifPresent(m -> log.info("Member: {} ({})", m.id(), m.metadata()));
```

## Broadcasting in Rooms

### Broadcast to Everyone

```java
lobby.broadcast("Hello, everyone!");
```

Both `broadcast` methods return a `Future<Object>` that completes when the broadcast is delivered. This message is delivered to all members of the room, including virtual members.

### Broadcast to Everyone Except the Sender

```java
// The sender doesn't receive their own message
lobby.broadcast("I just arrived!", senderResource);
```

This is the common pattern for chat: the client already knows what it sent, so you exclude it from the fan-out.

### Direct Message to a Specific Member

```java
// Send a private message to a specific member by their resource UUID
lobby.sendTo("Hey, just you!", targetUuid);
```

## Presence Tracking

Presence is the killer feature of the Room API. Register a listener to be notified whenever a member joins or leaves:

```java
lobby.onPresence(event -> {
    switch (event.type()) {
        case JOIN -> log.info("{} joined room '{}'",
            event.memberInfo() != null ? event.memberInfo().id() : event.member().uuid(),
            event.room().name());
        case LEAVE -> log.info("{} left room '{}'",
            event.memberInfo() != null ? event.memberInfo().id() : event.member().uuid(),
            event.room().name());
    }
});
```

### The PresenceEvent Record

```java
public record PresenceEvent(Type type, Room room, AtmosphereResource member, RoomMember memberInfo) {

    public enum Type { JOIN, LEAVE }

    // true if this event is for a virtual (non-connection) member
    public boolean isVirtual();
}
```

| Field | Description |
|-------|-------------|
| `type()` | `JOIN` or `LEAVE` |
| `room()` | The room where the event occurred |
| `member()` | The `AtmosphereResource` (null for virtual members) |
| `memberInfo()` | The `RoomMember` with id and metadata (null if not provided at join) |

`PresenceEvent` has three constructors:

- `(Type, Room, AtmosphereResource, RoomMember)` -- full form
- `(Type, Room, AtmosphereResource)` -- convenience without member info
- `(Type, Room, RoomMember)` -- for virtual member events (no `AtmosphereResource`)

### Multiple Presence Listeners

You can register multiple listeners. They are all invoked on every presence event:

```java
// Listener 1: logging
lobby.onPresence(event -> log.info("Presence: {} {}", event.type(), event.memberInfo()));

// Listener 2: auto-cleanup
lobby.onPresence(event -> {
    if (event.type() == PresenceEvent.Type.LEAVE && lobby.isEmpty()) {
        log.info("Room '{}' is empty, considering cleanup", lobby.name());
    }
});
```

## Message History

New joiners in a chat often see an empty screen. Message history fixes this by replaying the last N messages to anyone who joins.

### Enabling History

```java
Room lobby = rooms.room("lobby");
lobby.enableHistory(100); // keep the last 100 messages
```

When a new member joins, they automatically receive up to 100 recent messages, letting them catch up on the conversation.

Call `enableHistory` once when you set up the room. The history size is the maximum number of messages retained -- older messages are evicted as new ones arrive.

## AI Virtual Members

A `VirtualRoomMember` is a non-connection participant -- an AI agent, bot, or server-side service that receives room messages and can respond. Unlike human members backed by WebSocket or SSE connections, virtual members have no underlying transport. They participate purely through the `onMessage` callback.

### The VirtualRoomMember Interface

```java
public interface VirtualRoomMember {

    String id();

    void onMessage(Room room, String senderId, Object message);

    default Map<String, Object> metadata() {
        return Map.of();
    }
}
```

| Method | Description |
|--------|-------------|
| `id()` | Stable identifier for this virtual member (e.g., "assistant", "bot-1") |
| `onMessage(room, senderId, message)` | Called when a message is broadcast in the room. Must be thread-safe. |
| `metadata()` | Optional metadata for presence events (e.g., display name, avatar) |

### Custom Virtual Members

You can implement `VirtualRoomMember` directly for bots, notification services, or integrations:

```java
public class WelcomeBot implements VirtualRoomMember {

    @Override
    public String id() {
        return "welcome-bot";
    }

    @Override
    public void onMessage(Room room, String senderId, Object message) {
        // Only respond to join-like messages
        if (message.toString().contains("joined")) {
            room.broadcast("Welcome! Type /help for available commands.");
        }
    }

    @Override
    public Map<String, Object> metadata() {
        return Map.of("type", "bot", "displayName", "Welcome Bot");
    }
}
```

Register it:

```java
room.joinVirtual(new WelcomeBot());
```

### LlmRoomMember: AI-Powered Virtual Member

The `atmosphere-ai` module provides `LlmRoomMember`, which connects an LLM to a room. When any human member sends a message, the LLM processes it and broadcasts a response back to the room:

```java
import org.atmosphere.ai.LlmRoomMember;

var assistant = new LlmRoomMember("assistant", client, "gemini-2.5-flash",
    "You are a helpful coding assistant. Keep responses concise.");

Room devChat = rooms.room("dev-chat");
devChat.joinVirtual(assistant);
```

### Managing Virtual Members

```java
// List all virtual members
Set<VirtualRoomMember> virtuals = room.virtualMembers();

// Remove a virtual member
room.leaveVirtual(assistant);
```

Removing a virtual member fires a `LEAVE` presence event with `isVirtual() == true`.

## RoomProtocolInterceptor

The `RoomProtocolInterceptor` bridges the atmosphere.js client room protocol to the server-side Room API. It intercepts JSON messages from clients, decodes them via `RoomProtocolCodec`, and routes them to the appropriate `Room` operations.

It handles four message types:

| Message | Action |
|---------|--------|
| `Join` | Calls `room.join()`, sends join ack with member list, broadcasts presence, replays cached messages |
| `Leave` | Calls `room.leave()`, broadcasts leave presence |
| `Broadcast` | Calls `room.broadcast()` with sender exclusion |
| `Direct` | Resolves member ID to resource UUID, calls `room.sendTo()` |

The interceptor uses JDK 21 pattern matching to dispatch messages:

```java
switch (message) {
    case RoomProtocolMessage.Join join -> handleJoin(r, join);
    case RoomProtocolMessage.Leave leave -> handleLeave(r, leave);
    case RoomProtocolMessage.Broadcast broadcast -> handleBroadcast(r, broadcast);
    case RoomProtocolMessage.Direct direct -> handleDirect(r, direct);
}
```

It runs with `BEFORE_DEFAULT` priority so it processes messages before `BroadcastOnPostAtmosphereInterceptor`, and it returns `Action.CANCELLED` after handling a room protocol message to prevent downstream interceptors from re-broadcasting.

## RoomInterceptor: URL-Based Auto-Join

The `RoomInterceptor` provides automatic room joining based on URL path. When a client connects to a URL matching the base path, they are automatically joined to the room whose name is extracted from the remaining path segment:

```java
RoomManager rooms = RoomManager.create(framework);
framework.interceptor(new RoomInterceptor(rooms));
// Requests to /room/lobby auto-join the "lobby" room
// Requests to /room/general auto-join the "general" room
```

You can customize the base path:

```java
framework.interceptor(new RoomInterceptor(rooms, "/chat/"));
// Now /chat/lobby -> room "lobby"
// /chat/support -> room "support"
```

This is useful when you want simple URL-driven room assignment without requiring clients to send an explicit join message via the room protocol.

### Authorization

The `RoomProtocolInterceptor` supports authorization via `@RoomAuth` and `RoomAuthorizer`. Annotate your `AtmosphereHandler` class:

```java
@RoomAuth(authorizer = MyAuthorizer.class)
public class ChatHandler extends OnMessage<String> { ... }
```

The authorizer is a functional interface:

```java
@FunctionalInterface
public interface RoomAuthorizer {
    boolean authorize(AtmosphereResource resource, String roomName, RoomAction action);
}
```

Where `RoomAction` is an enum: `JOIN`, `LEAVE`, `BROADCAST`, `SEND_TO`.

## @RoomService Annotation

For a simplified annotation-driven approach, `@RoomService` marks a class as a room handler. It works like `@ManagedService` but is scoped to a `Room`:

```java
@RoomService(path = "/chat/{roomId}", maxHistory = 100)
public class ChatRoom {

    @Ready
    public void onJoin(AtmosphereResource r) {
        // invoked when a client joins the room
    }

    @Message
    public String onMessage(String message) {
        return message; // broadcast to all room members
    }

    @Disconnect
    public void onLeave(AtmosphereResourceEvent event) {
        // invoked when a client disconnects
    }
}
```

| Attribute | Description | Default |
|-----------|-------------|---------|
| `path` | Mapping path; supports path parameters like `{roomId}` | `"/"` |
| `maxHistory` | Maximum messages to keep in room history (0 = disabled) | `0` |

## Complete Example: Spring Boot Room Setup

The following code is from the `spring-boot-chat` sample (`RoomsConfig.java`):

```java
@Configuration
public class RoomsConfig {

    private static final Logger logger = LoggerFactory.getLogger(RoomsConfig.class);

    private final AtmosphereFramework framework;

    public RoomsConfig(AtmosphereFramework framework) {
        this.framework = framework;
    }

    @Bean
    public RoomManager roomManager() {
        return RoomManager.getOrCreate(framework);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void setupRooms() {
        var interceptor = new RoomProtocolInterceptor();
        interceptor.configure(framework.getAtmosphereConfig());
        framework.interceptor(interceptor);

        RoomManager manager = roomManager();
        Room lobby = manager.room("lobby");
        lobby.enableHistory(50);

        lobby.onPresence(event -> {
            var memberInfo = event.memberInfo();
            var memberId = memberInfo != null ? memberInfo.id() : event.member().uuid();
            logger.info("Room '{}': {} {} (members: {})",
                    event.room().name(),
                    memberId,
                    event.type(),
                    event.room().size());
        });
    }
}
```

Key points:

1. `RoomManager.getOrCreate(framework)` is exposed as a Spring `@Bean` so other components can inject it
2. `RoomProtocolInterceptor` is registered at application startup
3. The lobby room is pre-provisioned with 50-message history
4. Presence events are logged with member identity

## Exposing Rooms via REST

The `ChatRoomsController` from the same sample exposes room data as a REST API:

```java
@RestController
@RequestMapping("/api/rooms")
public class ChatRoomsController {

    private final RoomManager roomManager;

    public ChatRoomsController(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    @GetMapping
    public List<Map<String, Object>> listRooms() {
        return roomManager.all().stream()
                .map(room -> {
                    var map = new HashMap<String, Object>();
                    map.put("name", room.name());
                    map.put("members", room.size());
                    map.put("destroyed", room.isDestroyed());
                    var memberList = room.memberInfo().values().stream()
                            .map(m -> {
                                var mMap = new HashMap<String, Object>();
                                mMap.put("id", m.id());
                                mMap.put("metadata", m.metadata());
                                return (Map<String, Object>) mMap;
                            })
                            .toList();
                    map.put("memberDetails", memberList);
                    return (Map<String, Object>) map;
                })
                .toList();
    }
}
```

This returns JSON like:

```json
[
  {
    "name": "lobby",
    "members": 3,
    "destroyed": false,
    "memberDetails": [
      { "id": "alice", "metadata": { "displayName": "Alice Chen" } },
      { "id": "bob", "metadata": { "displayName": "Bob Smith" } }
    ]
  }
]
```

## Client Framework Hooks

atmosphere.js 5.0 includes room hooks for React, Vue, and Svelte. These hooks manage the full lifecycle -- connection, room join/leave, presence tracking, and reactive state updates. Import from the framework-specific sub-path:

### React

```tsx
import { useRoom } from 'atmosphere.js/react';

function ChatRoom() {
    const { members, messages, broadcast } = useRoom<ChatMessage>({
        request: { url: '/atmosphere/chat', transport: 'websocket' },
        room: 'lobby',
        member: { id: 'alice' },
    });

    return (
        <div>
            <p>Members: {members.map(m => m.id).join(', ')}</p>
            {messages.map((msg, i) => <p key={i}>{msg.member.id}: {msg.data}</p>)}
            <button onClick={() => broadcast({ text: 'Hello!' })}>Send</button>
        </div>
    );
}
```

React hooks require an `AtmosphereProvider` ancestor that holds the shared `Atmosphere` client instance.

### Vue

```vue
<script setup lang="ts">
import { useRoom } from 'atmosphere.js/vue';

const { members, messages, broadcast } = useRoom<ChatMessage>(
    { url: '/atmosphere/chat', transport: 'websocket' },
    'lobby',
    { id: 'alice' },
);
</script>

<template>
  <div v-if="members">
    <p>{{ members.length }} members online</p>
    <div v-for="msg in messages" :key="msg.data.text">
      <b>{{ msg.member.id }}</b>: {{ msg.data.text }}
    </div>
    <button @click="broadcast({ text: 'Hello!' })">Send</button>
  </div>
</template>
```

All returned values are Vue `Ref` objects -- they update reactively in templates and watchers. No provider is needed.

### Svelte

```svelte
<script>
import { createRoomStore } from 'atmosphere.js/svelte';

const { store: lobby, broadcast } = createRoomStore(
    { url: '/atmosphere/chat', transport: 'websocket' },
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

Svelte hooks use the Svelte store contract. Use `$store` auto-subscription syntax for reactive access.

All framework hooks handle automatic connection on mount, cleanup on unmount, reconnection, and full TypeScript generics for message types.

## Room vs. Broadcaster: When to Use Which

| Need | Use |
|------|-----|
| Simple pub/sub with no identity | `Broadcaster` directly |
| Know who is connected (presence) | `Room` |
| Replay recent messages for new joiners | `Room` with `enableHistory()` |
| Application-level member identity (survives reconnect) | `Room` with `RoomMember` |
| AI agents participating in a conversation | `Room` with `VirtualRoomMember` |
| Fine-grained message filtering per subscriber | `Broadcaster` with `PerRequestBroadcastFilter` |
| Custom lifecycle management | `Broadcaster` with `BroadcasterLifeCyclePolicy` |

The Room API does not replace Broadcasters -- it wraps them. Every Room is backed by a Broadcaster at `/atmosphere/room/<name>`. If you need both Room features and Broadcaster features (like custom filters or lifecycle policies), you can access the underlying Broadcaster through the `BroadcasterFactory`:

```java
Room lobby = rooms.room("lobby");
Broadcaster underlying = factory.lookup("/atmosphere/room/lobby");
underlying.getBroadcasterConfig().addFilter(new ProfanityFilter());
```

## Summary

| Concept | Purpose |
|---------|---------|
| `RoomManager` | Singleton registry for creating and managing rooms |
| `Room` | Named group of connections with presence, history, and virtual members |
| `RoomMember` | Application-level identity (id + metadata), stable across reconnects |
| `PresenceEvent` | Notification of JOIN/LEAVE with member info |
| `VirtualRoomMember` | Non-connection participant (bot, AI agent, service) |
| `RoomProtocolInterceptor` | Bridges atmosphere.js room protocol to server-side Room API |
| `@RoomService` | Annotation-driven room handler (like `@ManagedService` for rooms) |
| `RoomAuthorizer` | Functional interface for authorizing room operations |
| `enableHistory(n)` | Replay last N messages to new joiners |

**See also:** [WebSocket Deep Dive](/docs/tutorial/07-websocket/) for direct control over WebSocket frames, binary messages, and protocol-level details. Or jump to [AI & LLM Streaming](/docs/tutorial/09-ai-endpoint/) if you want to build AI endpoints on top of the pub/sub layer.
