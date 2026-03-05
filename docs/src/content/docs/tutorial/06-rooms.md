---
title: "Chapter 6: Rooms & Presence"
description: "Higher-level room abstraction with presence tracking, member metadata, message history, and AI virtual members"
---

The [previous chapter](/docs/tutorial/05-broadcaster/) covered Broadcasters -- the low-level pub/sub primitive in Atmosphere. Broadcasters are powerful but sometimes too low-level: they deal in `AtmosphereResource` UUIDs (connection-scoped, unstable across reconnects) and have no built-in concept of "who is in the channel" or "what did I miss."

The **Room** API, introduced in Atmosphere 4.0, sits on top of Broadcasters and adds:

- **Presence tracking** -- know who joins and leaves, with metadata (display name, avatar, role)
- **Stable member identity** -- `RoomMember` IDs survive reconnects
- **Message history** -- new joiners automatically receive the last N messages
- **Virtual members** -- AI agents that participate like human members
- **Direct messaging** -- send to a specific member by UUID

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
rooms.destroy("lobby");

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

The `id` is distinct from the `AtmosphereResource.uuid()`. The resource UUID changes every time the client reconnects; the `RoomMember.id` stays the same. This distinction is important for presence tracking and for clients that need to display consistent identities.

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

This delivers the message to all members of the room, including virtual members.

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

### Broadcasting Presence to Clients

A common pattern is to broadcast the presence event itself so all clients update their member lists:

```java
lobby.onPresence(event -> {
    var notification = Map.of(
        "type", "presence",
        "event", event.type().name(),
        "memberId", event.memberInfo() != null ? event.memberInfo().id() : "unknown",
        "metadata", event.memberInfo() != null ? event.memberInfo().metadata() : Map.of(),
        "roomSize", event.room().size(),
        "isVirtual", event.isVirtual()
    );
    lobby.broadcast(new ObjectMapper().writeValueAsString(notification));
});
```

### Multiple Presence Listeners

You can register multiple listeners. They are all invoked on every presence event:

```java
// Listener 1: logging
lobby.onPresence(event -> log.info("Presence: {} {}", event.type(), event.memberInfo()));

// Listener 2: analytics
lobby.onPresence(event -> analytics.track("room_presence", event));

// Listener 3: auto-cleanup
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

### Complete Setup Example

```java
@ManagedService(path = "/room-manager")
public class RoomManagerEndpoint {

    @Inject
    private AtmosphereFramework framework;

    private RoomManager rooms;

    @Ready
    public void onReady() {
        if (rooms == null) {
            rooms = RoomManager.getOrCreate(framework);
            setupRooms();
        }
    }

    private void setupRooms() {
        // Create rooms with history
        Room general = rooms.room("general");
        general.enableHistory(200);

        Room support = rooms.room("support");
        support.enableHistory(50);

        // Set up presence logging for all rooms
        rooms.all().forEach(room ->
            room.onPresence(event ->
                log.info("[{}] {} {}", room.name(), event.type(),
                    event.memberInfo() != null ? event.memberInfo().id() : "anonymous"))
        );
    }
}
```

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

### LlmRoomMember: AI-Powered Virtual Member

The `atmosphere-ai` module provides `LlmRoomMember`, which connects an LLM to a room. When any human member sends a message, the LLM processes it and broadcasts a response back to the room.

```java
import org.atmosphere.ai.LlmRoomMember;

var client = AiConfig.get().client();  // OpenAI-compatible client
var assistant = new LlmRoomMember("assistant", client, "gpt-4o",
    "You are a helpful coding assistant. Keep responses concise.");

