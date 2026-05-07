import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8114;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

/**
 * E2E coverage for AgentPassivation snapshot/resume across two sequential
 * WebSocket connections. Pairs with AgentPassivationTest (unit) — proves
 * the same shape survives Atmosphere's transport, the in-memory
 * CheckpointStore retains the snapshot between connections, and resume
 * merges the snapshot's history into a fresh dispatch.
 */
test.describe('AI Passivation E2E', () => {

  test('@smoke pause then resume across two connections preserves history', async () => {
    // First connection: pause.
    let checkpointId: string;
    const c1 = new AiWsClient(server.wsUrl, '/ai/passivation');
    try {
      await c1.connect();
      c1.send('pause');
      await c1.waitForDone(15_000);

      const id = c1.metadata.get('passivation.id');
      expect(id).toBeTruthy();
      expect(typeof id).toBe('string');
      checkpointId = id as string;
      expect(c1.metadata.get('passivation.history.size')).toBe(2);
    } finally {
      c1.close();
    }

    // Second connection: resume with an external signal.
    const c2 = new AiWsClient(server.wsUrl, '/ai/passivation');
    try {
      await c2.connect();
      c2.send(`resume:${checkpointId!}:approved by Alice`);
      await c2.waitForDone(15_000);

      // External signal replaced the snapshot's pending message.
      expect(c2.metadata.get('resumed.message')).toBe('approved by Alice');
      // Snapshot history (2 turns) overrode the empty base context.
      expect(c2.metadata.get('resumed.history.size')).toBe(2);
      // Snapshot's session id wins over the base's.
      expect(c2.metadata.get('resumed.session.id')).toBe('session-paused');
      expect(c2.tokens.join('')).toContain('resumed: approved by Alice');
    } finally {
      c2.close();
    }
  });

  test('load snapshot reads back persisted reason and pending message', async () => {
    let checkpointId: string;
    const c1 = new AiWsClient(server.wsUrl, '/ai/passivation');
    try {
      await c1.connect();
      c1.send('pause');
      await c1.waitForDone(15_000);
      checkpointId = c1.metadata.get('passivation.id') as string;
    } finally {
      c1.close();
    }

    const c2 = new AiWsClient(server.wsUrl, '/ai/passivation');
    try {
      await c2.connect();
      c2.send(`load:${checkpointId!}`);
      await c2.waitForDone(15_000);

      expect(c2.metadata.get('loaded.reason')).toBe('awaiting human approval');
      expect(c2.metadata.get('loaded.pending')).toBe('draft contract for legal');
      expect(c2.metadata.get('loaded.history.size')).toBe(2);
    } finally {
      c2.close();
    }
  });

  test('resume without an external signal replays the pending message', async () => {
    let checkpointId: string;
    const c1 = new AiWsClient(server.wsUrl, '/ai/passivation');
    try {
      await c1.connect();
      c1.send('pause');
      await c1.waitForDone(15_000);
      checkpointId = c1.metadata.get('passivation.id') as string;
    } finally {
      c1.close();
    }

    const c2 = new AiWsClient(server.wsUrl, '/ai/passivation');
    try {
      await c2.connect();
      // Empty signal after the colon → snapshot's pending replaces it.
      c2.send(`resume:${checkpointId!}:`);
      await c2.waitForDone(15_000);

      expect(c2.metadata.get('resumed.message')).toBe('draft contract for legal');
    } finally {
      c2.close();
    }
  });

  test('missing checkpoint surfaces an error frame', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/passivation');
    try {
      await client.connect();
      client.send('missing:nonexistent-id');
      await client.waitForDone(15_000);

      expect(client.errors.length).toBeGreaterThan(0);
      expect(client.errors[0]).toContain('Passivated checkpoint not found');
    } finally {
      client.close();
    }
  });
});
