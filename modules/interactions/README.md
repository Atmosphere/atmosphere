# atmosphere-interactions

A **stateful agent-turn resource** layered above the `AgentRuntime` SPI. An
*interaction* is one agent turn that carries a stable id, a durable `steps[]`
event log, and chains to the previous turn via `previousInteractionId` — the
server holds the conversation history so the client does not resend it. Because
it wraps `AgentRuntime.execute(...)` / `executeWithHandle(...)`, it works for
every adapter with no per-runtime code.

This module is **additive** — it changes no existing module behaviour.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-interactions</artifactId>
    <version>${project.version}</version>
</dependency>
```

The HTTP surface (`/api/interactions`) and the live-stream socket live in
`atmosphere-spring-boot-starter`; this module is the runtime-agnostic core
(records, store SPI, capturing session, service).

## Core Types

| Type | Role |
|------|------|
| `Interaction` | Immutable record of a turn: `id`, `parentId`, `conversationId`, `agentId`, `userId`, `model`, `status`, `background`, `store`, `steps`, `finalText`, `usage`, `errorMessage`, `createdAt`, `updatedAt`. |
| `InteractionStep` | One durable event: `seq`, `type`, `text`, `toolName`, `data`, `usage`, `createdAt`. |
| `InteractionStatus` | `CREATED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED` (the last three are terminal). |
| `InteractionRequest` | A turn request: `previousInteractionId`, `message`, `agentId`, `model`, `systemPrompt`, `tools`, `metadata`, `background`, `store`. |
| `InteractionService` | Facade: `create` (sync), `createBackground`, `get`, `list`, `continueInteraction`, `cancel`, `delete` — all ownership-checked. |
| `InteractionStore` | Persistence SPI (below). |

## InteractionStore SPI

```java
public interface InteractionStore {
    void start();
    void stop();
    default boolean isAvailable() { return true; }

    Interaction save(Interaction interaction);          // upsert metadata
    void appendStep(String interactionId, InteractionStep step); // incremental
    Optional<Interaction> load(String interactionId);
    List<Interaction> list(InteractionQuery query);
    boolean delete(String interactionId);
}
```

In-memory and SQLite ship in this module; Postgres ships as `atmosphere-interactions-postgres`:

| Implementation | Use case |
|----------------|----------|
| `InMemoryInteractionStore` | Default. `ConcurrentHashMap`, copy-on-write step append. Lost on restart. |
| `SqliteInteractionStore` | Single-node durability. Persists to `atmosphere-interactions.db` (mirrors `SqliteCheckpointStore`). |
| `PostgresInteractionStore` | JDBC store (ships as `atmosphere-interactions-postgres`); targets Postgres, works with any JSR-221 `DataSource`. Operator supplies the driver + pooling. |

A JDBC/Postgres store ships in-tree as `atmosphere-interactions-postgres`
(`PostgresInteractionStore`). Other backends (Redis, …) are pluggable via the SPI.

## Sync vs. Background

```java
// Synchronous — streams to the caller's session and returns the completed record
Interaction done = service.create(InteractionRequest.of("Summarise this repo"),
                                  clientSession, principal);

// Background — returns a RUNNING record immediately; retrieve/poll later
Interaction running = service.createBackground(
        new InteractionRequest(null, "Refactor the auth module", /* … */),
        principal);
```

A background interaction is persisted as `RUNNING` the moment it is launched
(Runtime Truth), so it is retrievable even if the launching connection drops.
A CAS-guarded terminal writer records exactly one of `COMPLETED` / `FAILED` /
`CANCELLED` — cancelling mid-run keeps the steps captured so far.

## Chaining

`continueInteraction(previousId, request, principal)` inherits the prior turn's
`conversationId` and rehydrates LLM history from `ConversationPersistence`;
`parentId` is the audit breadcrumb. `steps[]` is the durable observability
record, not the prompt history — the two are kept separate on purpose.

## Live Streaming

Background runs can be streamed live. The starter registers an Atmosphere
handler at `/atmosphere/interactions-stream?id=<id>`; on connect it replays the
steps captured so far (late-joiner catch-up, deduped by `seq`) then pushes each
new step and a terminal frame. The `InteractionLiveStream` SPI is the hook the
service calls as steps are appended:

```java
public interface InteractionLiveStream {
    void onStep(InteractionStep step);
    void onTerminal(Interaction terminal);

    @FunctionalInterface
    interface Factory { InteractionLiveStream open(Interaction initial); }
}
```

On the browser, `InteractionsClient.subscribe(id, handlers)` from
`atmosphere.js/interactions` bridges the socket. See the
[Interactions reference](https://atmosphere.github.io/docs/reference/interactions/).

## Security

- Ids are validated against `^[A-Za-z0-9_-]{1,128}$` — a malformed id is a 400,
  never a 500, and ids are parameterized keys, never filenames (Invariant #4).
- `get` / `cancel` / `delete` / `continue` compare the interaction's `userId`
  against the resolved principal; a mismatch yields empty/false (Invariant #6).
- Over HTTP, mutating routes are default-deny behind
  `atmosphere.interactions.http-write-enabled` plus an authenticated principal.

## Samples

- [`spring-boot-coding-agent`](../../samples/spring-boot-coding-agent) — a
  long-running coding task launched in the background, streamed live in the Console.
- [`spring-boot-multi-agent-startup-team`](../../samples/spring-boot-multi-agent-startup-team)
  — a multi-agent run kicked off as a background interaction.
