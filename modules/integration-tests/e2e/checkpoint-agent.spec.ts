/*
 * Durable HITL coverage for samples/spring-boot-checkpoint-agent.
 *
 * TARGET FLOW (from the 4.0.36 e2e coverage plan, gap #1):
 *   1. Start the sample (Spring Boot on a unique port) against a per-suite
 *      sqlite checkpoint.db.
 *   2. Send a WebSocket prompt to the `dispatch` coordinator
 *      (`/atmosphere/agent/dispatch`). The coordinator handler responds
 *      (streaming bytes arrive on the socket) — the transport is alive.
 *   3. Seed a fake analyzer snapshot directly into the sqlite store so
 *      the approve path can be exercised without requiring a live LLM
 *      runtime (the checkpoint HTTP surface and the ApproverAgent are
 *      what this spec certifies end-to-end — not the LLM call inside
 *      DispatchCoordinator, which needs a real API key).
 *   4. GET /api/checkpoints?coordination=dispatch — observe the seeded
 *      snapshot id.
 *   5. Restart the JVM (SampleServer.restart) against the same
 *      checkpoint.db file. Expect the snapshot to survive.
 *   6. POST /api/checkpoints/{id}/approve — resume the workflow. The
 *      controller recovers the original request from the stored state,
 *      invokes the approver, and chains the approver's result as a
 *      child snapshot.
 *   7. Assert the child snapshot's state text matches what ApproverAgent
 *      produces for that request/approver pair.
 *
 * CURRENT STATE OF THE SAMPLE (verified 2026-04-13 on branch
 *     fix-checkpoint-agent against commits 563a2ad + this commit):
 *
 *   (A) Bug 1a (AgentProcessor) FIXED in 563a2ad. `atmosphere-agent`'s
 *       AgentProcessor no longer hard-references McpRegistry symbols in
 *       its bytecode; MCP integration moved to the reflectively-loaded
 *       org.atmosphere.agent.processor.McpAgentRegistration class.
 *       @Agent classes (analyzer, approver) register cleanly at servlet
 *       init without any atmosphere-mcp dependency.
 *
 *   (A') Bug 1a-prime (CoordinatorProcessor) FIXED in this commit.
 *        `atmosphere-coordinator`'s CoordinatorProcessor no longer links
 *        McpRegistry symbols either — its MCP integration moved to the
 *        reflectively-loaded McpCoordinatorRegistration bridge. The
 *        DispatchCoordinator annotation scan now completes, the
 *        `/atmosphere/agent/dispatch` WebSocket path is mapped, and the
 *        coordinator transport responds to inbound frames.
 *
 *   (B) Bug 1b (CheckpointConfig) FIXED in 563a2ad. `CheckpointConfig`
 *       wires `SqliteCheckpointStore` by default on a `target/checkpoint.db`
 *       path; honors `atmosphere.checkpoint.store=sqlite|in-memory` and
 *       `atmosphere.checkpoint.sqlite.path=<path>` via application.yml
 *       so the spec can point the sample at a per-test tmp file via
 *       ATMOSPHERE_CHECKPOINT_SQLITE_PATH.
 *
 * WHAT THIS FILE SHIPS TODAY:
 *   - Baseline REST tests: the Spring MVC controller is reachable and
 *     returns sane responses for unknown ids.
 *   - Bug 1a regression: the @Agent classes register cleanly.
 *   - Bug 1a-prime regression: no NoClassDefFoundError on McpRegistry,
 *     no `No AtmosphereHandler maps request` for /atmosphere/agent/dispatch,
 *     and a live WebSocket probe gets bytes back from the coordinator.
 *   - Bug 1b regression: the durable SqliteCheckpointStore banner is
 *     logged and the checkpoint.db file is created.
 *   - End-to-end approval: seed an analyzer snapshot into the sqlite
 *     store, POST /approve, assert ApproverAgent executed and chained
 *     its result as a child snapshot with the expected text.
 *   - Flagship durable HITL: seed a snapshot, SIGTERM the JVM, restart
 *     against the same checkpoint.db, GET /api/checkpoints — assert the
 *     pre-restart snapshot survived — then POST /approve and assert the
 *     resumed workflow produced the expected approver output.
 */

