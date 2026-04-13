/*
 * Durable HITL coverage for samples/spring-boot-checkpoint-agent.
 *
 * TARGET FLOW (from the 4.0.36 e2e coverage plan, gap #1):
 *   1. Start the sample (Spring Boot on a unique port).
 *   2. Send a WebSocket prompt to the `dispatch` coordinator
 *      (`/atmosphere/agent/dispatch`). The coordinator calls the
 *      `analyzer` agent; the CheckpointingCoordinationJournal bridge
 *      persists an AgentCompleted snapshot.
 *   3. GET /api/checkpoints?coordination=dispatch — observe the pending
 *      snapshot id.
 *   4. SIGTERM the JVM, wait for exit.
 *   5. Restart the JVM against the same persistent checkpoint backing
 *      store. Expect the pending snapshot to survive.
 *   6. POST /api/checkpoints/{id}/approve — resume the workflow. The
 *      controller recovers the original request from the stored state,
 *      invokes the approver, and chains the approver's result as a
 *      child snapshot.
 *   7. Assert the child snapshot's state text matches what a single-run
 *      execution of the sample would produce.
 *
 * CURRENT STATE OF THE SAMPLE (verified 2026-04-13 against
 *     samples/spring-boot-checkpoint-agent @ main):
 *
 *   (A) The coordinator HTTP/WS endpoint is never registered.
 *       Atmosphere's ClasspathScanner aborts annotation processing of
 *       DispatchCoordinator with:
 *
 *         java.lang.NoClassDefFoundError:
 *             org/atmosphere/mcp/registry/McpRegistry$ParamEntry
 *
 *       Root cause: `atmosphere-agent`'s AgentProcessor hard-references
 *       McpRegistry.ParamEntry, and `atmosphere-agent` declares
 *       `atmosphere-mcp` as <optional>true</optional>, so it is NOT
 *       pulled transitively. The sample pom does not depend on
 *       atmosphere-mcp either, so the scanner fails and
 *       `/atmosphere/agent/dispatch` is never mapped — every WebSocket
 *       open returns 500 "No AtmosphereHandler maps request for
 *       /atmosphere/agent/dispatch". Until this is fixed (either by
 *       making the mcp dep non-optional, guarding AgentProcessor's mcp
 *       references behind reflection/Class.forName, or adding the dep
 *       to the sample pom), the workflow's entry point is unreachable
 *       over the wire.
 *
 *   (B) The sample uses InMemoryCheckpointStore unconditionally.
 *       CheckpointConfig.checkpointStore() hard-wires
 *       `new InMemoryCheckpointStore()` with no property switch, and
 *       the README's own "Notes" section states: "The checkpoint store
 *       is in-memory: restarting the JVM discards all snapshots."
 *       The plan gist assumes a `checkpoint.db` (SqliteCheckpointStore)
 *       backing store, but no wiring exists. To actually demonstrate
 *       durable HITL survival across JVM restart, the sample needs to
 *       accept a property (e.g. `atmosphere.checkpoint.store=sqlite`
 *       plus `atmosphere.checkpoint.sqlite.path=...`) and switch beans
 *       accordingly. Per task constraints the sample must not be
 *       modified from this worktree.
 *
 * WHAT THIS FILE SHIPS TODAY:
 *   - A baseline test that starts the sample, verifies the Spring MVC
 *     CheckpointController is reachable, and pins the exact failure
 *     mode of the coordinator endpoint. That pin will flip this spec
 *     red the moment gap (A) is fixed — forcing whoever fixes it to
 *     lift the test.skip guards below and wire up the full durable
 *     flow.
 *   - Skipped placeholders for the approve-resume and restart-survival
 *     flows, gated behind env vars so the spec is trivially re-enabled
 *     once both gaps are closed:
 *         CHECKPOINT_AGENT_COORDINATOR_FIXED=1  (gap A resolved)
 *         CHECKPOINT_AGENT_DURABLE_STORE=1      (gap B resolved — sample
 *                                                honors a property that
 *                                                selects a persistent
 *                                                backing store)
 *
 * TODO(4.0.37): Fix gap (A) — add `atmosphere-mcp` to
 *   samples/spring-boot-checkpoint-agent/pom.xml (or make the dep
 *   non-optional in modules/agent) — then lift the `approveResumesWorkflow`
 *   skip, add the WebSocket-driven prompt phase, and assert the child
 *   snapshot state.
 * TODO(4.0.37): Fix gap (B) — make CheckpointConfig honor a Spring
 *   property to swap in SqliteCheckpointStore with a configurable
 *   `checkpoint.db` path. Then lift the `durableHitlSurvivesJvmRestart`
 *   skip, point the sample at a tmp file, drive the full kill-and-restart
 *   flow, and assert the analyzer snapshot survives the bounce.
 */

