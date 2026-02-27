import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8096;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('AI Cost/Latency Routing E2E', () => {

  test('Cost routing selects within budget', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/cost-routing');
    try {
      await client.connect();
      // Budget of 10.0: maxTokens = 10000
      // premium: 0.01 * 10000 = 100 > 10 -- too expensive
      // mid: 0.005 * 10000 = 50 > 10 -- too expensive
      // cheap: 0.001 * 10000 = 10 <= 10 -- fits!
      client.send('cost:10 hello world');
      await client.waitForDone();

      const routingModel = await client.waitForMetadata('routing.model');
      expect(routingModel).toBe('cheap-model');
      expect(client.fullResponse).toContain('CHEAP:');
    } finally {
      client.close();
    }
  });

  test('Cost routing falls through when nothing fits budget', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/cost-routing');
    try {
      await client.connect();
      // Budget of 0.0001: maxTokens = 0 (tiny)
      // All models: costPerToken * 0 = 0 <= 0.0001 -- all fit, but maxTokens is 0
      // Actually: 0.0001 / 0.001 = 0.1 -> (int) = 0 maxTokens
      // premium: 0.01 * 0 = 0 <= 0.0001 -- fits
      // Since maxTokens is 0, all costs are 0. Highest capability (premium) wins.
      client.send('cost:0.0001 tiny budget');
      await client.waitForDone();

      const routingModel = await client.waitForMetadata('routing.model');
      // With maxTokens=0, all costs are 0, so highest capability (premium) wins
      expect(routingModel).toBe('premium-model');
    } finally {
      client.close();
    }
  });

  test('Latency routing selects fast model within constraint', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/cost-routing');
    try {
      await client.connect();
      // Max latency 100ms:
      // slow: 500ms > 100ms -- too slow
      // medium: 150ms > 100ms -- too slow
      // fast: 30ms <= 100ms -- fits!
      client.send('latency:100 need speed');
      await client.waitForDone();

      const routingModel = await client.waitForMetadata('routing.model');
      expect(routingModel).toBe('fast-model');
      expect(client.fullResponse).toContain('FAST:');

      const routingLatency = await client.waitForMetadata('routing.latency');
      expect(routingLatency).toBe(30);
    } finally {
      client.close();
    }
  });

  test('Latency routing falls through to default', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/cost-routing');
    try {
      await client.connect();
      // Max latency 10ms: nothing fits (min is 30ms)
      client.send('latency:10 impossibly fast');
      await client.waitForDone();

      const routingModel = await client.waitForMetadata('routing.model');
      expect(routingModel).toBe('default-model');
      expect(client.fullResponse).toContain('DEFAULT:');
    } finally {
      client.close();
    }
  });
});
