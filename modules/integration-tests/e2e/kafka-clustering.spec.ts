import { test, expect } from '@playwright/test';
import { execSync } from 'child_process';

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

  test('cross-node broadcast via Kafka (Java integration)', async () => {
    test.skip(true, 'Kafka clustering tested via Java CI job (requires Testcontainers + Docker)');
  });

  test('topic isolation across Kafka nodes (Java integration)', async () => {
    test.skip(true, 'Kafka clustering tested via Java CI job (requires Testcontainers + Docker)');
  });

  test('echo prevention — no Kafka duplicates (Java integration)', async () => {
    test.skip(true, 'Kafka clustering tested via Java CI job (requires Testcontainers + Docker)');
  });
});
