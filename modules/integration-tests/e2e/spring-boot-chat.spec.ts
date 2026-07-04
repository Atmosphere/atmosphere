import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Spring Boot Chat', () => {
  test('page loads with console layout', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
  });

  test('console connects to chat handler', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });
  });

  test('user can send a message', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });

    await page.getByTestId('chat-input').fill('Hello from Playwright!');
    await page.getByTestId('chat-send').click();

    // User message should appear in the message list
    await expect(page.getByTestId('message-list')).toContainText('Hello from Playwright!', { timeout: 10_000 });
  });

  test('input clears after sending', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });

    await page.getByTestId('chat-input').fill('test message');
    await page.getByTestId('chat-send').click();

    // Input should be cleared after sending
    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });

  test('send button is disabled when not connected', async ({ page }) => {
    // Navigate but don't wait for connection — check initial state
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    // Once connected, send button should be usable with non-empty input
    await expect(page.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });
    // With empty input, send should be disabled
    await expect(page.getByTestId('chat-send')).toBeDisabled();
  });

  // Regression (F1): the console used to unconditionally probe optional-module
  // endpoints for feature-detection, which 404'd on a plain chat sample and
  // logged red console errors. The console now gates those tabs on the
  // /api/console/info capability flags (hasInteractions / hasVerifier), so it
  // never fetches an endpoint that isn't there.
  test('does not probe optional endpoints that 404', async ({ page }) => {
    const notFound: string[] = [];
    page.on('response', (r) => {
      const u = new URL(r.url()).pathname;
      if (
        r.status() === 404 &&
        (u === '/api/interactions' || u === '/api/admin/verifier/summary')
      ) {
        notFound.push(u);
      }
    });
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    // Let onMounted's /api/console/info fetch + any gated probes settle.
    await page.waitForTimeout(1500);
    expect(notFound, `unexpected 404 probes: ${notFound.join(', ')}`).toEqual([]);
  });

  // Regression (browser-sweep ISSUE-1): the console used to drop BOTH halves
  // of broadcast mode — outbound, it pushed the raw prompt string, which the
  // chat sample's JacksonDecoder cannot decode (message silently dropped
  // server-side); inbound, event-less {author, message, time} frames from
  // other clients fell through handleStreamingEvent's default case and never
  // rendered. The earlier "user can send a message" test passed on the LOCAL
  // user bubble alone, masking all of it. This test pins the real contract:
  // a message sent from one console client must render on ANOTHER connected
  // console client (and echo back to the sender) through the server round-trip.
  test('broadcast reaches another connected console client', async ({ browser }) => {
    const ctxA = await browser.newContext();
    const ctxB = await browser.newContext();
    try {
      const a = await ctxA.newPage();
      const b = await ctxB.newPage();
      await a.goto(server.baseUrl + '/atmosphere/console/');
      await b.goto(server.baseUrl + '/atmosphere/console/');
      await expect(a.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });
      await expect(b.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });

      await a.getByTestId('chat-input').fill('cross-client broadcast probe');
      await a.getByTestId('chat-send').click();

      // The other client renders the broadcast (author-prefixed bubble)...
      await expect(b.getByTestId('message-list'))
        .toContainText('console: cross-client broadcast probe', { timeout: 10_000 });
      // ...and the sender sees the server echo, proving the round-trip
      // (not just the locally rendered user bubble).
      await expect(a.getByTestId('message-list'))
        .toContainText('console: cross-client broadcast probe', { timeout: 10_000 });
    } finally {
      await ctxA.close();
      await ctxB.close();
    }
  });
});
