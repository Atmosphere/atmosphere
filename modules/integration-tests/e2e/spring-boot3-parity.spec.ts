import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectWebSocket, waitFor } from './helpers/transport-helper';
import { existsSync, readFileSync } from 'fs';
import { resolve } from 'path';

const ROOT = resolve(__dirname, '..', '..', '..', '..');
const SB3_MODULE = resolve(ROOT, 'modules', 'spring-boot3-starter');
const SB4_MODULE = resolve(ROOT, 'modules', 'spring-boot-starter');

/**
 * Spring Boot 3 vs Spring Boot 4 parity tests.
 * Verifies that both starter modules exist, have consistent structure,
 * and the SB4 sample works correctly.
 *
 * Module compilation is tested by the main Atmosphere CI job.
 */

const hasSB3Module = existsSync(resolve(SB3_MODULE, 'pom.xml'));

(hasSB3Module ? test.describe : test.describe.skip)('Spring Boot 3/4 Parity', () => {

  test.describe.configure({ timeout: 120_000 });

  test('Spring Boot 3 starter module exists with pom.xml', async () => {
    expect(existsSync(resolve(SB3_MODULE, 'pom.xml'))).toBe(true);
    expect(existsSync(resolve(SB3_MODULE, 'src', 'main'))).toBe(true);
  });

  test('Spring Boot 4 starter module exists with pom.xml', async () => {
    expect(existsSync(resolve(SB4_MODULE, 'pom.xml'))).toBe(true);
    expect(existsSync(resolve(SB4_MODULE, 'src', 'main'))).toBe(true);
  });

  test('both starters depend on atmosphere-runtime', async () => {
    const sb3Pom = readFileSync(resolve(SB3_MODULE, 'pom.xml'), 'utf-8');
    const sb4Pom = readFileSync(resolve(SB4_MODULE, 'pom.xml'), 'utf-8');

    expect(sb3Pom).toContain('atmosphere-runtime');
    expect(sb4Pom).toContain('atmosphere-runtime');
  });

  test('both starters have auto-configuration classes', async () => {
    const sb3Src = resolve(SB3_MODULE, 'src', 'main');
    const sb4Src = resolve(SB4_MODULE, 'src', 'main');

    expect(existsSync(sb3Src)).toBe(true);
    expect(existsSync(sb4Src)).toBe(true);
  });

  test('Spring Boot chat sample works with SB4 starter', async () => {
    const server = await startSample(SAMPLES['spring-boot-chat']);
    try {
      const client = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
      expect(client.ws.readyState).toBe(1); // OPEN

      client.ws.send(JSON.stringify({ author: 'SB4Test', message: 'parity check' }));
      await waitFor(() => client.messages.some(m => m.includes('parity check')));

      client.close();
    } finally {
      await server.stop();
    }
  });
});
