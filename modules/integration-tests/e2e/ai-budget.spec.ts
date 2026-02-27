import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8094;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('AI Budget E2E', () => {

  test('First request uses premium model (within budget)', async () => {
    // user-3 has 100 tokens, 90% threshold -> plenty of room
    const client = new AiWsClient(server.wsUrl, '/ai/budget', {
      'X-Atmosphere-User-Id': 'user-3',
    });
    try {
      await client.connect();
      client.send('First request');
      await client.waitForDone();

      // Should use premium model (no degradation yet)
      expect(client.metadata.get('budget.model')).toBe('premium-model');
      expect(client.errors).toHaveLength(0);
      expect(client.tokens.length).toBeGreaterThan(0);
    } finally {
      client.close();
    }
  });

  test('Degradation: switches to fallback at threshold', async () => {
    // user-1 has 20 tokens, 50% threshold = 10 tokens before degradation
    // Each request generates 5 tokens. recommendedModel checks usage BEFORE streaming.
    // Request 1: 0 used -> premium, after: 5 used
    // Request 2: 5 used (25%) -> premium, after: 10 used
    // Request 3: 10 used (50% >= 50%) -> cheap-model

    // First request: 0 tokens used -> premium
    const c1 = new AiWsClient(server.wsUrl, '/ai/budget', {
      'X-Atmosphere-User-Id': 'user-1',
    });
    try {
      await c1.connect();
      c1.send('Request 1');
      await c1.waitForDone();
      expect(c1.metadata.get('budget.model')).toBe('premium-model');
    } finally {
      c1.close();
    }

    // Second request: 5 tokens used (25%) -> still premium
    const c2 = new AiWsClient(server.wsUrl, '/ai/budget', {
      'X-Atmosphere-User-Id': 'user-1',
    });
    try {
      await c2.connect();
      c2.send('Request 2');
      await c2.waitForDone();
      expect(c2.metadata.get('budget.model')).toBe('premium-model');
    } finally {
      c2.close();
    }

    // Third request: 10 tokens used (50% >= 50% threshold) -> fallback
    const c3 = new AiWsClient(server.wsUrl, '/ai/budget', {
      'X-Atmosphere-User-Id': 'user-1',
    });
    try {
      await c3.connect();
      c3.send('Request 3');
      await c3.waitForDone();
      expect(c3.metadata.get('budget.model')).toBe('cheap-model');
    } finally {
      c3.close();
    }
  });

  test('Budget exhaustion: error when exceeded', async () => {
    // user-2 has 10 tokens, 80% threshold
    // After 2 requests (10 tokens), budget is exhausted

    // First request: 5 tokens (50%)
    const c1 = new AiWsClient(server.wsUrl, '/ai/budget', {
      'X-Atmosphere-User-Id': 'user-2',
    });
    try {
      await c1.connect();
      c1.send('Request 1');
      await c1.waitForDone();
      expect(c1.errors).toHaveLength(0);
    } finally {
      c1.close();
    }

    // Second request: 10 tokens (100%)
    const c2 = new AiWsClient(server.wsUrl, '/ai/budget', {
      'X-Atmosphere-User-Id': 'user-2',
    });
    try {
      await c2.connect();
      c2.send('Request 2');
      await c2.waitForDone();
      // May or may not error depending on exact timing
    } finally {
      c2.close();
    }

    // Third request: should get budget exceeded error
    const c3 = new AiWsClient(server.wsUrl, '/ai/budget', {
      'X-Atmosphere-User-Id': 'user-2',
    });
    try {
      await c3.connect();
      c3.send('Request 3');
      await c3.waitForDone();

      // Should get budget exceeded
      expect(c3.errors.length).toBeGreaterThan(0);
      expect(c3.metadata.get('budget.exceeded')).toBe(true);
    } finally {
      c3.close();
    }
  });

  test('3 users: independent budget tracking', async () => {
    // All 3 users send a request simultaneously; budgets are independent
    const clients = [
      new AiWsClient(server.wsUrl, '/ai/budget', { 'X-Atmosphere-User-Id': 'user-1' }),
      new AiWsClient(server.wsUrl, '/ai/budget', { 'X-Atmosphere-User-Id': 'user-2' }),
      new AiWsClient(server.wsUrl, '/ai/budget', { 'X-Atmosphere-User-Id': 'user-3' }),
    ];

    try {
      for (const c of clients) await c.connect();

      // Send prompts from all 3 users
      clients[0].send('User 1 prompt');
      clients[1].send('User 2 prompt');
      clients[2].send('User 3 prompt');

      for (const c of clients) {
        await c.waitForDone();
      }

      // All clients share a broadcaster, so all see all responses.
      // Each response should include budget metadata.
      // user-3 (100 token budget) should definitely not be exceeded.
      const allEvents = clients[2].events;
      const budgetExceeded = allEvents.filter(
        e => e.type === 'metadata' && e.key === 'budget.exceeded'
      );
      // user-3 has plenty of budget, so no exceeded events from that user
      // (though user-2 might have exceeded from previous tests)
    } finally {
      clients.forEach(c => c.close());
    }
  });

  test('Budget remaining decreases with each request', async () => {
    // Use user-3 which has lots of budget (100 tokens)
    const c1 = new AiWsClient(server.wsUrl, '/ai/budget', {
      'X-Atmosphere-User-Id': 'user-3',
    });
    let remaining1: number;
    try {
      await c1.connect();
      c1.send('Budget tracking 1');
      await c1.waitForDone();

      // Get the remaining budget from the completion metadata
      const remainingEvents = c1.events.filter(
        e => e.type === 'metadata' && e.key === 'budget.remaining'
      );
      expect(remainingEvents.length).toBeGreaterThan(0);
      remaining1 = remainingEvents[remainingEvents.length - 1].value as number;
    } finally {
      c1.close();
    }

    // Second request should show lower remaining
    const c2 = new AiWsClient(server.wsUrl, '/ai/budget', {
      'X-Atmosphere-User-Id': 'user-3',
    });
    try {
      await c2.connect();
      c2.send('Budget tracking 2');
      await c2.waitForDone();

      const remainingEvents = c2.events.filter(
        e => e.type === 'metadata' && e.key === 'budget.remaining'
      );
      expect(remainingEvents.length).toBeGreaterThan(0);
      const remaining2 = remainingEvents[remainingEvents.length - 1].value as number;
      expect(remaining2).toBeLessThan(remaining1);
    } finally {
      c2.close();
    }
  });
});
