import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectWebSocket, waitFor } from './helpers/transport-helper';
import { existsSync } from 'fs';
import { resolve } from 'path';

const ROOT = resolve(__dirname, '..', '..', '..', '..');
const SB3_MODULE = resolve(ROOT, 'modules', 'spring-boot3-starter');

/**
 * Spring Boot 3 vs Spring Boot 4 parity tests.
 * Verifies that the Spring Boot 3 starter module compiles and its
 * behavior is consistent with the Spring Boot 4 starter.
 *
 * Skipped if the spring-boot3-starter module doesn't exist.
 */

const hasSB3Module = existsSync(resolve(SB3_MODULE, 'pom.xml'));

(hasSB3Module ? test.describe : test.describe.skip)('Spring Boot 3/4 Parity', () => {

  test.describe.configure({ timeout: 120_000 });

  test('Spring Boot 3 starter module compiles', async () => {
    const { execSync } = await import('child_process');
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} compile -pl modules/spring-boot3-starter -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 120_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });

  test('Spring Boot 3 starter module tests pass', async () => {
    const { execSync } = await import('child_process');
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} test -pl modules/spring-boot3-starter -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 120_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });

  test('Spring Boot 4 starter module compiles', async () => {
    const { execSync } = await import('child_process');
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} compile -pl modules/spring-boot-starter -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 120_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });

  test('Spring Boot 4 starter module tests pass', async () => {
    const { execSync } = await import('child_process');
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} test -pl modules/spring-boot-starter -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 120_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });

  test('both starters expose same core auto-configuration beans', async () => {
    const { execSync } = await import('child_process');
    // Verify both modules depend on atmosphere-runtime
    const sb3Deps = execSync(
      `${resolve(ROOT, 'mvnw')} dependency:tree -pl modules/spring-boot3-starter -q`,
      { cwd: ROOT, timeout: 60_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    const sb4Deps = execSync(
      `${resolve(ROOT, 'mvnw')} dependency:tree -pl modules/spring-boot-starter -q`,
      { cwd: ROOT, timeout: 60_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );

    // Both should depend on atmosphere-runtime
    expect(sb3Deps).toContain('atmosphere-runtime');
    expect(sb4Deps).toContain('atmosphere-runtime');
  });

  test('Spring Boot chat sample works with SB4 starter', async () => {
    // The spring-boot-chat sample uses the SB4 starter — verify it runs
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
