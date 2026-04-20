# spring-boot-reattach-harness

Deterministic harness for the mid-stream reattach contract. The
Playwright spec `e2e/tests/reattach.spec.ts` drives this sample end-to-end
so the full HTTP → `RunRegistry` → `RunEventReplayBuffer` →
`RunReattachSupport` wire is proven in CI — unit tests alone cannot
prove the transport layer cooperates.

## Two surfaces

### `SlowEmitterChat` — live `@Prompt` emitter

`@AiEndpoint(path = "/atmosphere/agent/harness")`. The `@Prompt` method
emits six events with `Thread.sleep(500)` between each `session.send(...)`.
Every event is captured by `RunEventCapturingSession` into the run's
`RunEventReplayBuffer`. This is the literal harness spec ChefFamille
asked for — useful for manual verification and future timing-tolerant
integration tests.

### `SyntheticRunController` — deterministic REST surface

`POST /harness/synthetic-run` pre-registers a run in
`RunRegistryHolder.get()` with three known buffered events and returns
the run id. The Playwright spec uses this path for deterministic CI
coverage: the reattach contract is about the header-to-replay wire,
not wall-clock timing. Removing the scheduling variable removes flake
without weakening what's proven — the reconnect path goes through the
identical production code (`AiEndpointHandler.onReady` →
`RunReattachSupport.replayPendingRun`).

## Run locally

```bash
./mvnw spring-boot:run -pl samples/spring-boot-reattach-harness

# Register a synthetic run
curl -X POST http://localhost:8096/harness/synthetic-run
# → {"runId":"…","events":["replay-event-0","replay-event-1","replay-event-2"],"total":4}

# Reconnect against the @AiEndpoint carrying the run id; the onReady hook
# fires reattachPendingRun → replayPendingRun and drains the buffer onto
# the reconnecting resource.
curl -H 'X-Atmosphere-Run-Id: <runId>' http://localhost:8096/atmosphere/agent/harness
```

## Why a sample, not an integration test

Unit tests (`RunReattachSupportTest`, `RunEventCapturingSessionTest`,
`AgentResumeHandleTest`) pin every primitive individually and walk the
full capture → disconnect → reconnect → replay loop with a real
`RunRegistry`. What they cannot prove is that the **transport** layer
cooperates — that the servlet container preserves the
`X-Atmosphere-Run-Id` header onto the request attribute chain, that
`AtmosphereResource` lifecycle events fire in the right order, and that
the broadcaster delivers the replay writes to the reconnected resource.
This sample closes that gap by giving Playwright a live endpoint to
drive over real HTTP.
