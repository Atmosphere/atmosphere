import { test, expect } from '@playwright/test';
import { execSync } from 'child_process';
import { resolve } from 'path';

const ROOT = resolve(__dirname, '..', '..', '..', '..');

/**
 * Kafka clustering E2E tests — verifies cross-node broadcast via Kafka.
 * Delegates to the Java KafkaClusteringTest which uses Testcontainers.
 *
 * Requires Docker — tests are skipped if Docker is unavailable.
 */

function isDockerAvailable(): boolean {
  try {
    execSync('docker info', { stdio: 'ignore', timeout: 5000 });
    return true;
  } catch {
    return false;
  }
}

const DOCKER_OK = isDockerAvailable();

(DOCKER_OK ? test.describe : test.describe.skip)('Kafka Clustering', () => {

  test.describe.configure({ timeout: 180_000 });

  test('cross-node broadcast via Kafka', async () => {
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} test -pl modules/integration-tests ` +
      `-Dtest=KafkaClusteringTest#testCrossNodeBroadcast -Dgroups=kafka -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 180_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });

  test('topic isolation across Kafka nodes', async () => {
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} test -pl modules/integration-tests ` +
      `-Dtest=KafkaClusteringTest#testTopicIsolation -Dgroups=kafka -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 180_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });

  test('echo prevention — no Kafka duplicates', async () => {
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} test -pl modules/integration-tests ` +
      `-Dtest=KafkaClusteringTest#testEchoPrevention -Dgroups=kafka -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 180_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });
});