import { test, expect } from '@playwright/test';
import { startSample, type SampleConfig, type SampleServer } from './fixtures/sample-server';
import { WebSocket } from 'ws';
import { mkdtempSync, rmSync, existsSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { execFileSync } from 'child_process';
import { randomUUID } from 'crypto';

// Unique port to avoid collisions with every other spec in the matrix
// (8080-8097 + 8099-8104 are already claimed). 8108 is free.
const PORT = 8108;

// Per-suite tmp dir for the sqlite checkpoint.db. We control the path
// via the `atmosphere.checkpoint.sqlite.path` property that Bug 1b's
// fix now honors, so the sample writes durable snapshots somewhere the
// test can inspect and clean up.
const checkpointDir = mkdtempSync(join(tmpdir(), 'atm-ckpt-'));
const checkpointDbPath = join(checkpointDir, 'checkpoint.db');

// Inlined config instead of extending the SAMPLES map in the fixture,
// so Phase 2 agents editing fixtures/sample-server.ts don't conflict
// with this spec.
const config: SampleConfig = {
  name: 'spring-boot-checkpoint-agent',
  dir: 'spring-boot-checkpoint-agent',
  port: PORT,
  type: 'spring-boot',
  env: {
    ATMOSPHERE_CHECKPOINT_STORE: 'sqlite',
    ATMOSPHERE_CHECKPOINT_SQLITE_PATH: checkpointDbPath,
  },
};

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(180_000);
  server = await startSample(config);
});

test.afterAll(async () => {
  await server?.stop();
  try { rmSync(checkpointDir, { recursive: true, force: true }); } catch { /* best-effort */ }
});

const REST_PATH = '/api/checkpoints';
const COORDINATOR_PATH = '/atmosphere/agent/dispatch';
const COORDINATION_ID = 'dispatch';

// ── Helpers ──────────────────────────────────────────────────────────

/**
 * Seed an analyzer snapshot into the sqlite checkpoint.db directly via
 * the `sqlite3` CLI. This matches the on-disk row format that
 * SqliteCheckpointStore writes (jdbc:sqlite, default serialization via
 * Jackson `ObjectMapper()` on the CoordinationEvent record) so the
 * CheckpointController reads it back as a LinkedHashMap whose
 * `resultText` value embeds the `{"request":"..."}` blob the
 * approve-path regex is designed to recover.
 *
 * The app must be STOPPED (or the sqlite lock will collide) when this
 * is called. Returns the generated checkpoint id.
 */
