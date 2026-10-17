import { test, expect, type Page } from '@playwright/test';
import { startSample, SAMPLES, type SampleConfig, type SampleServer } from './fixtures/sample-server';

/**
 * The Atmosphere Console reattaches to the answer it was streaming when the page
 * is reloaded mid-answer.
 *
 * atmosphere.js keeps the run id the server surfaces (the `X-Atmosphere-Run-Id`
 * metadata frame) in the tab's sessionStorage, keyed by endpoint, until the run's
 * terminal frame or a deliberate `close()`; the reloaded Console subscribes again
 * with it, and the server, which kept the model call going for the reattach grace,
 * replays the answer so far and streams the rest through `complete`. The page does
 * nothing of its own for this: it is the Console as it ships.
 *
 * The Console speaks WebSocket and falls back to long-polling. Both are driven
 * here: the long-polling case removes WebSocket from the page (an init script), so
 * the Console's own fallback picks long-polling, as on a network that refuses the
 * upgrade.
 *
 * Fixture: `samples/spring-boot-reattach-harness`, with the Console pointed at
 * `/atmosphere/agent/harness-model` (`SlowModelChat`): twelve numbered tokens, one
 * every 500ms, from a model call a disconnect cancels. `GET /harness/model-call`
 * reports on the server whether that call completed or was cancelled.
 */

const ENDPOINT = '/atmosphere/agent/harness-model';
const CONSOLE = '/atmosphere/console/';
const TOKENS = Array.from({ length: 12 }, (_, i) => `t${String(i).padStart(2, '0')}`);
const ANSWER = TOKENS.join(' ');

// The sample's port, unless REATTACH_HARNESS_PORT names another (as in
// ai-reattach-reload.spec.ts): a host whose 8096 is taken can still run the spec.
const HARNESS: SampleConfig = {
  ...SAMPLES['spring-boot-reattach-harness'],
  port: Number(process.env.REATTACH_HARNESS_PORT ?? SAMPLES['spring-boot-reattach-harness'].port),
  jvmArgs: [...(SAMPLES['spring-boot-reattach-harness'].jvmArgs ?? []), `-Datmosphere.ai.path=${ENDPOINT}`],
};

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(HARNESS);
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

/** Make the page's WebSocket constructor throw, so atmosphere.js falls back to long-polling. */
async function refuseWebSocket(page: Page): Promise<void> {
  await page.addInitScript(() => {
    class RefusedWebSocket {
      static readonly CONNECTING = 0;
      static readonly OPEN = 1;
      static readonly CLOSING = 2;
      static readonly CLOSED = 3;
      constructor() {
        throw new Error('WebSocket refused by the test');
      }
    }
    (window as unknown as { WebSocket: unknown }).WebSocket = RefusedWebSocket;
  });
}

/** The assistant bubbles' text, in order. */
async function assistantTexts(page: Page): Promise<string[]> {
  return page.locator('.message--assistant .message-content').allInnerTexts();
}

async function openConsole(page: Page): Promise<void> {
  await page.goto(server.baseUrl + CONSOLE);
  await expect(page.getByText(/^Connected\b/).first()).toBeVisible({ timeout: 20_000 });
}

const MODES = [
  { name: 'websocket', transport: 'websocket', setup: async (_page: Page) => { /* the Console's default */ } },
  { name: 'long-polling (WebSocket refused)', transport: 'long-polling', setup: refuseWebSocket },
] as const;

test.describe('Console reattaches to the in-flight answer across a reload', () => {
  for (const mode of MODES) {
    test(`@smoke ${mode.name}: a reload mid-answer renders the whole answer once, through complete`,
        async ({ page }) => {
      await mode.setup(page);
      // The transport the page reached the endpoint with, to prove the case ran on it.
      const seen = { websockets: 0, http: new Set<string>() };
      page.on('websocket', (ws) => { if (ws.url().includes(ENDPOINT)) seen.websockets++; });
      page.on('request', (request) => {
        const url = new URL(request.url());
        if (url.pathname.startsWith(ENDPOINT)) {
          const t = url.searchParams.get('X-Atmosphere-Transport');
          if (t) seen.http.add(t);
        }
      });

      await openConsole(page);
      const prompt = `console-reload-${mode.transport}-${Date.now()}`;
      await page.getByTestId('chat-input').fill(prompt);
      await page.getByTestId('chat-send').click();

      // Reload mid-answer: the model has streamed some of it and is still running.
      await expect.poll(async () => (await modelCall(prompt))?.tokens ?? 0,
        { timeout: 15_000, message: 'the model never streamed 3 tokens' }).toBeGreaterThanOrEqual(3);
      if (mode.transport === 'websocket') {
        await expect.poll(async () => (await assistantTexts(page)).join(''),
          { timeout: 10_000, message: 'the first load rendered none of the answer' }).toContain('t02');
      }
      // atmosphere.js holds the run id for the tab.
      await expect.poll(() => page.evaluate(() => Object.keys(sessionStorage)
        .filter((k) => k.startsWith('atmosphere.js:run-id:')).length),
        { timeout: 10_000, message: 'atmosphere.js never stored the run id' }).toBe(1);
      expect((await modelCall(prompt))?.state, 'the reload must come before the answer ended').toBe('RUNNING');

      await page.reload();
      await expect(page.getByText(/^Connected\b/).first()).toBeVisible({ timeout: 20_000 });

      // The rest of the answer arrives in the reloaded Console: one assistant
      // bubble holding the whole answer, nothing repeated, no cancellation.
      await expect.poll(async () => (await assistantTexts(page)).join('\n'),
        { timeout: 20_000, message: 'the reloaded Console never rendered the whole answer' }).toContain('t11');
      const bubbles = await assistantTexts(page);
      expect(bubbles.length, `one assistant bubble: ${JSON.stringify(bubbles)}`).toBe(1);
      expect(bubbles[0].replace(/\s+/g, ' ').trim()).toBe(ANSWER);
      await expect(page.locator('body')).not.toContainText('Run cancelled');

      // The server ran the model call to its end.
      const call = await modelCall(prompt);
      expect(call?.state).toBe('COMPLETED');
      expect(call?.tokens).toBe(TOKENS.length);

      // The terminal frame cleared the stored run id: a further reload reattaches to nothing.
      await expect.poll(() => page.evaluate(() => Object.keys(sessionStorage)
        .filter((k) => k.startsWith('atmosphere.js:run-id:')).length),
        { timeout: 5_000, message: 'the complete frame must clear the stored run id' }).toBe(0);
      await page.reload();
      await expect(page.getByText(/^Connected\b/).first()).toBeVisible({ timeout: 20_000 });
      await page.waitForTimeout(1_500);
      expect(await assistantTexts(page), 'a reload after the answer ended replays nothing').toEqual([]);

      if (mode.transport === 'websocket') {
        expect(seen.websockets, 'the Console must have used WebSocket').toBeGreaterThanOrEqual(2);
      } else {
        expect(seen.websockets, 'the long-polling case must not open a WebSocket').toBe(0);
        expect(seen.http.has('long-polling'), `the Console must have long-polled: ${[...seen.http]}`).toBe(true);
      }
    });
  }
});
