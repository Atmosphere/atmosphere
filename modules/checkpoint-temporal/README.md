# atmosphere-checkpoint-temporal

Runs Atmosphere's durable `Workflow<S>` on a [Temporal](https://temporal.io)
service. This module implements the `DurableExecutionProvider` SPI from
`atmosphere-checkpoint`: add it to the classpath and `Workflow.run()`
resolves it via `ServiceLoader` whenever a Temporal server is actually
reachable — no caller changes.

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-checkpoint-temporal</artifactId>
</dependency>
```

```java
// Identical code to the in-tree engine — the backend is resolved per run.
var workflow = new Workflow<>("doc-pipeline", "coord-123", steps, store);
var result = workflow.run("doc-42");
```

## What Temporal owns vs. what Atmosphere keeps

| Concern | Owner |
|---|---|
| Per-step retries (translated from `maxRetries()` / `retryDelay()`, fixed backoff) | Temporal |
| Step timeouts (`atmosphere.temporal.step-timeout-ms`, start-to-close) | Temporal |
| Execution history + operational visibility (Temporal UI, `temporal workflow list`) | Temporal |
| Snapshot trail, hibernation, cross-restart resume (`CheckpointStore`) | Atmosphere |
| Step code + application state `S` | Your JVM |

## Execution model

One generic Temporal workflow (`AtmosphereTemporalWorkflow`) drives the run.
Each step executes as a Temporal **activity in the JVM that called
`run()`**, against the live step lambdas. Application state never crosses
the Temporal payload boundary, so adding this module imposes **no
serialization constraints on `S`**.

The adapter writes the exact snapshot trail the in-tree engine writes (same
metadata keys, same seed snapshot, same resume rule), so the two engines are
interchangeable mid-flight: hibernate on one, resume on the other.
`TemporalDurableExecutionProviderTest.snapshotTrailMatchesTheInTreeEngine`
pins this.

**Restart contract:** steps need the live session, so a run orphaned by a
JVM restart fails its next activity fast (`SessionNotFound`) instead of
hanging. The application resumes by calling `Workflow.run()` again — it
picks up after the last checkpointed step, exactly like the in-tree engine.
Cross-JVM *continuation of an in-flight run* (a worker fleet picking up
mid-run) is not provided by this adapter.

## Configuration

Each system property can also be set via the equivalent environment
variable (`atmosphere.temporal.target` → `ATMOSPHERE_TEMPORAL_TARGET`):

| Property | Default | Purpose |
|---|---|---|
| `atmosphere.temporal.target` | `127.0.0.1:7233` | Temporal frontend host:port |
| `atmosphere.temporal.namespace` | `default` | Temporal namespace |
| `atmosphere.temporal.task-queue` | `atmosphere-workflow` | Task queue the embedded worker polls |
| `atmosphere.temporal.connect-timeout-ms` | `2000` | Connection probe timeout |
| `atmosphere.temporal.step-timeout-ms` | `3600000` | Per-step start-to-close timeout |

Availability is runtime truth: the provider reports `isAvailable()` only
after a health-checked connection succeeds. No reachable server → the
in-tree step engine runs, automatically. Failed probes are cached for 30s
so per-run resolution stays cheap.

## Running against a real server

```bash
temporal server start-dev          # local dev server on 127.0.0.1:7233
# start your app with this module on the classpath — runs appear in the
# Temporal UI (http://localhost:8233) under task queue atmosphere-workflow
```

## Tests

`TemporalDurableExecutionProviderTest` drives `Workflow.run()` end-to-end on
the Temporal **test service** (`temporal-testing`, in-process, no Docker):

| Test | Proves |
|---|---|
| `resolveSelectsTheTemporalProviderWhenConnected` | `resolve()` prefers a reachable Temporal backend |
| `runExecutesStepsInsideTemporalActivities` | steps run inside real Temporal activities (`Activity.getExecutionContext()`), not on the in-tree engine |
| `hibernateReturnsImmediatelyAndResumeSkipsCompletedSteps` | HITL hibernate/resume parity, exact step-execution counts |
| `temporalHonorsThePerStepRetryBudget` | `maxRetries=2` → exactly 3 attempts |
| `exhaustedRetriesSurfaceAsFailedNotAsAThrow` | failure shape parity with the in-tree engine |
| `explicitFailPropagatesTheReasonVerbatim` | `StepOutcome.fail(reason)` round-trips |
| `snapshotTrailMatchesTheInTreeEngine` | identical `CheckpointStore` trail on both engines (Mode Parity) |
| `unreachableBackendFallsBackToTheInTreeEngine` | unreachable server is never selected (Runtime Truth) |
