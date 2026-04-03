import { test, expect } from '@playwright/test';
import { execSync } from 'child_process';
import { existsSync } from 'fs';
import { resolve } from 'path';

const ROOT = resolve(__dirname, '..', '..', '..', '..');
const COORDINATOR_MODULE = resolve(ROOT, 'modules', 'coordinator');

/**
 * Coordinator journal E2E tests — verifies journal persistence, replay,
 * and query API for coordinator workflows.
 *
 * Delegates to the Java CoordinatorWebSocketIntegrationTest and verifies
 * the coordinator module builds and its tests pass.
 */

const hasCoordinatorModule = existsSync(resolve(COORDINATOR_MODULE, 'pom.xml'));

(hasCoordinatorModule ? test.describe : test.describe.skip)('Coordinator Journal', () => {

  test.describe.configure({ timeout: 120_000 });

  test('coordinator module compiles', async () => {
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} compile -pl modules/coordinator -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 120_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });

  test('coordinator module tests pass', async () => {
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} test -pl modules/coordinator -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 120_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });

  test('coordinator integration test — fleet delegation', async () => {
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} test -pl modules/integration-tests ` +
      `-Dtest=CoordinatorWebSocketIntegrationTest -Dgroups=coordinator -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 120_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });
});