Room devChat = rooms.room("dev-chat");
devChat.joinVirtual(assistant);
```

Now when any human member broadcasts a message in `dev-chat`, the `assistant` receives it via `onMessage`, sends it to the LLM, and broadcasts the response back to all human members.

Key behaviors of `LlmRoomMember`:

- **Self-loop prevention** -- it ignores messages from its own `id`, preventing infinite response loops
- **Blank message filtering** -- empty or whitespace-only messages are ignored
- **Virtual thread execution** -- LLM calls run on virtual threads to avoid blocking the Broadcaster
- **Presence participation** -- virtual members appear in presence events with `isVirtual() == true`
- **Metadata** -- includes `"type": "llm"` and `"model": "<model-name>"` in its metadata

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

### Managing Virtual Members

```java
// List all virtual members
Set<VirtualRoomMember> virtuals = room.virtualMembers();

// Remove a virtual member
room.leaveVirtual(assistant);
```

Removing a virtual member fires a `LEAVE` presence event with `isVirtual() == true`.

## Multiple Rooms Per User

A single client can be a member of multiple rooms simultaneously. This is common in applications with channels, topics, or workspaces.

```java
@ManagedService(path = "/chat")
public class MultiRoomChat {

    @Inject
    private AtmosphereFramework framework;

    @Inject
    private AtmosphereResource resource;

    private RoomManager rooms;

    @Ready
    public void onReady() {
        rooms = RoomManager.getOrCreate(framework);
    }

    @Message
    public void onMessage(String rawMessage) {
        var msg = parseCommand(rawMessage);

        switch (msg.command()) {
            case "join" -> {
                var member = new RoomMember(msg.userId(), Map.of("name", msg.displayName()));
                rooms.room(msg.roomName()).join(resource, member);
            }
            case "leave" -> {
                rooms.room(msg.roomName()).leave(resource);
            }
            case "send" -> {
                rooms.room(msg.roomName()).broadcast(msg.text(), resource);
            }
            case "list" -> {
                var roomList = rooms.all().stream()
                    .map(r -> r.name() + " (" + r.size() + " members)")
                    .toList();
                // Send the list back to just this client
                resource.write(new ObjectMapper().writeValueAsString(roomList));
            }
        }
    }
}
```

## Client Integration

The `atmosphere.js` library provides React hooks for rooms and presence.

### useRoom Hook

```tsx
import { useRoom } from 'atmosphere.js/react';

interface ChatMessage {
  text: string;
  sender: string;
}

function ChatRoom({ roomName, userId }: { roomName: string; userId: string }) {
  const { joined, members, messages, broadcast } = useRoom<ChatMessage>({
    request: {
      url: '/atmosphere/room',
      transport: 'websocket',
    },
    room: roomName,
    member: { id: userId },
  });

  const [input, setInput] = useState('');

  const send = () => {
    broadcast({ text: input, sender: userId });
    setInput('');
  };

  if (!joined) return <p>Connecting...</p>;

  return (
    <div>
      <h2>{roomName} ({members.length} online)</h2>
      <div>
        {messages.map((msg, i) => (
          <div key={i}>
            <strong>{msg.data.sender}:</strong> {msg.data.text}
          </div>
        ))}
      </div>
      <input value={input} onChange={e => setInput(e.target.value)} />
      <button onClick={send}>Send</button>
    </div>
  );
}
```

### usePresence Hook

For components that only need presence data without full room messaging:

```tsx
import { usePresence } from 'atmosphere.js/react';

