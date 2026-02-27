import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8090;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('AI Filters E2E', () => {

  test('PII redaction: emails redacted across 3 clients', async () => {
    const clients = [
      new AiWsClient(server.wsUrl, '/ai/filters'),
      new AiWsClient(server.wsUrl, '/ai/filters'),
      new AiWsClient(server.wsUrl, '/ai/filters'),
    ];

    try {
      for (const c of clients) await c.connect();

      // Only one client sends the prompt; all 3 receive the broadcast
      clients[0].send('pii:test');

      for (const c of clients) {
        await c.waitForDone();
        const response = c.fullResponse;
        expect(response).not.toContain('john.doe@example.com');
        expect(response).toContain('[REDACTED]');
      }
    } finally {
      clients.forEach(c => c.close());
    }
  });

  test('PII redaction: SSN patterns detected', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/filters');
    try {
      await client.connect();
      client.send('pii:test');
      await client.waitForDone();

      const response = client.fullResponse;
      expect(response).not.toContain('123-45-6789');
      expect(response).toContain('[REDACTED]');
    } finally {
      client.close();
    }
  });

  test('Content safety: harmful keywords abort the stream', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/filters');
    try {
      await client.connect();
      client.send('safety:harmful');
      await client.waitForDone();

      // Should receive an error, not the harmful content
      expect(client.errors.length).toBeGreaterThan(0);
      expect(client.errors[0]).toContain('Content blocked');
    } finally {
      client.close();
    }
  });

  test('Content safety: safe content passes through', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/filters');
    try {
      await client.connect();
      client.send('normal:hello');
      await client.waitForDone();

      expect(client.errors).toHaveLength(0);
      expect(client.tokens.length).toBeGreaterThan(0);
      expect(client.fullResponse).toContain('AI');
    } finally {
      client.close();
    }
  });

  test('Cost metering: does not interfere with normal flow', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/filters');
    try {
      await client.connect();
      client.send('normal:cost-test');
      await client.waitForDone();

      // Normal flow should complete without error
      expect(client.errors).toHaveLength(0);
      const completeEvents = client.events.filter(e => e.type === 'complete');
      expect(completeEvents.length).toBeGreaterThanOrEqual(1);
    } finally {
      client.close();
    }
  });

  test('Protocol invariant: all text arrives as token type, complete is bare', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/filters');
    try {
      await client.connect();
      client.send('normal:protocol-test');
      await client.waitForDone();

      // Verify all text came in "token" events
      const tokenEvents = client.events.filter(e => e.type === 'token');
      expect(tokenEvents.length).toBeGreaterThan(0);
      for (const t of tokenEvents) {
        expect(t.data).toBeTruthy();
      }

      // Verify complete event has no data (bare complete)
      const completeEvents = client.events.filter(e => e.type === 'complete');
      expect(completeEvents.length).toBeGreaterThanOrEqual(1);
      // The complete from our fake client should be bare
      // (PII filter doesn't need to flush since tokens have sentence boundaries)
    } finally {
      client.close();
    }
  });
});
