import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

/**
 * Verifies that ALL Spring Boot samples serve /atmosphere/console/ correctly.
 * This is a lightweight HTTP-only check (no browser DOM assertions) that
 * confirms the ConsoleResourceFilter is registered in AtmosphereAutoConfiguration
 * (not just AtmosphereAiAutoConfiguration).
 *
 * Covers Gist 2, Fix 1: Console path accessible on all 7 Spring Boot samples.
 */

// All Spring Boot samples that should serve the console.
// Previously A2 (durable-sessions) and A3 (otel-chat) were broken
// because ConsoleResourceFilter was only in AiAutoConfiguration.
const CONSOLE_SAMPLES = [
  'spring-boot-chat',
  'spring-boot-durable-sessions',
  'spring-boot-otel-chat',
  'spring-boot-mcp-server',
  'spring-boot-ai-tools',
  'spring-boot-rag-chat',
  'spring-boot-dentist-agent',
] as const;

for (const sampleName of CONSOLE_SAMPLES) {
  test.describe(`Console HTTP — ${sampleName}`, () => {
    let server: SampleServer;

    test.beforeAll(async () => {
      test.setTimeout(120_000);
      server = await startSample(SAMPLES[sampleName]);
    });

    test.afterAll(async () => {
      await server?.stop();
    });

    test(`/atmosphere/console/ returns 200`, async () => {
      const res = await fetch(`${server.baseUrl}/atmosphere/console/`);
      expect(res.status).toBe(200);
    });

    test(`console response is HTML`, async () => {
      const res = await fetch(`${server.baseUrl}/atmosphere/console/`);
      const contentType = res.headers.get('content-type') ?? '';
      expect(contentType).toContain('text/html');
    });
  });
}
