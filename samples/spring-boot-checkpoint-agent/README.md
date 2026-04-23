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
| `CheckpointConfig.java` | Wires a pluggable `CheckpointStore` (SQLite by default, in-memory opt-in) + wraps the journal with `CheckpointingCoordinationJournal` |
| `CommitmentConfig.java` | Installs an `Ed25519CommitmentSigner` bean + flips `CommitmentRecordsFlag` on so every dispatch emits a signed VC-subtype record on the journal |
| `DispatchCoordinator.java` | `@Coordinator` that arms the signer per session, calls the analyzer, and returns the checkpoint pointer. Signed `CommitmentRecord`s land on the journal alongside each `AgentDispatched` event. |
| `AnalyzerAgent.java` | `@Agent` whose `AgentCompleted` events are captured as snapshots |
| `ApproverAgent.java` | `@Agent` invoked by `CheckpointController#approve` to resume the workflow after HITL approval |
| `CheckpointController.java` | REST surface over the `CheckpointStore`; `/approve` is the resumption point that calls the approver and chains its result |

## Governance — signed audit trail across HITL pause

**This sample is unique**: durable session + Ed25519-signed commitment
records paired across the HITL pause boundary. MS Agent Framework drops
state on pause; LangChain has no checkpoint primitive; both make it
impossible to produce a signed trail that survives a pause-and-resume.

Every analyzer dispatch publishes a `CommitmentRecord` with fields
`{issuer, principal, subject, scope, issuedAt, outcome, proof}` where
`proof` carries the Ed25519 signature over the record's canonical bytes.
A reviewer approving hours later can verify the signature against the
coordinator's public key — cryptographic proof that (a) the request
came from the expected coordinator, (b) the request is unmodified,
(c) the approval decision is bound to this specific request.

```bash
# Start the sample
./mvnw spring-boot:run -pl samples/spring-boot-checkpoint-agent

# Fire a request
wscat -c ws://localhost:8080/atmosphere/dispatch
> please refund order 1234

# Inspect the Commitments tab at /atmosphere/admin/ — each analyzer hop
# renders with a ✓ verified badge. The record's proof.signature field
# can be verified offline with the coordinator's Ed25519 public key
# (published at /api/admin/governance/health → policies[].digest).

# Hours later — approve via REST (resumes the workflow from the checkpoint)
curl -X POST http://localhost:8080/api/checkpoints/{id}/approve
# The approver agent's dispatch ALSO emits a signed CommitmentRecord,
# cryptographically linked to the original analyzer snapshot.
```

## Checkpoint store backends

`CheckpointConfig` honors two Spring properties:

| Property | Default | Description |
|----------|---------|-------------|
| `atmosphere.checkpoint.store` | `sqlite` | `sqlite` or `in-memory` |
| `atmosphere.checkpoint.sqlite.path` | `target/checkpoint.db` | Filesystem path to the SQLite database file |

- **SQLite (default).** Snapshots survive JVM restarts. The database lives
  under `target/` so `mvn clean` resets the sample to a fresh slate, but
  a plain restart (`Ctrl-C` → `./mvnw spring-boot:run ...`) keeps every
  pending approval intact. Override the path with
  `ATMOSPHERE_CHECKPOINT_SQLITE_PATH=/tmp/demo.db` (or the equivalent
  `-Datmosphere.checkpoint.sqlite.path=...`) for a stable location.
- **In-memory.** Start the sample with
  `--atmosphere.checkpoint.store=in-memory` (or
  `ATMOSPHERE_CHECKPOINT_STORE=in-memory`) to get a fresh store on every
  boot — useful in integration tests that don't want to carry state
  forward.

Additional backends (JDBC, Redis, ...) plug into the same `CheckpointStore`
SPI — the rest of the sample is backend-agnostic.

## Notes

- The analyzer's `@AiTool` returns a deterministic JSON response so the
  demo runs without any LLM API key. In a real deployment, configure an
  AI runtime (`atmosphere-spring-ai`, `atmosphere-langchain4j`, etc.) and
  call it from `@Prompt`.
- Durable by default: the flagship HITL story (approve hours after the
  initial prompt, possibly across a JVM restart) works out of the box
  with the default SQLite store. Switch to in-memory only if you
  explicitly want snapshots to vanish at shutdown.
