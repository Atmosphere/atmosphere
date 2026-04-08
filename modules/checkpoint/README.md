# Atmosphere Checkpoint

Durable agent execution for Atmosphere — a framework-neutral `CheckpointStore`
SPI plus an in-memory implementation, with an opt-in decorator that turns
the coordinator module's `CoordinationJournal` into a persistent execution
log.

This module exists because only one of Atmosphere's supported runtimes
(Google ADK) ships native checkpoint/resume. `durable-sessions` covers
transport-layer session state (rooms, broadcaster subscriptions) — it does
not persist agent workflow state. This module fills that gap.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-checkpoint</artifactId>
    <version>4.0.32</version>
</dependency>
```

## Core API

| Type | Purpose |
|------|---------|
| `CheckpointStore` | SPI: `save`, `load`, `fork`, `list`, `delete`, `deleteCoordination` |
| `WorkflowSnapshot<S>` | Immutable record of workflow state + parent link + metadata |
| `CheckpointId` | Opaque typed identifier (UUID-backed by default) |
| `CheckpointQuery` | Filter for `list()` — by coordinationId, agentName, time range, limit |
| `CheckpointEvent` | Sealed `Saved`/`Loaded`/`Forked`/`Deleted` lifecycle events |
| `CheckpointListener` | Callback for lifecycle events |
| `InMemoryCheckpointStore` | Default in-memory implementation, thread-safe, with eviction |

Application code owns the workflow state type `S` and its serialization.
The in-memory store keeps the state as-is; persistent stores (JDBC, Redis)
will accept a caller-supplied serializer.

## Quick Start

```java
CheckpointStore store = new InMemoryCheckpointStore();
store.start();

// Save a root snapshot
var root = WorkflowSnapshot.root("coord-42", myWorkflowState);
store.save(root);

// Derive a child snapshot on each workflow step
var next = root.deriveWith(updatedState);
store.save(next);

// Resume: load the latest snapshot for a coordination
var latest = store.list(CheckpointQuery.forCoordination("coord-42"))
        .stream()
        .reduce((a, b) -> b)        // last one
        .orElseThrow();

// Fork to explore an alternative branch
var branch = store.fork(latest.id(), alternativeState);
```

## CoordinationJournal Bridge (optional)

If the `atmosphere-coordinator` module is on your classpath, wrap its
journal with `CheckpointingCoordinationJournal` to automatically persist a
snapshot on every (or selected) coordination event:

```java
var store = new InMemoryCheckpointStore();
var journal = new CheckpointingCoordinationJournal<>(
        new InMemoryCoordinationJournal(),
        store,
        CheckpointingCoordinationJournal.onAgentBoundaries(),   // filter
        CoordinationStateExtractors.event());                   // what to store
