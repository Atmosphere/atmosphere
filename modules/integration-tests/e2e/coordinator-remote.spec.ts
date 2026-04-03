import { test, expect } from '@playwright/test';
import { execSync } from 'child_process';
import { resolve } from 'path';

const ROOT = resolve(__dirname, '..', '..', '..', '..');

/**
 * Coordinator with remote agents E2E tests — verifies the coordinator
 * pattern with headless agents communicated via A2A HTTP transport.
 *
 * Delegates to the Java CoordinatorWebSocketIntegrationTest which uses
 * an embedded Jetty server with @Coordinator and headless worker agents.
 */

test.describe('Coordinator Remote Agents', () => {

  test.describe.configure({ timeout: 120_000 });

  test('coordinator delegates to fleet and synthesizes results', async () => {
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} test -pl modules/integration-tests ` +
      `-Dtest=CoordinatorWebSocketIntegrationTest#testCoordinatorDelegatesToFleet ` +
      `-Dgroups=coordinator -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 120_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });

  test('headless agent responds to direct A2A calls', async () => {
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} test -pl modules/integration-tests ` +
      `-Dtest=CoordinatorWebSocketIntegrationTest#testHeadlessAgentRespondsToDirect_A2A ` +
      `-Dgroups=coordinator -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 120_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });

  test('coordinator synthesizes sequential and parallel worker results', async () => {
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} test -pl modules/integration-tests ` +
      `-Dtest=CoordinatorWebSocketIntegrationTest#testCoordinatorSynthesizesSequentialAndParallel ` +
      `-Dgroups=coordinator -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 120_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });

  test('multiple clients receive independent coordinator responses', async () => {
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} test -pl modules/integration-tests ` +
      `-Dtest=CoordinatorWebSocketIntegrationTest#testMultipleClientsReceiveIndependentResponses ` +
      `-Dgroups=coordinator -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 120_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });
});
