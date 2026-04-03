import { test, expect } from '@playwright/test';
import { execSync } from 'child_process';
import { existsSync } from 'fs';
import { resolve } from 'path';

const ROOT = resolve(__dirname, '..', '..', '..', '..');
const WASYNC_MODULE = resolve(ROOT, 'modules', 'wasync');

/**
 * wAsync client E2E tests — verifies the wAsync Java client library.
 * Delegates to the Java integration test (GrpcWasyncTransportTest) and
 * the wAsync module's own test suite.
 *
 * Skipped if the wAsync module doesn't exist.
 */

const hasWasyncModule = existsSync(resolve(WASYNC_MODULE, 'pom.xml'));

(hasWasyncModule ? test.describe : test.describe.skip)('wAsync Client', () => {

  test.describe.configure({ timeout: 120_000 });

  test('wAsync module compiles successfully', async () => {
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} compile -pl modules/wasync -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 120_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });

  test('wAsync module tests pass', async () => {
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} test -pl modules/wasync -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 120_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });

  test('GrpcWasyncTransportTest passes', async () => {
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} test -pl modules/integration-tests ` +
      `-Dtest=GrpcWasyncTransportTest -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 120_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });
});