function OnlineIndicator({ roomName, userId }: { roomName: string; userId: string }) {
  const { count, isOnline, members } = usePresence({
    request: {
      url: '/atmosphere/room',
      transport: 'websocket',
    },
    room: roomName,
    member: { id: userId },
  });

  return (
    <div>
      <span className={isOnline ? 'green' : 'gray'}>
        {count} online
      </span>
      <ul>
        {members.map(m => (
          <li key={m.id}>
            {m.metadata?.displayName || m.id}
            {m.metadata?.type === 'llm' && ' (AI)'}
          </li>
        ))}
      </ul>
    </div>
  );
}
```

### Vue and Svelte

Equivalent composables and stores are available for Vue (`useRoom`, `usePresence`) and Svelte (`roomStore`, `presenceStore`). See the [atmosphere.js client reference](/docs/clients/javascript/) for details.

## Room vs. Broadcaster: When to Use Which

| Need | Use |
|------|-----|
| Simple pub/sub with no identity | `Broadcaster` directly |
| Know who is connected (presence) | `Room` |
| Replay recent messages for new joiners | `Room` with `enableHistory()` |
| Application-level member identity (survives reconnect) | `Room` with `RoomMember` |
| AI agents participating in a conversation | `Room` with `VirtualRoomMember` |
| Fine-grained message filtering per subscriber | `Broadcaster` with `PerRequestBroadcastFilter` |
| Cross-channel message routing | `BroadcasterFactory.lookup()` |
| Custom lifecycle management | `Broadcaster` with `BroadcasterLifeCyclePolicy` |

The Room API does not replace Broadcasters -- it wraps them. Every Room is backed by a Broadcaster at `/atmosphere/room/<name>`. If you need both Room features and Broadcaster features (like custom filters or lifecycle policies), you can access the underlying Broadcaster through the `BroadcasterFactory`:

```java
Room lobby = rooms.room("lobby");
Broadcaster underlying = factory.lookup("/atmosphere/room/lobby");
underlying.getBroadcasterConfig().addFilter(new ProfanityFilter());
```

## Complete Example: Team Chat Application

```java
@ManagedService(path = "/team-chat")
public class TeamChat {

    @Inject
    private AtmosphereFramework framework;

    @Inject
    private AtmosphereResource resource;

    private RoomManager rooms;
    private final ObjectMapper mapper = new ObjectMapper();

    @Ready
    public void onReady() {
        rooms = RoomManager.getOrCreate(framework);
    }

    @Message
    public void onMessage(String raw) throws Exception {
        var cmd = mapper.readValue(raw, ChatCommand.class);

        switch (cmd.action()) {
            case "join" -> joinRoom(cmd);
            case "leave" -> leaveRoom(cmd);
            case "message" -> sendMessage(cmd);
        }
    }

    private void joinRoom(ChatCommand cmd) {
        Room room = rooms.room(cmd.room());
        room.enableHistory(100);

        room.onPresence(event -> {
            try {
                var json = mapper.writeValueAsString(Map.of(
                    "type", "presence",
                    "action", event.type().name().toLowerCase(),
                    "member", event.memberInfo() != null
                        ? event.memberInfo().id() : "unknown",
                    "room", event.room().name(),
                    "online", event.room().size()
                ));
                room.broadcast(json);
            } catch (Exception e) {
                // log and continue
            }
        });

        var member = new RoomMember(cmd.userId(), Map.of(
            "displayName", cmd.displayName(),
            "avatar", cmd.avatar()
        ));
        room.join(resource, member);
    }

    private void leaveRoom(ChatCommand cmd) {
        if (rooms.exists(cmd.room())) {
            rooms.room(cmd.room()).leave(resource);
        }
    }

    private void sendMessage(ChatCommand cmd) throws Exception {
        var json = mapper.writeValueAsString(Map.of(
            "type", "message",
            "room", cmd.room(),
            "sender", cmd.userId(),
            "text", cmd.text(),
            "timestamp", Instant.now().toString()
        ));
        rooms.room(cmd.room()).broadcast(json, resource);
    }

    record ChatCommand(String action, String room, String userId,
                        String displayName, String avatar, String text) {}
}
```

## Summary

| Concept | Purpose |
|---------|---------|
| `RoomManager` | Singleton registry for creating and managing rooms |
| `Room` | Named group of connections with presence, history, and virtual members |
| `RoomMember` | Application-level identity (id + metadata), stable across reconnects |
| `PresenceEvent` | Notification of JOIN/LEAVE with member info |
| `VirtualRoomMember` | Non-connection participant (bot, AI agent, service) |
| `LlmRoomMember` | LLM-powered virtual member that responds to room messages |
| `enableHistory(n)` | Replay last N messages to new joiners |

In the [next chapter](/docs/tutorial/07-websocket/), you will dive deeper into the WebSocket transport layer with `@WebSocketHandlerService` for cases where you need direct control over WebSocket frames, binary messages, and protocol-level details.