import { test, expect } from '@playwright/test';
import { startSample, type SampleConfig, type SampleServer } from './fixtures/sample-server';
import { WebSocket } from 'ws';
import { mkdtempSync, rmSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';

// Unique port to avoid collisions with every other spec in the matrix
// (8080-8097 + 8099-8104 are already claimed). 8108 is free.
const PORT = 8108;

// Inlined config instead of extending the SAMPLES map in the fixture,
// so Phase 2 agents editing fixtures/sample-server.ts don't conflict
// with this spec.
const config: SampleConfig = {
  name: 'spring-boot-checkpoint-agent',
  dir: 'spring-boot-checkpoint-agent',
  port: PORT,
  type: 'spring-boot',
  // We intentionally do NOT set `readyPath` to an Atmosphere path — the
  // coordinator endpoint is broken upstream (see header). The default
  // port-open + HTTP-root probe is enough to let CheckpointController
  // serve requests.
};

let server: SampleServer;
// Reserved for the durable-store flow once gap (B) is fixed. When the
// sample honors a property to select SqliteCheckpointStore, this will
// be a freshly-minted tmp dir passed via `env:` so kill + restart
// targets the same `checkpoint.db` file.
let checkpointDir: string | undefined;

test.beforeAll(async () => {
  test.setTimeout(180_000);
  checkpointDir = mkdtempSync(join(tmpdir(), 'atm-ckpt-'));
  server = await startSample(config);
});

test.afterAll(async () => {
  await server?.stop();
  if (checkpointDir) {
    try { rmSync(checkpointDir, { recursive: true, force: true }); } catch { /* best-effort */ }
  }
});

const REST_PATH = '/api/checkpoints';
const COORDINATOR_PATH = '/atmosphere/agent/dispatch';
const COORDINATION_ID = 'dispatch';

test.describe('spring-boot-checkpoint-agent — durable HITL coverage (gap #1)', () => {

  // ── Baseline: what the sample CAN do today ────────────────────────

  test('CheckpointController REST surface is reachable', async () => {
    // Plain Spring MVC controller — unaffected by the Atmosphere
    // ClasspathScanner failure, so this must stay green even while
    // the coordinator endpoint is broken.
    const res = await fetch(`${server.baseUrl}${REST_PATH}?coordination=${COORDINATION_ID}`);
    expect(res.status).toBe(200);

    const body = await res.json();
    expect(Array.isArray(body)).toBe(true);
    // Fresh server, in-memory store, no snapshots have been written.
    expect(body.length).toBe(0);
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

  // ── Pinned failure mode: drives whoever fixes gap (A) here ─────────

  test('PIN gap-A: coordinator endpoint drops messages with a mapping error', async () => {
    // If this test starts passing OUTRIGHT (i.e. the assertions that pin
    // the failure mode become false), gap (A) is fixed — delete this test
    // AND lift the `approveResumesWorkflow` skip below.
    //
    // The WebSocket handshake itself succeeds because Tomcat's JSR356
    // upgrade completes before Atmosphere's per-request handler lookup
    // runs. The failure shows up in two places: (1) the startup log
    // contains the ClasspathScanner NoClassDefFoundError on McpRegistry
    // (from Atmosphere servlet init), and (2) sending any message
    // triggers `No AtmosphereHandler maps request for /atmosphere/agent/dispatch`.
    const wsUrl = server.baseUrl.replace('http://', 'ws://') + COORDINATOR_PATH;

    await new Promise<void>((resolve, reject) => {
      const ws = new WebSocket(wsUrl);
      const timer = setTimeout(() => {
        try { ws.close(); } catch { /* ignore */ }
        resolve(); // OK if the server never wrote anything back — the
                   // server-side log is what carries the real signal.
      }, 4_000);
      ws.on('open', () => {
        // Poke the broken dispatcher so the "No AtmosphereHandler maps
        // request" line gets written to the server log.
        try { ws.send('probe'); } catch { /* ignore */ }
      });
      ws.on('error', () => { clearTimeout(timer); resolve(); });
      ws.on('close', () => { clearTimeout(timer); resolve(); });
      // Surface truly unexpected failures
      setTimeout(() => reject(new Error('WebSocket probe hung')), 8_000);
    });

    // Give the server log a moment to flush the mapping-exception line
    // that Atmosphere's DefaultWebSocketProcessor writes asynchronously.
    await new Promise((r) => setTimeout(r, 500));

    const log = server.getOutput();
    // Ground truth verified 2026-04-13: at servlet init the classpath
    // scanner aborts because `atmosphere-agent` references
    // `org.atmosphere.mcp.registry.McpRegistry$ParamEntry` and the
    // sample pom does not depend on atmosphere-mcp.
    expect(log).toContain('McpRegistry');
    expect(log).toMatch(/NoClassDefFoundError|ClassNotFoundException/);
    // Consequence: the @Coordinator(name="dispatch") path is never mapped
    // to an AtmosphereHandler, so any message to the WebSocket triggers
    // AtmosphereMappingException.
    expect(log).toContain(
      'No AtmosphereHandler maps request for /atmosphere/agent/dispatch',
    );
  });

  // ── Target flows, gated until the upstream gaps close ──────────────
  //
  // Each gated test opens with an explicit `test.skip(condition, reason)`
  // INSIDE the test body so the baseline tests above still run. Flipping
  // the env var (once the upstream fix lands) unblocks them individually.

  test('approveResumesWorkflow — analyzer → approver chain via checkpoint', async () => {
    test.skip(
      process.env.CHECKPOINT_AGENT_COORDINATOR_FIXED !== '1',
      'Gap (A): coordinator endpoint unreachable — see file header.',
    );
    // Target flow (steps 1-3 + 6 + 7 from the header, single-JVM):
    //   1. Open WS /atmosphere/agent/dispatch, send "please refund order 1234".
    //   2. Wait for the coordinator's stream-end response mentioning
    //      the checkpoint id.
    //   3. GET /api/checkpoints?coordination=dispatch — expect a
    //      single AgentCompleted snapshot from the analyzer.
    //   4. POST /api/checkpoints/{id}/approve?by=alice.
    //   5. Expect a 200 with a child snapshot whose `parentId`
    //      matches the analyzer snapshot and whose `state` equals
    //      "Executed 'please refund order 1234' approved by alice"
    //      (verified against ApproverAgent.execute).
    //   6. GET /api/checkpoints — expect 2 snapshots total.
    //
    // Implement once gap (A) is fixed. The WebSocket plumbing pattern
    // matches dentist-agent.spec.ts (split on '|', filter heartbeats).
    throw new Error('Not yet implemented — unblocked when gap (A) is fixed.');
  });

  test('durable HITL survives JVM restart — flagship 4.0.36 story', async () => {
    test.skip(
      process.env.CHECKPOINT_AGENT_COORDINATOR_FIXED !== '1'
        || process.env.CHECKPOINT_AGENT_DURABLE_STORE !== '1',
      'Gaps (A) and/or (B): see file header.',
    );
    // Target flow (full steps 1-7 from the header):
    //   Pre: beforeAll spawns the sample with a config that points
    //        CheckpointConfig at SqliteCheckpointStore on a tmp
    //        `checkpoint.db` path (passed via config.env once the
    //        sample honors such a property — `checkpointDir` is
    //        already allocated for this purpose).
    //   1. Open WS, send prompt, wait for checkpoint id in response.
    //   2. GET /api/checkpoints — capture the analyzer snapshot id.
    //   3. await server.restart() — fixture SIGTERMs, waits for exit,
    //      respawns with the same env (same checkpoint.db path).
    //   4. GET /api/checkpoints — assert the same snapshot id still
    //      exists (durable survival is the whole point).
    //   5. POST /api/checkpoints/{id}/approve?by=alice — assert 200
    //      and child snapshot state matches the expected text.
    //   6. DELETE both snapshots to leave the store clean for
    //      re-runnability across suite runs.
    throw new Error('Not yet implemented — unblocked when gaps (A) and (B) are fixed.');
  });
});
