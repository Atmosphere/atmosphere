/**
 * End-to-end coverage for the EpisodicMemoryStore SPI: store/recall/forget
 * round-trip on the in-memory impl + on-disk persistence across instances
 * on JsonFileEpisodicMemoryStore. Both modes capture the JFR
 * EpisodicMemoryAccess events and report counts so the spec pins
 * observability alongside the functional behavior.
 */
import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8111;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('EpisodicMemoryStore — SPI round-trip + JSON-file persistence', () => {

  test('@smoke in-memory store/recall/forget round-trip emits JFR events', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/episodic-memory');
    try {
      await client.connect();
      client.send('roundtrip');
      await client.waitForDone(15_000);

      const afterStore = client.metadata.get('ai.memory.roundtrip.size.afterStore') as number;
      const feedback = client.metadata.get('ai.memory.roundtrip.feedback.count') as number;
      const recent = client.metadata.get('ai.memory.roundtrip.recent.count') as number;
      const forgot = client.metadata.get('ai.memory.roundtrip.forgot') as boolean;
      const afterForget = client.metadata.get('ai.memory.roundtrip.size.afterForget') as number;
      const jfrTotal = client.metadata.get('ai.memory.roundtrip.jfr.totalEvents') as number;
      const jfrStore = client.metadata.get('ai.memory.roundtrip.jfr.store') as number;
      const jfrRecall = client.metadata.get('ai.memory.roundtrip.jfr.recall') as number;
      const jfrForget = client.metadata.get('ai.memory.roundtrip.jfr.forget') as number;

      expect(afterStore).toBe(3);
      expect(feedback, 'type filter must return only FEEDBACK entries').toBe(1);
      expect(recent).toBe(3);
      expect(forgot).toBe(true);
      expect(afterForget).toBe(2);

      const diagnostic = client.metadata.get('ai.memory.roundtrip.jfr.diagnostic') as string;
      expect(diagnostic, 'JFR parser must finish without exceptions').toBe('ok');
      expect(jfrTotal, 'JFR recording must contain events').toBeGreaterThan(0);
      expect(jfrStore).toBe(3);
      expect(jfrRecall).toBe(2);
      expect(jfrForget).toBe(1);
    } finally {
      client.close();
    }
  });

  test('@smoke JsonFileEpisodicMemoryStore persists across instance recreation', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/episodic-memory');
    try {
      await client.connect();
      client.send('persist');
      await client.waitForDone(15_000);

      const writerSize = client.metadata.get('ai.memory.persist.writer.size') as number;
      const fileBytes = client.metadata.get('ai.memory.persist.file.bytes') as number;
      const readerSize = client.metadata.get('ai.memory.persist.reader.size') as number;
      const recallCount = client.metadata.get('ai.memory.persist.recall.count') as number;
      const jfrTotal = client.metadata.get('ai.memory.persist.jfr.totalEvents') as number;
      const jfrStore = client.metadata.get('ai.memory.persist.jfr.store') as number;
      const jfrRecall = client.metadata.get('ai.memory.persist.jfr.recall') as number;

      expect(writerSize).toBe(3);
      expect(fileBytes, 'JSON document must be non-empty after writes')
        .toBeGreaterThan(10);
      expect(readerSize,
        'a fresh store instance against the same path must read the prior entries')
        .toBe(3);
      expect(recallCount).toBe(3);

      // 3 STOREs on the writer + 1 RECALL on the reader; the reader's
      // ensureLoaded() does not emit (load is internal, not an SPI access).
      expect(jfrTotal, 'JFR recording must contain events').toBeGreaterThan(0);
      expect(jfrStore).toBe(3);
      expect(jfrRecall).toBe(1);
    } finally {
      client.close();
    }
  });
});
