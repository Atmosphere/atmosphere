import { test, expect, type Page } from '@playwright/test';
import { startSample, SAMPLES, type SampleConfig, type SampleServer } from './fixtures/sample-server';

/**
 * The Atmosphere Console reattaches to the answer it was streaming when the page
 * is reloaded mid-answer, over WebTransport.
 *
 * ai-reattach-console.spec.ts drives WebSocket and long-polling only, which is
 * how a WebTransport-only defect shipped: the server wrote each replayed frame
 * with a newline its length prefix counted, the WebTransport client (which
 * splits on newlines before reading the prefix) failed to parse the replay,
 * dropped the session, reconnected with the same run id, was replayed the same
 * frames, and looped, rendering raw JSON and never completing the answer.
 *
 * Fixture: `samples/spring-boot-reattach-harness` with its HTTP/3 sidecar on
 * (`atmosphere.web-transport.enabled`), the Console pointed at
 * `/atmosphere/agent/harness-model` (`SlowModelChat`: twelve numbered tokens,
 * one every 500ms). The Console reads the sidecar's port and self-signed
 * certificate hash from `/api/console/info` and connects WebTransport-first with
 * `serverCertificateHashes`, which the project runs in Chrome (`channel:
 * 'chrome'`), as the `webtransport` project does. The Spring Boot 3 starter has
 * no WebTransport sidecar, so the spec skips on that leg only.
 */

const ENDPOINT = '/atmosphere/agent/harness-model';
const CONSOLE = '/atmosphere/console/';
const TOKENS = Array.from({ length: 12 }, (_, i) => `t${String(i).padStart(2, '0')}`);
const ANSWER = TOKENS.join(' ');
const WEB_TRANSPORT_PORT = Number(process.env.REATTACH_HARNESS_WT_PORT ?? 4460);

const HARNESS: SampleConfig = {
  ...SAMPLES['spring-boot-reattach-harness'],
  port: Number(process.env.REATTACH_HARNESS_PORT ?? SAMPLES['spring-boot-reattach-harness'].port),
  jvmArgs: [
    ...(SAMPLES['spring-boot-reattach-harness'].jvmArgs ?? []),
    `-Datmosphere.ai.path=${ENDPOINT}`,
    '-Datmosphere.web-transport.enabled=true',
    `-Datmosphere.web-transport.port=${WEB_TRANSPORT_PORT}`,
  ],
};

test.skip(process.env.E2E_SPRING_BOOT_VERSION === '3',
  'The Spring Boot 3 starter has no WebTransport sidecar');

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(HARNESS);
  // The sidecar binds after the servlet container answers: wait for it to run,
  // and fail rather than let the Console quietly fall back to WebSocket.
  await expect.poll(async () => {
    const res = await fetch(`${server.baseUrl}/api/console/info`);
    const info = res.ok ? await res.json() : null;
    return typeof info?.webTransport?.port === 'number' && typeof info?.webTransport?.certificateHash === 'string';
  }, { timeout: 30_000, message: 'the harness never reported a running WebTransport sidecar' }).toBe(true);
});

test.afterAll(async () => {
  await server?.stop();
});

interface ModelCall {
  state: 'RUNNING' | 'COMPLETED' | 'CANCELLED';
  tokens: number;
  total: number;
}

async function modelCall(prompt: string): Promise<ModelCall | null> {
  const res = await fetch(`${server.baseUrl}/harness/model-call?prompt=${encodeURIComponent(prompt)}`);
  if (res.status === 404) {
    return null;
  }
  expect(res.ok, `GET /harness/model-call answered ${res.status}`).toBe(true);
  return await res.json() as ModelCall;
}

async function assistantTexts(page: Page): Promise<string[]> {
  return page.locator('.message--assistant .message-content').allInnerTexts();
}

async function runIdKeys(page: Page): Promise<number> {
  return page.evaluate(() => Object.keys(sessionStorage)
    .filter((k) => k.startsWith('atmosphere.js:run-id:')).length);
}