journal.start();
```

Snapshots form a chain per coordination: the first snapshot is a root, each
subsequent one references its predecessor. The chain mirrors the event
ordering in the underlying journal. Supply your own
`CoordinationStateExtractor<S>` to capture domain state rather than the raw
event.

## Listeners

```java
store.addListener(event -> {
    if (event instanceof CheckpointEvent.Saved saved) {
        metrics.counter("checkpoints.saved", "coordination", saved.coordinationId()).increment();
    }
});
```

Listeners must be thread-safe. Exceptions thrown from a listener are logged
and swallowed; they never abort the store operation.

## Design Notes

- **Framework-neutral**: no hard dependency on any LLM runtime. The state
  type `S` is whatever the application wants to persist.
- **No direct coordinator dependency in the SPI**: the decorator lives in
  the `org.atmosphere.checkpoint.coordinator` sub-package, and coordinator
  is a `<optional>true</optional>` dependency.
- **Eviction**: the in-memory store evicts oldest snapshots (by
  `createdAt`) once it exceeds its cap. Default cap is 10 000.
- **Sealed events**: `CheckpointEvent` is sealed so exhaustive pattern
  matches are safe.

## Relationship to `atmosphere-durable-sessions`

Atmosphere has **two** durability primitives; they solve different
problems and should be understood together.

### Surface comparison

| Axis | `SessionStore` (durable-sessions) | `CheckpointStore` (checkpoint) |
|------|-----------------------------------|--------------------------------|
| Layer | Transport / connection | Coordinator / workflow |
| Unit persisted | `DurableSession` (token, rooms, broadcaster subs, metadata) | `WorkflowSnapshot<S>` (state, parentId, agentName, coordinationId) |
| Lifecycle | One session per client connection; replaced on reconnect | Many snapshots per coordination; form a parent-chained history |
| Mutation model | Overwrite-on-update | Append-only + fork |
| Triggered by | `DurableSessionInterceptor` on connect/disconnect | `CheckpointingCoordinationJournal` on `CoordinationEvent` |
| Backends | In-memory, SQLite, Redis (already shipped) | In-memory (this module); JDBC + Redis planned |
| Primary consumer | WebSocket/SSE reconnect handler | Agent fleet / HITL approval flow |

### Why separate modules

- **SRP**: transport state and workflow state have independent lifecycles.
  A session reconnect does not imply workflow resumption, and a workflow
  fork does not require any transport-level change.
- **Different data models**: `DurableSession` is a flat record updated in
  place; `WorkflowSnapshot` is an immutable node in a parent-chained DAG
  with fork semantics.
- **Different consumers**: the session interceptor runs at connect /
  disconnect boundaries; the checkpoint bridge runs on every recorded
  coordination event.
- **Independent evolvability**: persistence backends (SQLite, Redis) can
  evolve in each module without coupling their schemas.

### The composition point — the moat-aligned synthesis

The two primitives compose at one point: **carry a `CheckpointId` in the
`DurableSession.metadata` map so that a streaming client reconnecting via
durable-sessions can rehydrate the coordinator's workflow position from
the `CheckpointStore` in a single flow.**

Conceptual pseudocode (no bridge class shipped yet — see below):

```java
// While the workflow runs, the coordinator records the current checkpoint
// id on the client's durable session:
session.metadata().put("checkpointId",
        journal.lastSnapshot(coordinationId).value());

// On reconnect, the session interceptor rehydrates rooms + broadcasters,
// then hands off to the coordinator to resume from the snapshot:
DurableSession durable = sessionStore.load(token).orElseThrow();
var snapId = durable.metadata().get("checkpointId");
if (snapId != null) {
    WorkflowSnapshot<?> resumeAt =
            checkpointStore.load(CheckpointId.of(snapId)).orElseThrow();
    // Coordinator resumes from resumeAt.state(), optionally forking.
}
```

This is the pattern that makes Atmosphere **streaming-native resumable
workflows** — distinct from what Python agentic frameworks offer, because
the transport layer and the workflow layer rehydrate together over one
WebTransport/WebSocket resume.

### Why no bridge class in this module (yet)

No shipped sample demands this pattern today. Per the project's own
guidance (`CLAUDE.md`: *"Don't create helpers, utilities, or abstractions
for one-time operations. Don't design for hypothetical future
requirements"*), the bridge will land when a sample drives its shape. At
that point it is expected to be a **single optional class** in this
module (with `atmosphere-durable-sessions` as a `<optional>true</optional>`
dependency), not a third module — adding a third module would compound
the SPI surface without benefit.

### When to use which

- **Need**: a WebSocket/SSE client should recover its rooms, broadcaster
  subscriptions, and message queue across a restart. **Use**
  `atmosphere-durable-sessions`.
- **Need**: an agent coordination should survive a pause (human approval,
  crash, long-running tool call) and be able to branch/resume/replay at
  tool-boundary granularity. **Use** `atmosphere-checkpoint`.
- **Need**: a streaming client reconnects mid-workflow and should pick up
  both its transport state and its workflow position. **Use both**, with
  the pattern above.

## Planned Follow-ups

- `atmosphere-checkpoint-jdbc` — persistent JDBC store (PostgreSQL, H2)
- `atmosphere-checkpoint-redis` — clustered store reusing the existing
  `atmosphere-redis` infrastructure
- `fleet.resume(CheckpointId)` convenience API on `AgentFleet`
- Sample extending `spring-boot-multi-agent-startup-team` with
  long-running HITL pauses

## Requirements

- Java 21+
- SLF4J 2.x (transitive)
