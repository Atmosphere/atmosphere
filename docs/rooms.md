# Rooms & Presence

Server-side room management with presence tracking, message history, and AI virtual members. Built into the core runtime -- no additional dependencies needed.

## Quick Start

```java
RoomManager rooms = RoomManager.getOrCreate(framework);
Room lobby = rooms.room("lobby");
lobby.enableHistory(100); // replay last 100 messages to new joiners

lobby.join(resource, new RoomMember("user-1", Map.of("name", "Alice")));
lobby.broadcast("Hello everyone!");
lobby.onPresence(event -> log.info("{} {} room '{}'",
    event.member().id(), event.type(), event.room().name()));
```

## Room API

```java
RoomManager rooms = RoomManager.getOrCreate(framework);

// Create or get a room
Room room = rooms.room("dev-chat");

// Join with metadata
room.join(resource, new RoomMember("user-1", Map.of("name", "Alice", "role", "admin")));

// Broadcast to all members
room.broadcast("Hello everyone!");

// Leave the room
room.leave(resource);
```

## Presence Tracking

```java
room.onPresence(event -> {
    switch (event.type()) {
        case JOIN -> log.info("{} joined {}", event.member().id(), event.room().name());
        case LEAVE -> log.info("{} left {}", event.member().id(), event.room().name());
    }
});
```

## Message History

Enable replay of recent messages for new joiners:

```java
room.enableHistory(100); // keep last 100 messages
```

When a new member joins, they receive the last N messages so they can catch up on the conversation.

## AI Virtual Members

Add an LLM-powered member that responds to messages in the room:

```java
var client = AiConfig.get().client();
var assistant = new LlmRoomMember("assistant", client, "gpt-5",
    "You are a helpful coding assistant");

Room room = rooms.room("dev-chat");
room.joinVirtual(assistant);
// Now when any user sends a message, the LLM responds in the same room
```

Virtual members participate like any other room member -- they appear in presence, receive messages, and can broadcast responses.

## Client Integration

### React

```tsx
import { useRoom, usePresence } from 'atmosphere.js/react';

function ChatRoom() {
  const { joined, members, messages, broadcast } = useRoom<ChatMessage>({
    request: { url: '/atmosphere/room', transport: 'websocket' },
    room: 'lobby',
    member: { id: 'user-1' },
  });

  const { count, isOnline } = usePresence({
    request: { url: '/atmosphere/room', transport: 'websocket' },
    room: 'lobby',
    member: { id: 'user-1' },
  });

  return (
    <div>
      <p>{count} online</p>
      {messages.map((m, i) => <div key={i}>{m.member.id}: {m.data.text}</div>)}
      <button onClick={() => broadcast({ text: 'Hi' })}>Send</button>
    </div>
  );
}
```

Vue and Svelte hooks are also available -- see [atmosphere.js](client-javascript.md).

## Samples

- [Spring Boot Chat](../samples/spring-boot-chat/) -- rooms, presence, REST API

## See Also

- [Core Runtime](core.md) -- Broadcaster and AtmosphereResource
- [AI Integration](ai.md) -- AI virtual members and `LlmRoomMember`
- [atmosphere.js](client-javascript.md) -- `useRoom` and `usePresence` hooks
