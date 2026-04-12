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

interface ContentFrame {
  type: 'content';
  contentType: string;
  mimeType: string;
  data: string;
  fileName?: string;
  sessionId: string;
  seq: number;
}

function contentFrames(client: AiWsClient): ContentFrame[] {
  return client.events
    .filter(e => (e as unknown as ContentFrame).type === 'content')
    .map(e => e as unknown as ContentFrame);
}

test.describe('Multi-modal Content Wire Protocol (Wave 2)', () => {

  test('@smoke image content emits base64 PNG with correct frame shape', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/multimodal');
    try {
      await client.connect();
      client.send('image');
      await client.waitForDone(15_000);

      const frames = contentFrames(client);
      expect(frames.length).toBe(1);

      const img = frames[0];
      expect(img.contentType).toBe('image');
      expect(img.mimeType).toBe('image/png');
      expect(img.data).toBeTruthy();
      expect(img.sessionId).toBeTruthy();
      expect(img.seq).toBeGreaterThan(0);

      // base64 must be decodable
      const decoded = Buffer.from(img.data, 'base64');
      expect(decoded.length).toBeGreaterThan(0);
      // PNG magic bytes
      expect(decoded[0]).toBe(0x89);
      expect(decoded[1]).toBe(0x50);
    } finally {
      client.close();
    }
  });

  test('audio content emits base64 WAV with correct frame shape', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/multimodal');
    try {
      await client.connect();
      client.send('audio');
      await client.waitForDone(15_000);

      const frames = contentFrames(client);
      expect(frames.length).toBe(1);

      const audio = frames[0];
      expect(audio.contentType).toBe('audio');
      expect(audio.mimeType).toBe('audio/wav');
      expect(audio.data).toBeTruthy();

      const decoded = Buffer.from(audio.data, 'base64');
      // WAV magic: RIFF
      expect(String.fromCharCode(decoded[0], decoded[1], decoded[2], decoded[3])).toBe('RIFF');
    } finally {
      client.close();
    }
  });

  test('file content emits base64 CSV with fileName', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/multimodal');
    try {
      await client.connect();
      client.send('file');
      await client.waitForDone(15_000);

      const frames = contentFrames(client);
      expect(frames.length).toBe(1);

      const file = frames[0];
      expect(file.contentType).toBe('file');
      expect(file.mimeType).toBe('text/csv');
      expect(file.fileName).toBe('results.csv');
      expect(file.data).toBeTruthy();

      const decoded = Buffer.from(file.data, 'base64').toString('utf-8');
      expect(decoded).toContain('name,value');
      expect(decoded).toContain('test,42');
    } finally {
      client.close();
    }
  });

  test('multi-type sequence delivers all three types in order', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/multimodal');
    try {
      await client.connect();
      client.send('multi');
      await client.waitForDone(15_000);

      const frames = contentFrames(client);
      expect(frames.length).toBe(3);

      expect(frames[0].contentType).toBe('image');
      expect(frames[1].contentType).toBe('audio');
      expect(frames[2].contentType).toBe('file');

      // seq numbers must be monotonically increasing
      expect(frames[1].seq).toBeGreaterThan(frames[0].seq);
      expect(frames[2].seq).toBeGreaterThan(frames[1].seq);
    } finally {
      client.close();
    }
  });
});
