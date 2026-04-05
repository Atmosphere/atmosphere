# Spring Boot Checkpoint Agent

Demonstrates **durable agent execution** using `atmosphere-checkpoint`:
an approval-gated workflow where an agent's result is persisted as a
`WorkflowSnapshot` so the workflow can be reviewed, approved, or forked
hours or days later.

## What this sample shows

A durable HITL workflow split across two entry points, linked by a
`CheckpointStore`:

1. **`DispatchCoordinator`** (`@Coordinator`) receives a request over
   WebSocket/SSE and hands it to the `analyzer` agent. The stream ends
   there — no thread is kept alive waiting for approval.
2. **`CheckpointingCoordinationJournal`** (wired in `CheckpointConfig`)
   captures the analyzer's completion event as a `WorkflowSnapshot`.
3. **`CheckpointController`** exposes the store over HTTP. A reviewer
   (human, automated gate, or the `atmosphere checkpoint` CLI) lists
   pending snapshots, inspects them, and calls `POST
   /api/checkpoints/{id}/approve`.
4. The **`/approve` endpoint actually resumes the workflow**: it recovers
   the original request from the analyzer's snapshot state, invokes
   `ApproverAgent.execute()`, and chains the approver's result as a child
   snapshot. The child IS the workflow continuation — the parent chain
   now runs `analyzer → approver` across what may have been days of
   pause between the two HTTP calls.

## Run

```bash
./mvnw spring-boot:run -pl samples/spring-boot-checkpoint-agent
```

The app listens on `http://localhost:8095`.

## Try it

Open a WebSocket to the coordinator:

```bash
# Any WebSocket client, e.g. wscat
wscat -c ws://localhost:8095/atmosphere/agent/dispatch
> please refund order 1234
# response includes the analysis + checkpoint reference
```

Then inspect the checkpoints:

```bash
# List snapshots for the dispatch coordination
curl http://localhost:8095/api/checkpoints?coordination=dispatch

# View a single snapshot (paste an id from the list above)
curl http://localhost:8095/api/checkpoints/<id>

# Resume the workflow: recovers the original request from the snapshot,
# invokes the approver, and chains its result as a child snapshot.
curl -X POST 'http://localhost:8095/api/checkpoints/<id>/approve?by=alice'
# -> { "parentId": "<id>", "agentName": null,
#      "state": "Executed 'please refund order 1234' approved by alice", ... }

# Override the recovered request (useful if the reviewer amended it):
curl -X POST 'http://localhost:8095/api/checkpoints/<id>/approve?by=alice&request=refund%20order%209999'

# Fork with arbitrary state (non-approver branch exploration)
curl -X POST 'http://localhost:8095/api/checkpoints/<id>/fork?state=custom-branch'

# Delete
curl -X DELETE http://localhost:8095/api/checkpoints/<id>
```

Or use the Atmosphere CLI (if installed):

```bash
atmosphere checkpoint list --coordination dispatch
atmosphere checkpoint show <id>
atmosphere checkpoint approve <id> --by alice
atmosphere checkpoint fork <id> --state custom-branch
```

## Key code

| File | Purpose |
|------|---------|
| `CheckpointConfig.java` | Wires `InMemoryCheckpointStore` + wraps the journal with `CheckpointingCoordinationJournal` |
| `DispatchCoordinator.java` | `@Coordinator` that calls the analyzer and returns the checkpoint pointer to the caller |
| `AnalyzerAgent.java` | `@Agent` whose `AgentCompleted` events are captured as snapshots |
| `ApproverAgent.java` | `@Agent` invoked by `CheckpointController#approve` to resume the workflow after HITL approval |
| `CheckpointController.java` | REST surface over the `CheckpointStore`; `/approve` is the resumption point that calls the approver and chains its result |

## Swapping the store backend

Replace the `@Bean` in `CheckpointConfig` to use a different implementation
(JDBC, Redis, etc. — planned follow-up modules). The contract is the
`CheckpointStore` SPI; the rest of the sample is backend-agnostic.

## Notes

- The analyzer's `@AiTool` returns a deterministic JSON response so the
  demo runs without any LLM API key. In a real deployment, configure an
  AI runtime (`atmosphere-spring-ai`, `atmosphere-langchain4j`, etc.) and
  call it from `@Prompt`.
- The checkpoint store is in-memory: restarting the JVM discards all
  snapshots. Swap in a persistent backend for durability across restarts.
