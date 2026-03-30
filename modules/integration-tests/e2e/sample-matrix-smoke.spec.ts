import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

/**
 * Lightweight startup + HTTP response smoke test for ALL samples.
 *
 * Covers Gist 3: Full sample matrix. Verifies every sample in the SAMPLES map
 * boots and responds to HTTP (status < 500). Does NOT duplicate full functional
 * tests from individual spec files — just ensures clean startup/shutdown.
 *
 * grpc-chat is NOT in the SAMPLES map (uses dual-transport-server.ts),
 * so it is excluded from this sweep.
 */

const ALL_SAMPLE_NAMES = Object.keys(SAMPLES);

for (const sampleName of ALL_SAMPLE_NAMES) {
  test.describe(`Matrix Smoke — ${sampleName}`, () => {
    let server: SampleServer;

    test.beforeAll(async () => {
      test.setTimeout(120_000);
      server = await startSample(SAMPLES[sampleName]);
    });

    test.afterAll(async () => {
      await server?.stop();
    });

    test(`starts and responds to HTTP`, async () => {
      const res = await fetch(server.baseUrl);
      // Accept any non-5xx status (some samples redirect or return 404 for /)
      expect(res.status).toBeLessThan(500);
    });
  });
}