function seedAnalyzerSnapshot(request: string): string {
  const id = randomUUID();
  const createdAt = new Date().toISOString();
  // The analyzer emits a JSON string whose top-level fields are
  // "request", "risk", "recommendation" — matches AnalyzerAgent.analyze.
  const resultText = JSON.stringify({
    request,
    risk: 'HIGH',
    recommendation: 'requires manual approval',
  });
  // CoordinationEvent.AgentCompleted record shape. Jackson's default
  // ObjectMapper() writes records as plain objects (no @class marker);
  // when SqliteCheckpointStore reads the state back with
  // `readValue(json, Object.class)` it returns a LinkedHashMap and
  // CheckpointController falls through to `String.valueOf(state)`
  // whose toString contains the embedded `"request":"..."` substring.
  const stateJson = JSON.stringify({
    coordinationId: COORDINATION_ID,
    agentName: 'analyzer',
    skill: 'analyze',
    resultText,
    duration: 'PT0.05S',
    timestamp: createdAt,
  });
  const metadataJson = '{}';

  // Write the row via a parameterless bind (sqlite3 CLI) — easier to
  // escape via a temp .sql file than via a command-line argument.
  const sqlPath = join(checkpointDir, 'seed-' + id + '.sql');
  const sqlBody =
    "INSERT INTO checkpoints (id, parent_id, coordination_id, agent_name, "
    + "state_json, metadata_json, created_at) VALUES ("
    + sqlLiteral(id) + ", NULL, "
    + sqlLiteral(COORDINATION_ID) + ", "
    + sqlLiteral('analyzer') + ", "
    + sqlLiteral(stateJson) + ", "
    + sqlLiteral(metadataJson) + ", "
    + sqlLiteral(createdAt) + ");\n";
  writeFileSync(sqlPath, sqlBody);
  execFileSync('sqlite3', [checkpointDbPath, '.read ' + sqlPath], {
    encoding: 'utf-8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  try { rmSync(sqlPath, { force: true }); } catch { /* best-effort */ }
  return id;
}

/** SQL single-quoted string literal (doubling embedded quotes). */
function sqlLiteral(s: string): string {
  return "'" + s.replace(/'/g, "''") + "'";
}

/** Delete every row from the checkpoints table so tests are re-runnable. */
function clearCheckpointStore(): void {
  execFileSync('sqlite3', [checkpointDbPath, 'DELETE FROM checkpoints;'], {
    encoding: 'utf-8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

test.describe('spring-boot-checkpoint-agent — durable HITL coverage (gap #1)', () => {

  // ── Baseline ──────────────────────────────────────────────────────

  test('CheckpointController REST surface is reachable', async () => {
    const res = await fetch(`${server.baseUrl}${REST_PATH}?coordination=${COORDINATION_ID}`);
    expect(res.status).toBe(200);

    const body = await res.json();
    expect(Array.isArray(body)).toBe(true);
  });

  test('show/delete/approve return 404 for unknown snapshot ids', async () => {
    const unknown = '00000000-0000-0000-0000-000000000000';

    const show = await fetch(`${server.baseUrl}${REST_PATH}/${unknown}`);
    expect(show.status).toBe(404);

    const del = await fetch(`${server.baseUrl}${REST_PATH}/${unknown}`, { method: 'DELETE' });
    expect(del.status).toBe(404);

    const approve = await fetch(
      `${server.baseUrl}${REST_PATH}/${unknown}/approve?by=alice`,
      { method: 'POST' },
    );
    expect(approve.status).toBe(404);
  });

  // ── Bug 1b: durable store wiring ─────────────────────────────────

  test('Bug 1b: CheckpointConfig wires SqliteCheckpointStore by default', async () => {
    // The sample ships with `atmosphere.checkpoint.store=sqlite` as the
    // default, and CheckpointConfig logs the resolved db path on startup.
    // Both conditions must hold, otherwise the durable-HITL promise in
    // the README is fiction.
    const log = server.getOutput();
    expect(log).toContain('Using SqliteCheckpointStore at');
    expect(log).toContain(checkpointDbPath);

    // And the file must actually have been created on disk — sqlite-jdbc
    // lazy-creates the db on first connection, which happens when the
    // @Bean is instantiated. If this is false, sqlite-jdbc failed to
    // connect (or the wiring dropped through to in-memory silently).
    expect(existsSync(checkpointDbPath)).toBe(true);
  });

  // ── Bug 1a: AgentProcessor bytecode fix ──────────────────────────

  test('Bug 1a: agent classes register without any NoClassDefFoundError on AgentProcessor', async () => {
    // AgentProcessor's bytecode used to carry a synthetic lambda method
    // whose return type was org.atmosphere.mcp.registry.McpRegistry$ParamEntry,
    // so loading AgentProcessor in a sample that omits the optional
    // atmosphere-mcp jar linked against a class the classloader cannot
    // find. Both `analyzer` and `approver` @Agent classes must now
    // register cleanly — if either of these disappears from the log,
    // the lambda-in-class-file regression has returned.
    const log = server.getOutput();
    expect(log).toContain("Agent 'analyzer' registered at /atmosphere/agent/analyzer");
    expect(log).toContain("Agent 'approver' registered at /atmosphere/agent/approver");

    // And the NoClassDefFoundError traceback in the log, if any, must
    // NOT come from o.a.agent.processor.AgentProcessor.
    const mcpCrashInAgentProcessor = /NoClassDefFoundError[\s\S]{0,2000}?org\.atmosphere\.agent\.processor\.AgentProcessor/m;
    expect(log).not.toMatch(mcpCrashInAgentProcessor);
  });

  // ── Bug 1a-prime: CoordinatorProcessor bytecode fix ──────────────

  test('Bug 1a-prime: CoordinatorProcessor registers dispatch coordinator without linking McpRegistry', async () => {
    // The startup log must show the dispatch coordinator registration
    // succeeded — before this fix, CoordinatorProcessor.class loading
    // triggered a NoClassDefFoundError on McpRegistry$ParamEntry via
    // its synthetic lambda return type, aborting annotation scanning
    // for DispatchCoordinator entirely.
    const log = server.getOutput();
    expect(log).toContain(
      'Found Annotation in class org.atmosphere.samples.springboot.checkpoint.DispatchCoordinator'
        + ' being scanned: interface org.atmosphere.coordinator.annotation.Coordinator',
    );
    expect(log).toContain("Coordinator 'dispatch' registered");
    expect(log).toContain("fleet: 2 agents");

    // No McpRegistry-flavored NoClassDefFoundError must appear anywhere
    // in the startup log — that's the exact regression indicator for
    // Bug 1a-prime. (ClassNotFoundException is also caught as a belt-
    // and-braces guard against classloader-level resolution failures.)
    expect(log).not.toContain('McpRegistry$ParamEntry');
    const mcpCrashInCoordinatorProcessor = /NoClassDefFoundError[\s\S]{0,2000}?org\.atmosphere\.coordinator\.processor\.CoordinatorProcessor/m;
    expect(log).not.toMatch(mcpCrashInCoordinatorProcessor);
  });

  test('fu2: live coordinator probe writes snapshot with coordinationId="dispatch" (not UUID)', async () => {
    // Round-2 follow-up to the 4.0.37 journal-bridge fix. Before this fix
    // JournalingAgentFleet.coordinationId() returned UUID.randomUUID() per
    // invocation, so REST callers who filtered `?coordination=dispatch`
    // never matched any of the snapshots the coordinator actually wrote.
    // After the fix the coordinator's @Coordinator(name="dispatch") value
    // IS the coordination id on every recorded event — the REST filter
    // becomes the primary discovery path for coordinator-driven snapshots.
    //
    // This test drives a real WebSocket probe against the dispatch
    // coordinator (no sqlite3 CLI seeding) and asserts that:
    //   1. GET /api/checkpoints?coordination=dispatch returns non-empty
    //   2. Every row's coordinationId equals the literal "dispatch"
    //   3. At least one row is the analyzer (the only specialist the
    //      coordinator drives before the LLM stream kicks in — so even
    //      without an API key, this row is the proof of life for the
    //      coordinator → JournalingAgentFleet → CheckpointingCoordinationJournal
    //      → SqliteCheckpointStore pipeline end-to-end)
    clearCheckpointStore();

    const wsUrl = server.baseUrl.replace('http://', 'ws://') + COORDINATOR_PATH;
    await new Promise<void>((resolve) => {
      const ws = new WebSocket(wsUrl);
      const timer = setTimeout(() => {
        try { ws.close(); } catch { /* ignore */ }
        resolve();
      }, 4_000);
      ws.on('open', () => {
        try { ws.send('please refund order 777'); } catch { /* ignore */ }
      });
      // Give the server a moment after any inbound frame to finish the
      // synchronous JournalingAgentFleet.record(...) → SqliteCheckpointStore.save.
      ws.on('message', () => { /* observed */ });
      ws.on('close', () => { clearTimeout(timer); resolve(); });
      ws.on('error', () => { clearTimeout(timer); resolve(); });
    });

    // Small settle window for the SqliteCheckpointStore INSERT to land.
    await new Promise((r) => setTimeout(r, 500));

    const res = await fetch(
      `${server.baseUrl}${REST_PATH}?coordination=${COORDINATION_ID}`,
    );
    expect(res.status).toBe(200);
    const rows = await res.json() as Array<Record<string, unknown>>;
    expect(rows.length,
      'GET /api/checkpoints?coordination=dispatch must return a non-empty '
      + 'list after a live coordinator probe — if this is empty, '
      + 'JournalingAgentFleet.coordinationId() is still emitting UUIDs and '
      + 'the REST filter is silently broken').toBeGreaterThan(0);

    // Every row filtered by `coordination=dispatch` must actually have
    // coordinationId="dispatch" — not a UUID dressed up in the REST
    // response. This is the invariant the round-2 fix exists to uphold.
    for (const row of rows) {
      expect(row.coordinationId,
        `row ${row.id} must carry the coordinator name as coordinationId, `
        + `got ${String(row.coordinationId)}`).toBe(COORDINATION_ID);
    }

    // At least one row must be the analyzer — that's the specialist the
    // DispatchCoordinator.onPrompt dispatches synchronously before the LLM
    // stream starts, so its AgentCompleted/AgentFailed boundary event is
    // the one the CheckpointingCoordinationJournal persists. If no
    // analyzer row shows up, the coordinator prompt method never ran.
    const analyzerRows = rows.filter((r) => r.agentName === 'analyzer');
    expect(analyzerRows.length).toBeGreaterThan(0);

    clearCheckpointStore();
  });

  test('Bug 1a-prime: /atmosphere/agent/dispatch WebSocket handler is mapped and responds', async () => {
    // Drive a real WebSocket probe against the dispatch coordinator.
    // Before the fix, the handler was never mapped (Atmosphere logged
    // "No AtmosphereHandler maps request for /atmosphere/agent/dispatch"
    // on every frame and closed the socket). After the fix, the handler
    // is mapped and the coordinator streams a progress frame back over
    // the socket before the DispatchCoordinator.onPrompt body runs.
    const wsUrl = server.baseUrl.replace('http://', 'ws://') + COORDINATOR_PATH;

    const frames: string[] = await new Promise((resolve, reject) => {
      const ws = new WebSocket(wsUrl);
      const collected: string[] = [];
      const timer = setTimeout(() => {
        try { ws.close(); } catch { /* ignore */ }
        resolve(collected);
      }, 6_000);
      ws.on('open', () => {
        try { ws.send('hello dispatch'); } catch { /* ignore */ }
      });
      ws.on('message', (d) => {
        collected.push(d.toString());
      });
      ws.on('close', () => { clearTimeout(timer); resolve(collected); });
      ws.on('error', (e) => {
        clearTimeout(timer);
        if (collected.length === 0) {
          reject(new Error('dispatch WS failed: ' + e.message));
        } else {
          resolve(collected);
        }
      });
    });

    // At least one frame must come back — the AI pipeline sends a
    // "Connecting to built-in..." progress event immediately when the
    // DispatchCoordinator's @Prompt streaming session starts. If zero
    // frames arrive, the handler is not mapped (regression).
    expect(frames.length).toBeGreaterThan(0);

    // And the server log must NOT contain the mapping-failure line —
    // that's the exact symptom Bug 1a-prime produced.
    const log = server.getOutput();
    expect(log).not.toContain(
      'No AtmosphereHandler maps request for /atmosphere/agent/dispatch',
    );
    // The coordinator must also have logged receipt of the probe —
    // proof the @Prompt method actually ran (vs. Atmosphere silently
    // dropping the frame at the handler-lookup layer).
    expect(log).toContain('Dispatch received: hello dispatch');
  });

  // ── Bug 4: Spring journal bridge ─────────────────────────────────

  test('Bug 4: Spring CoordinationJournal bean is bridged into CoordinatorProcessor', async () => {
    // Before this fix, CoordinatorProcessor.resolveJournal used ServiceLoader
    // exclusively and silently fell through to CoordinationJournal.NOOP for
    // Spring-wired beans, swallowing every AgentCompleted/AgentFailed event
    // inside DefaultAgentFleet. The CheckpointConfig @Bean was created and
    // its store was real, but the coordinator-driven path produced ZERO
    // snapshots because no one was calling journal.record(...).
    //
    // After the fix, the spring-boot-starter's
    // AtmosphereCoordinatorAutoConfiguration bridges the journal bean onto
    // framework.getAtmosphereConfig().properties() and the processor logs
    // it as "(externally managed)" during startup. If this log line is
    // missing, the bridge auto-config did not activate and the journal is
    // back to NOOP — every coordinator run would silently drop snapshots.
    const log = server.getOutput();
    expect(log).toMatch(
      /Bridged Spring CoordinationJournal bean .*CheckpointingCoordinationJournal.* into CoordinatorProcessor/,
    );
    expect(log).toMatch(
      /CoordinationJournal:.*CheckpointingCoordinationJournal.*\(externally managed\)/,
    );
  });

  test('Bug 4: SqliteCheckpointStore initialized exactly once on startup (no double-start)', async () => {
    // Wave A audit (commit 306b58c47d timeframe) flagged the
    // "SqliteCheckpointStore initialized" log line printing twice on boot —
    // once from CheckpointConfig.checkpointStore() calling store.start()
    // directly, and once from CheckpointConfig.coordinationJournal()
    // calling journal.start(), which delegates to store.start() inside
    // CheckpointingCoordinationJournal. After the fix the bean no longer
    // calls journal.start() (Spring owns the lifecycle, the bridged
    // journal lifecycle is a no-op for the underlying delegate, and the
    // store is only started once by the @Bean(destroyMethod="stop") path).
    //
    // CoordinatorProcessor's resolveJournal must also NOT call start() on
    // a bridged journal — the externally-managed flag is what enforces
    // this. If either path regresses, the line below appears twice and
    // the assertion fires.
    const log = server.getOutput();
    const occurrences = (log.match(/SqliteCheckpointStore initialized/g) ?? []).length;
    expect(occurrences,
      `expected exactly one "SqliteCheckpointStore initialized" log line on boot, `
      + `but saw ${occurrences} — duplicate start() means the journal bridge `
      + `is double-initializing the store (Bug 4 follow-up regression)`)
      .toBe(1);
  });

  // ── Approval resumption (single JVM) ──────────────────────────────

  test('approveResumesWorkflow — seeded analyzer snapshot → approver chain via /approve', async () => {
    // Seeding writes through sqlite3 CLI. sqlite-jdbc and the CLI can
    // coexist on the same file with brief locks because each one-shot
    // INSERT releases immediately — in practice we stop-seed-restart
    // for the durable test below and only seed-while-running here; if
    // the CLI write races with the JDBC connection we'll see
    // "database is locked" and must fall back to stop-seed-restart.
    clearCheckpointStore();
    const analyzerId = seedAnalyzerSnapshot('please refund order 1234');

    // The REST list endpoint must now surface the seeded snapshot.
    const listRes = await fetch(
      `${server.baseUrl}${REST_PATH}?coordination=${COORDINATION_ID}`,
    );
    expect(listRes.status).toBe(200);
    const list = await listRes.json() as Array<Record<string, unknown>>;
    expect(list.length).toBeGreaterThanOrEqual(1);
    const seeded = list.find((row) => row.id === analyzerId);
    expect(seeded).toBeDefined();
    expect(seeded?.coordinationId).toBe(COORDINATION_ID);
    expect(seeded?.agentName).toBe('analyzer');

    // Drive the resumption: the controller extracts the original request
    // from the stored state via its regex, invokes ApproverAgent.execute,
    // and forks the result as a child snapshot.
    const approveRes = await fetch(
      `${server.baseUrl}${REST_PATH}/${analyzerId}/approve?by=alice`,
      { method: 'POST' },
    );
    expect(approveRes.status).toBe(200);
    const child = await approveRes.json() as Record<string, unknown>;
    // Child snapshot's parentId is the seeded analyzer row.
    expect(child.parentId).toBe(analyzerId);
    // State text matches ApproverAgent.execute's exact output.
    expect(child.state).toBe("Executed 'please refund order 1234' approved by alice");

    // The store now contains both snapshots (ordering is newest-first).
    const after = await fetch(
      `${server.baseUrl}${REST_PATH}?coordination=${COORDINATION_ID}`,
    );
    const afterList = await after.json() as Array<Record<string, unknown>>;
    const ids = afterList.map((r) => r.id);
    expect(ids).toContain(analyzerId);
    expect(ids).toContain(child.id);
  });

  // ── Flagship: durable HITL survives JVM restart ──────────────────

  test('durable HITL survives JVM restart — flagship 4.0.36 story', async () => {
    // Clean slate + seed a fresh analyzer snapshot.
    clearCheckpointStore();
    const analyzerId = seedAnalyzerSnapshot('refund order 9999');

    // Confirm the snapshot is visible pre-restart.
    const preRes = await fetch(`${server.baseUrl}${REST_PATH}/${analyzerId}`);
    expect(preRes.status).toBe(200);
    const preSnap = await preRes.json() as Record<string, unknown>;
    expect(preSnap.id).toBe(analyzerId);
    expect(preSnap.coordinationId).toBe(COORDINATION_ID);

    // SIGTERM + respawn against the same checkpoint.db file (same env
    // means same ATMOSPHERE_CHECKPOINT_SQLITE_PATH). This is the exact
    // flow the README promises: a JVM restart preserves durable
    // snapshots so the HITL pause can span days of operator downtime.
    await server.restart();

    // Post-restart: the snapshot must still be there, identified by the
    // same id, with the same coordinationId, agentName, and state text.
    // Equality of the surface fields is what "durable across restart"
    // actually means — anything weaker is not the flagship story.
    const postRes = await fetch(`${server.baseUrl}${REST_PATH}/${analyzerId}`);
    expect(postRes.status).toBe(200);
    const postSnap = await postRes.json() as Record<string, unknown>;
    expect(postSnap.id).toBe(analyzerId);
    expect(postSnap.coordinationId).toBe(COORDINATION_ID);
    expect(postSnap.agentName).toBe('analyzer');
    // The embedded request text survives the round-trip through sqlite.
    expect(String(postSnap.state)).toContain('refund order 9999');

    // Resumption: POST /approve on the surviving snapshot must invoke
    // ApproverAgent and chain its result as a child snapshot. This is
    // the HITL continuation the durable store exists to enable.
    const approveRes = await fetch(
      `${server.baseUrl}${REST_PATH}/${analyzerId}/approve?by=bob`,
      { method: 'POST' },
    );
    expect(approveRes.status).toBe(200);
    const resumed = await approveRes.json() as Record<string, unknown>;
    expect(resumed.parentId).toBe(analyzerId);
    expect(resumed.state).toBe("Executed 'refund order 9999' approved by bob");

    // Cleanup so re-running the spec back-to-back leaves a clean slate.
    clearCheckpointStore();
  });
});
