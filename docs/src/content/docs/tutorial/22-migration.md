---
title: "Migrating from 2.x to 4.0"
description: "Step-by-step guide for upgrading from Atmosphere 2.x to 4.0"
sidebar:
  order: 22
---

Atmosphere 4.0 is a major release with significant changes from 2.x. This chapter covers what changed, what was removed, and how to migrate step by step.

## Overview of Changes

| Area | 2.x | 4.0 |
|------|-----|-----|
| Java version | JDK 8+ | JDK 21+ |
| Servlet API | `javax.servlet` | `jakarta.servlet` (Jakarta EE 10+) |
| Dependency injection | `javax.inject` | `jakarta.inject` |
| Client library | atmosphere.js (JavaScript) | atmosphere.js 5.0 (TypeScript) |
| Chat/groups | Manual Broadcaster management | First-class Rooms API |
| Spring Boot | Spring Boot 2.x (community) | Spring Boot 4.0 (official starter) |
| Quarkus | Not supported | Official extension |

## Package Changes

All `javax.*` packages are now `jakarta.*`:

```java
// 2.x
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

// 4.0
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
```

## @ManagedService (Unchanged)

The core annotation model is the same. Your `@ManagedService` classes work with minimal changes:

```java
// Same in both 2.x and 4.0
@ManagedService(path = "/chat")
public class Chat {
    @Ready
    public void onReady() { }

    @Disconnect
    public void onDisconnect() { }

    @Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) { return message; }
}
```

## Removed Features

The following have been removed or replaced in 4.0:

| Removed | Replacement |
|---------|-------------|
| Jersey integration | Use `@ManagedService` or Spring Boot |
| GWT support | Use atmosphere.js 5.0 |
| Socket.IO protocol | Use atmosphere.js 5.0 |
| Cometd protocol | Use atmosphere.js 5.0 |
| SwaggerSocket | Removed |
| Meteor API | Use `@ManagedService` |
| `@MeteorService` | Use `@ManagedService` |
| `web.xml`-only config | Still supported, but annotation-based is preferred |
| atmosphere-javascript | Replaced by atmosphere.js 5.0 (TypeScript, zero dependencies) |

## New Features in 4.0

| Feature | Description |
|---------|-------------|
| **Rooms** | Named groups with presence, history, direct messaging |
| **RoomManager** | Create/manage rooms backed by Broadcasters |
| **PresenceEvent** | Join/leave tracking with member identity |
| **RoomMember** | Application-level member ID (stable across reconnects) |
| **RoomAuthorizer** | Authorization for room operations |
| **Framework hooks** | React `useRoom`, Vue `useRoom`, Svelte `createRoomStore` |
| **Spring Boot 4.0 starter** | Auto-configuration, health, metrics |
| **Quarkus extension** | Build-time processing, native image support |

## Client Migration

### 2.x (atmosphere-javascript)

```javascript
var socket = atmosphere;
var request = {
    url: '/chat',
    transport: 'websocket',
    fallbackTransport: 'long-polling'
};
request.onMessage = function(response) {
    var message = response.responseBody;
};
var subSocket = socket.subscribe(request);
subSocket.push(JSON.stringify(data));
```

### 4.0 (atmosphere.js 5.0, TypeScript)

```typescript
import { Atmosphere } from 'atmosphere.js';

const atm = new Atmosphere();
const sub = await atm.subscribe(
    {
        url: '/chat',
        transport: 'websocket',
        fallbackTransport: 'long-polling',
    },
    {
        message: (response) => {
            const data = JSON.parse(response.responseBody);
        },
    }
);
sub.push(JSON.stringify(data));
```

The global `atmosphere` object is still available for `<script>` tag usage (no build step):

```javascript
const sub = await atmosphere.atmosphere.subscribe(request, handlers);
```

## Step-by-Step Migration

1. **Update JDK** to 21+
2. **Replace** `javax.*` imports with `jakarta.*`
3. **Update** `atmosphere-runtime` dependency to `4.0.0`
4. **Replace** Jersey/Meteor code with `@ManagedService` (if not already using it)
5. **Replace** `atmosphere-javascript` with `atmosphere.js` 5.0
6. **Optionally** adopt Rooms for group messaging patterns
7. **Optionally** adopt Spring Boot starter or Quarkus extension

## web.xml Changes

Update the web-app namespace for Jakarta EE:

```xml
<!-- 2.x -->
<web-app xmlns="http://java.sun.com/xml/ns/javaee" version="3.0">

<!-- 4.0 -->
<web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">
```

The `AtmosphereServlet` class name is unchanged: `org.atmosphere.cpr.AtmosphereServlet`.
