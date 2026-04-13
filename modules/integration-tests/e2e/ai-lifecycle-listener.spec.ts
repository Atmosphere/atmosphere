import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8099;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('AgentLifecycleListener Events (Wave 3)', () => {

  test('@smoke listener captures onToolCall and onToolResult', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/lifecycle-listener');
    try {
      await client.connect();
      client.send('listen');
      await client.waitForDone(15_000);

      expect(client.metadata.get('listener.toolCall.count')).toBe(1);
      expect(client.metadata.get('listener.toolCall.name')).toBe('get_weather');
      expect(client.metadata.get('listener.toolResult.count')).toBe(1);
      expect(client.metadata.get('listener.toolResult.name')).toBe('get_weather');
      expect(client.metadata.get('listener.toolResult.preview')).toBe('{"temp":22}');
    } finally {
      client.close();
    }
  });

  test('throwing listener does not prevent recording listener from capturing', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/lifecycle-listener');
    try {
      await client.connect();
      client.send('error-listener');
      await client.waitForDone(15_000);

      // The recording listener (added first) must still capture
      // despite the throwing listener (added second) exploding
      expect(client.metadata.get('listener.toolCall.count')).toBe(1);
      expect(client.metadata.get('listener.toolCall.name')).toBe('get_weather');
      expect(client.metadata.get('listener.toolResult.count')).toBe(1);
    } finally {
      client.close();
    }
  });
});

/**
 * Gap #6 — full lifecycle coverage via AbstractAgentRuntime.execute().
 *
 * Drives a test-only FakeAgentRuntime subclass whose doExecute either
 * succeeds or throws, so the real fireStart/fireCompletion/fireError
 * helpers in AbstractAgentRuntime run against the attached listener list.
 * Also verifies that a listener throwing from onStart does not abort the
 * execution pipeline — the sibling recording listener must still observe
 * onStart → onCompletion.
 */
test.describe('AgentLifecycleListener full lifecycle (Gap #6)', () => {

  test('onStart fires exactly once before onCompletion on success', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/lifecycle-listener');
    try {
      await client.connect();
      client.send('fire-start-complete');
      await client.waitForDone(15_000);

      expect(client.metadata.get('listener.onStart.count')).toBe(1);
      expect(client.metadata.get('listener.onCompletion.count')).toBe(1);
      expect(client.metadata.get('listener.onError.count')).toBe(0);
      expect(client.metadata.get('listener.startBeforeCompletion')).toBe(true);
      expect(client.metadata.get('listener.caught')).toBe(false);
    } finally {
      client.close();
    }
  });

  test('onError fires exactly once on failure, onCompletion does not', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/lifecycle-listener');
    try {
      await client.connect();
      client.send('fire-error');
      await client.waitForDone(15_000);

      expect(client.metadata.get('listener.onStart.count')).toBe(1);
      expect(client.metadata.get('listener.onCompletion.count')).toBe(0);
      expect(client.metadata.get('listener.onError.count')).toBe(1);
      // The fake runtime throws from doExecute, so AbstractAgentRuntime.execute
      // rethrows after fireError — the handler captures and reports it.
      expect(client.metadata.get('listener.caught')).toBe(true);
    } finally {
      client.close();
    }
  });

  test('listener throwing from onStart does not break the pipeline', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/lifecycle-listener');
    try {
      await client.connect();
      client.send('throwing-start');
      await client.waitForDone(15_000);

      // Recording listener (added first) saw onStart; the throwing listener
      // exploded; execution still continued and onCompletion still fired.
      expect(client.metadata.get('listener.onStart.count')).toBe(1);
      expect(client.metadata.get('listener.onCompletion.count')).toBe(1);
      expect(client.metadata.get('listener.onError.count')).toBe(0);
      expect(client.metadata.get('listener.startBeforeCompletion')).toBe(true);
      expect(client.metadata.get('listener.caught')).toBe(false);
    } finally {
      client.close();
    }
  });
});
