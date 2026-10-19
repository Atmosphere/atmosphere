# spring-boot-reattach-harness

Deterministic harness for the mid-stream reattach contract. The
Playwright spec `e2e/tests/reattach.spec.ts` drives this sample end-to-end
so the full HTTP → `RunRegistry` → `RunEventReplayBuffer` →
`RunReattachSupport` wire is proven in CI — unit tests alone cannot
prove the transport layer cooperates. The browser spec
`modules/integration-tests/e2e/ai-reattach-reload.spec.ts` drives
`SlowModelChat` through the real atmosphere.js to prove the reattach grace
across a page reload over WebSocket, SSE and long-polling.

## Three surfaces

### `SlowEmitterChat` — live `@Prompt` emitter

`@AiEndpoint(path = "/atmosphere/agent/harness")`. The `@Prompt` method
emits six events with `Thread.sleep(500)` between each `session.send(...)`.
Every event is captured by `RunEventCapturingSession` into the run's
`RunEventReplayBuffer`. This is the literal harness spec the project maintainer
asked for — useful for manual verification and future timing-tolerant
integration tests.

Drop the WebSocket after `event-1` and reconnect with
`?X-Atmosphere-Run-Id=<runId>` (the run id arrives in the `X-Atmosphere-Run-Id` metadata
frame): the new socket receives `event-0` and `event-1` from the replay
buffer, then `event-2`..`event-5` and the `complete` frame as the still-running
`@Prompt` emits them. Over WebSocket, SSE and streaming the reattached
connection is subscribed to the rest of the run; long-polling gets the
buffered events in one reply. A reconnect after the turn ended still replays
the whole run, `complete` included, for the registry TTL (30 minutes by
default).

This works because `SlowEmitterChat` emits with `session.send(...)` and keeps
running after the first socket drops: there is no model call for a disconnect
to cancel. `SlowModelChat` below is the surface with one.

### `SlowModelChat` — a model call a disconnect can cancel

`@AiEndpoint(path = "/atmosphere/agent/harness-model")`. The `@Prompt`
answers with `session.stream(message)`, so the reply is a model call through
the built-in runtime's cancellable path. The model is `PacedModelClient`, an
in-process `LlmClient` that answers every prompt with the twelve tokens
`t00 ` … `t11 `, one every 500ms, and stops when the call is cancelled.
`HarnessModelConfiguration` makes it the application's model with its own
`AiConfig.LlmSettings` bean, which the starter adopts in place of the settings
it builds from `LLM_*`, so the harness answers the same way whatever keys the
host exports.

A client whose connection is lost — a dropped network, a page reload (a
WebSocket closed with 1001; over SSE and long-polling, the close message
atmosphere.js sends from `pagehide`, marked
`X-Atmosphere-Close-Reason=going-away`) — leaves the model call going for the
reattach grace (30s by default): a reattach with the run id receives the whole
reply through `complete`. A client that leaves on purpose (atmosphere.js
`close()`) cancels the call at once, and a reattach receives the tokens streamed
until then, then the `cancelled` frame.

`GET /harness/model-call?prompt=<prompt>` reports how the model call that
answered a prompt stands — `{"state":"RUNNING"|"COMPLETED"|"CANCELLED","tokens":n,"total":12,…}`,
404 when no call answered it — so a test asks the server whether the call was
cancelled rather than inferring it from what one client received.

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

# A model call over WebSocket, and how it ended
#   ws://localhost:8096/atmosphere/agent/harness-model?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0
#   send any prompt, e.g. "hello": the X-Atmosphere-Run-Id metadata frame, a progress
#   and a model metadata frame, t00 … t11, then complete
curl 'http://localhost:8096/harness/model-call?prompt=hello'
# → {"prompt":"hello","state":"COMPLETED","tokens":12,"total":12}

# Register a synthetic run
curl -X POST http://localhost:8096/harness/synthetic-run
# → {"runId":"…","events":["replay-event-0","replay-event-1","replay-event-2"],"total":4}

# Reconnect against the @AiEndpoint carrying the run id; the onReady hook
# fires reattachPendingRun → replayPendingRun and drains the buffer onto
# the reconnecting resource.
#
# The transport headers are REQUIRED, not decoration. AiEndpointHandler suspends the
# resource — and calls reattachPendingRun — only when the transport resolves to
# WEBSOCKET, SSE or LONG_POLLING. A bare GET resolves to none of those, so the whole
# block is skipped and the request returns 200 with an empty body in ~2ms and no
# replay: it looks like the feature is broken when it is only mis-invoked.
curl -H 'X-Atmosphere-Run-Id: <runId>' \
     -H 'X-Atmosphere-Transport: long-polling' \
     -H 'X-Atmosphere-Framework: 4.0' \
     -H 'X-Atmosphere-tracking-id: 0' \
     http://localhost:8096/atmosphere/agent/harness
# → the 3 buffered events plus the terminal frame, in order:
#   {"type":"streaming-text","data":"replay-event-0",…,"seq":1}
#   {"type":"streaming-text","data":"replay-event-1",…,"seq":2}
#   {"type":"streaming-text","data":"replay-event-2",…,"seq":3}
#   {"type":"complete","data":"synthetic",…}
# and the server logs: `Reattach run <id> for resource <uuid>: replayed 4/4 new event(s)`
```

## WebTransport

The harness carries `reactor-netty-http`, so the starter can run the
WebTransport (HTTP/3) sidecar, off by default. Turn it on to drive the
Console over WebTransport, as
`modules/integration-tests/e2e/ai-reattach-webtransport.spec.ts` does to
prove a reload mid-answer reattaches over WebTransport too:

```bash
java -Datmosphere.web-transport.enabled=true -Datmosphere.web-transport.port=4460 \
     -Datmosphere.ai.path=/atmosphere/agent/harness-model \
     -jar samples/spring-boot-reattach-harness/target/*.jar
```

The sidecar uses a self-signed certificate; `/api/console/info` reports its
hash, which the Console hands to the browser (`serverCertificateHashes`).
The Spring Boot 3 starter has no WebTransport support.

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
