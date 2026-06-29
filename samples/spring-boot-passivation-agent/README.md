# spring-boot-passivation-agent

Proves the Atmosphere 4 **PASSIVATION** capability end to end: a paused agent
conversation is **snapshotted** into a durable store and, when an external
signal arrives, **resumed from exactly where it left off** — same system
prompt, same history, same identity — instead of restarting cold.

This is the "agent saves its state and vanishes from RAM while it waits for a
human (which can take days), then instantly resumes when signaled" pattern. The
sample is the honest proof behind that blog claim: a real application driving
`AgentPassivation.passivate(...)` / `AgentPassivation.resume(...)` against a
`CheckpointStore`.

It runs **fully offline** — no LLM key required. A deterministic
`DemoContinuationRuntime` stands in for the model, and the default store is the
in-memory `CheckpointStore`.

## Why a REST controller and not `@AiEndpoint`

`AiCapability.PASSIVATION` is **application policy, not a user-facing
endpoint** (see the `AgentPassivation` Javadoc). The framework gives you the
durable building blocks — capture a snapshot, resume it with the restored
history threaded back in — but the application decides *when* to pause and on
*which* signal to resume (human approval, a scheduled tick, an upstream event).

So the trigger here is a plain `@RestController`. The `POST /resume` call
stands in for "the approval arrived." Everything streaming-related still works
the same way; passivation simply isn't an endpoint you expose.

## How to run

```bash
./mvnw spring-boot:run -pl samples/spring-boot-passivation-agent
# or run the test that proves the flow:
./mvnw test -pl samples/spring-boot-passivation-agent
```

The app starts on `http://localhost:8097` (override with `SERVER_PORT`).

### 1. Pause a conversation

```bash
curl -s localhost:8097/api/agent/pause -H 'Content-Type: application/json' -d '{
  "conversationId": "conv-refund-1",
  "pendingMessage": "Please finalize the pending refund.",
  "reason": "awaiting manager approval",
  "history": [
    {"role": "user",      "content": "I need a refund for order ORD-77781"},
    {"role": "assistant", "content": "Refund started for ORD-77781; routed to a manager for approval."}
  ]
}'
# -> {"checkpointId":"<id>","conversationId":"conv-refund-1","historySize":2,"reason":"awaiting manager approval"}
```

The agent is now "gone from RAM" — its state lives only in the
`CheckpointStore`. Inspect it without resuming:

```bash
curl -s localhost:8097/api/agent/checkpoints/<id>
# -> {"runtimeName":"demo-continuation","pendingMessage":"...","historySize":2,"history":[...]}
```

### 2. Resume when the signal arrives

```bash
curl -s localhost:8097/api/agent/resume -H 'Content-Type: application/json' -d '{
  "checkpointId": "<id>",
  "signal": "approved: manager Alex signed off"
}'
# -> {"checkpointId":"<id>",
#     "response":"Resuming the conversation (2 earlier messages restored).
#                 Earlier the customer said: \"I need a refund for order ORD-77781\".
#                 Approval signal received: \"approved: manager Alex signed off\".
#                 Completing the request.",
#     "restoredHistorySize":2,"continued":true,"sessionId":"resume-..."}
```

The reply quotes `ORD-77781`, which appears **only** in the restored history —
proof the resumed run continued the same conversation rather than starting over.

## Key code

| File | Role |
|------|------|
| `PassivationService` | The application-policy driver. `pause(...)` builds an `AgentExecutionContext` and calls `AgentPassivation.passivate(...)`; `resume(...)` calls `AgentPassivation.resume(...)` and collects the continued reply. |
| `DemoContinuationRuntime` | Deterministic, key-free `AgentRuntime` that declares `PASSIVATION` and makes its reply a function of the restored `context.history()` — so "continued from where it left off" is observable. |
| `CapturingSession` | A synchronous `StreamingSession` sink the resumed run streams into, capturing text + metadata. |
| `PassivationController` | REST surface (`/pause`, `/resume`, `/checkpoints/{id}`) — the policy trigger. |
| `PassivationConfig` | Provides the `InMemoryCheckpointStore` (creator-owns-lifecycle: `stop()` on shutdown) and the runtime. |
| `PassivationDeliveryTest` | Drives pause → persist → resume over HTTP and asserts the snapshot is persisted with the conversation history **and** that resume continues from it. |

## What the delivery test proves

`PassivationDeliveryTest` does not assert "the bean exists." It asserts the
content reached the subsystem:

1. **Pause persists the conversation** — after `POST /pause`, it loads the
   snapshot straight from the `CheckpointStore` and asserts the history equals
   the original two turns and the pending message was captured.
2. **Resume continues from it** — after `POST /resume`, it asserts the restored
   history size equals the original and that the reply contains a token
   (`ORD-77781`) that lived **only** in the restored history — impossible from a
   cold restart.

## Making it survive a real restart

The default `InMemoryCheckpointStore` survives a pause/resume within one JVM,
which is all this proof needs. Swap in `SqliteCheckpointStore` (same
`atmosphere-checkpoint` module) or the Postgres store and the snapshots survive
a full process restart — with no change to `PassivationService`.