/** Open (or reload) the Console and wait until it is connected over WebTransport. */
async function connectedOverWebTransport(page: Page): Promise<void> {
  const status = page.getByTestId('atmosphere-connection-status');
  await expect(status).toHaveAttribute('data-phase', 'open', { timeout: 30_000 });
  await expect(status, 'the Console must be on WebTransport, not a fallback')
    .toHaveAttribute('data-transport', 'webtransport');
  await expect(status).toHaveAttribute('data-via-fallback', 'false');
}

test.describe('Console reattaches to the in-flight answer across a reload over WebTransport', () => {
  test('@smoke a reload mid-answer renders the whole answer once, through complete', async ({ page }) => {
    const consoleLines: string[] = [];
    page.on('console', (msg) => consoleLines.push(`${msg.type()}: ${msg.text()}`));

    await page.goto(server.baseUrl + CONSOLE);
    await connectedOverWebTransport(page);

    const prompt = `console-reload-webtransport-${Date.now()}`;
    await page.getByTestId('chat-input').fill(prompt);
    await page.getByTestId('chat-send').click();

    // Reload mid-answer: the model has streamed some of it and is still running.
    await expect.poll(async () => (await modelCall(prompt))?.tokens ?? 0,
      { timeout: 15_000, message: 'the model never streamed 3 tokens' }).toBeGreaterThanOrEqual(3);
    await expect.poll(async () => (await assistantTexts(page)).join(''),
      { timeout: 10_000, message: 'the first load rendered none of the answer' }).toContain('t02');
    await expect.poll(() => runIdKeys(page),
      { timeout: 10_000, message: 'atmosphere.js never stored the run id' }).toBe(1);
    expect((await modelCall(prompt))?.state, 'the reload must come before the answer ended').toBe('RUNNING');

    await page.reload();
    await connectedOverWebTransport(page);

    // The reattached run streams as a turn in flight: Send is off meanwhile.
    const indicator = page.locator('.streaming-indicator');
    const sendButton = page.getByTestId('chat-send');
    await page.getByTestId('chat-input').fill('a question typed while the answer streams');
    await expect(indicator, 'the reattached run must show as streaming').toBeVisible({ timeout: 15_000 });
    await expect(sendButton, 'Send must be off while the reattached answer streams').toBeDisabled();

    // One assistant bubble holding the whole answer, nothing repeated.
    await expect.poll(async () => (await assistantTexts(page)).join('\n'),
      { timeout: 20_000, message: 'the reloaded Console never rendered the whole answer' }).toContain('t11');
    await expect(indicator, 'the Console must be idle after complete').toBeHidden({ timeout: 5_000 });
    await expect(sendButton, 'Send must be back on after complete').toBeEnabled();
    const bubbles = await assistantTexts(page);
    expect(bubbles.length, `one assistant bubble: ${JSON.stringify(bubbles)}`).toBe(1);
    expect(bubbles[0].replace(/\s+/g, ' ').trim()).toBe(ANSWER);
    await expect(page.locator('body')).not.toContainText('Run cancelled');
    await expect(page.locator('body')).not.toContainText('"type"');

    const call = await modelCall(prompt);
    expect(call?.state).toBe('COMPLETED');
    expect(call?.tokens).toBe(TOKENS.length);

    // The complete frame cleared the stored run id.
    await expect.poll(() => runIdKeys(page),
      { timeout: 5_000, message: 'the complete frame must clear the stored run id' }).toBe(0);

    // One WebTransport session per load: the replay neither broke the session
    // nor sent it reconnecting, and every frame parsed.
    const opened = consoleLines.filter((l) => l.includes('WebTransport connection established'));
    expect(opened.length, `WebTransport sessions opened: ${JSON.stringify(opened)}`).toBe(2);
    const broken = consoleLines.filter((l) => /dropped a frame|is not a number|transport error/.test(l));
    expect(broken, 'no frame may fail to parse').toEqual([]);
    await connectedOverWebTransport(page);
  });
});
