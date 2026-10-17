import { rmSync } from 'fs';
import { test, expect, type Page } from '@playwright/test';
import { startSample, SAMPLES, type SampleConfig, type SampleServer } from './fixtures/sample-server';
import {
  type AiFrame, type PageLoad, beacons, closeSubscription, instrumentBeacons, openReattachPage,
  pageLoad, reattachPageJvmArgs, sendPrompt, texts, waitForOpen,
} from './fixtures/ai-reattach-page';

/**
 * The AI reattach grace, in a browser, over WebSocket, SSE and long-polling.
 *
 * A page that reloads while an answer streams loses its connection, and comes back
 * with the run id. The server must have kept the model call going meanwhile (the
 * reattach grace, `RunDisconnectGrace`), so the reloaded page receives the whole
 * answer through `complete`, not the text streamed until the reload and then a
 * `cancelled` frame. How the server tells a reload from a client that left differs
 * per transport:
 *
 * - WebSocket: the browser closes the socket with 1001 (going away).
 * - SSE and long-polling: atmosphere.js sends its close message as a beacon from
 *   `pagehide`, marked `X-Atmosphere-Close-Reason=going-away`, which
 *   `OnDisconnectInterceptor` records on the event before it reports the client's
 *   close; without the mark the close message is a leave and the run is cancelled at
 *   once. The reload cases fail if atmosphere.js drops the mark from its beacon, and
 *   the "without the going-away mark" cases pin what an unmarked beacon gets.
 *
 * A client that closes its subscription on purpose (`subscription.close()`) has
 * left: its model call is cancelled at once, and a reattach gets the `cancelled`
 * frame.
 *
 * Fixture: `samples/spring-boot-reattach-harness`, endpoint
 * `/atmosphere/agent/harness-model` (`SlowModelChat`), whose `@Prompt` answers with
 * `session.stream(...)` from `PacedModelClient`: twelve numbered tokens, one every
 * 500ms, through the built-in runtime's cancellable path. `GET /harness/model-call`
 * reports whether the model call for a prompt completed or was cancelled, so "the
 * server did not cancel the model call" is asserted on the server, not inferred
 * from the page.
 *
 * Over long-polling a reply reaches the client whole when it ends (`LongPollingReplies`),
 * except its run id, which the session hands over at once so that a client reloading
 * mid-reply knows the run to come back to. Mid-reply, the long-polling page therefore
 * holds the run id and no text yet, and the reload is timed on the server's progress.
 *
 * The page (fixtures/ai-reattach-page.ts) is the real atmosphere.js browser bundle;
 * it keeps the run id in sessionStorage across the reload and subscribes again with
 * it as `request.runId`, as an application that resumes its conversation does. The
 * "keeps the run id across the reload by itself" cases open it with `keep=library`:
 * the page keeps nothing and the reloaded subscription reattaches with the run id
 * atmosphere.js stored for the tab, over the streaming transport too. The
 * sample serves the page itself: with Playwright request interception (`page.route`)
 * on, Chromium aborts the `pagehide` beacon, so do not serve it that way.
 */

const ENDPOINT = '/atmosphere/agent/harness-model';
const TOKENS = Array.from({ length: 12 }, (_, i) => `t${String(i).padStart(2, '0')} `);
const TRANSPORTS = ['websocket', 'sse', 'long-polling'] as const;
type Transport = typeof TRANSPORTS[number] | 'streaming';

// The sample's port, unless REATTACH_HARNESS_PORT names another: a host whose 8096
// is taken can still run the spec.
const HARNESS: SampleConfig = {
  ...SAMPLES['spring-boot-reattach-harness'],
  port: Number(process.env.REATTACH_HARNESS_PORT ?? SAMPLES['spring-boot-reattach-harness'].port),
};

let server: SampleServer;
let pageRoot: string | undefined;

test.beforeAll(async () => {
  // The sample serves the test page itself (see fixtures/ai-reattach-page.ts).
  const served = reattachPageJvmArgs();
  pageRoot = served.root;
  server = await startSample({ ...HARNESS, jvmArgs: [...(HARNESS.jvmArgs ?? []), ...served.jvmArgs] });
});

test.afterAll(async () => {
  await server?.stop();
  if (pageRoot) {
    rmSync(pageRoot, { recursive: true, force: true });
  }
});

interface ModelCall {
  state: 'RUNNING' | 'COMPLETED' | 'CANCELLED';
  tokens: number;
  total: number;
}

/** How the harness's model call for `prompt` stands, on the server. */
async function modelCall(prompt: string): Promise<ModelCall | null> {
  const res = await fetch(`${server.baseUrl}/harness/model-call?prompt=${encodeURIComponent(prompt)}`);
  if (res.status === 404) {
    return null;
  }
  expect(res.ok, `GET /harness/model-call answered ${res.status}`).toBe(true);
  return await res.json() as ModelCall;
}

const TERMINAL = ['complete', 'cancelled', 'error'];

function count(frames: AiFrame[], type: string): number {
  return frames.filter((f) => f.type === type).length;
}

function runIdOf(frames: AiFrame[]): string | undefined {
  return frames.find((f) => f.type === 'metadata' && f.key === 'X-Atmosphere-Run-Id')?.value;
}

/** The current load once it has received a terminal frame. */
async function terminalLoad(page: Page, timeoutMs = 20_000): Promise<PageLoad> {
  await expect.poll(async () => (await pageLoad(page)).frames.some((f) => TERMINAL.includes(f.type)),
    { timeout: timeoutMs, message: 'the reloaded page never received a terminal frame' }).toBe(true);
  return pageLoad(page);
}

/** Wait until the current load has received at least `n` tokens; returns it. */
async function afterTokens(page: Page, n: number): Promise<PageLoad> {
  await expect.poll(async () => texts((await pageLoad(page)).frames).length,
    { timeout: 15_000, message: `fewer than ${n} tokens arrived` }).toBeGreaterThanOrEqual(n);
  return pageLoad(page);
}

/**
 * Record the transports the page used to reach the endpoint: WebSocket upgrades, and
 * the `X-Atmosphere-Transport` of every HTTP request, so a test asserts it ran on the
 * transport it names rather than on a fallback.
 */
function trackTransports(page: Page): { websockets: string[]; http: Set<string> } {
  const seen = { websockets: [] as string[], http: new Set<string>() };
  page.on('websocket', (ws) => {
    if (ws.url().includes(ENDPOINT)) {
      seen.websockets.push(ws.url());
    }
  });
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (url.pathname.startsWith(ENDPOINT)) {
      const t = url.searchParams.get('X-Atmosphere-Transport') ?? request.headers()['x-atmosphere-transport'];
      if (t) {
        seen.http.add(t);
      }
    }
  });
  return seen;
}

function expectTransport(seen: { websockets: string[]; http: Set<string> }, transport: Transport): void {
  if (transport === 'websocket') {
    expect(seen.websockets.length, 'the page must have opened a WebSocket to the endpoint')
      .toBeGreaterThanOrEqual(2);
    expect([...seen.http].filter((t) => t !== 'websocket'),
      'a WebSocket run must not fall back to an HTTP transport').toEqual([]);
  } else {
    expect(seen.websockets, `a ${transport} run must not open a WebSocket`).toEqual([]);
    expect(seen.http.has(transport), `the page must have reached the endpoint over ${transport}`).toBe(true);
  }
}

/**
 * Open the page, send a fresh prompt and wait until the model streamed `tokens`
 * tokens and the page holds the run id; returns the prompt, the run id and what the
 * page saw. Over WebSocket and SSE the page has received those tokens too. Over
 * long-polling a reply reaches the client whole when it ends (`LongPollingReplies`),
 * except its run id, handed over at once: mid-reply the page has the run id and no
 * text yet.
 */
async function startRun(page: Page, transport: Transport, label: string, tokens: number,
                        keep: 'page' | 'library' = 'page')
    : Promise<{ prompt: string; runId: string; before: PageLoad }> {
  await openReattachPage(page, server.baseUrl, transport, ENDPOINT, keep);
  await waitForOpen(page);
  const prompt = `${label}-${transport}-${Date.now()}`;
  await sendPrompt(page, prompt);
  await expect.poll(async () => (await modelCall(prompt))?.tokens ?? 0,
    { timeout: 15_000, message: `the model never streamed ${tokens} tokens` }).toBeGreaterThanOrEqual(tokens);
  if (transport !== 'long-polling') {
    await afterTokens(page, tokens);
  }
  await expect.poll(async () => runIdOf((await pageLoad(page)).frames) ?? '',
    { timeout: 10_000, message: 'the page never received the run id' }).not.toBe('');
  const before = await pageLoad(page);
  return { prompt, runId: runIdOf(before.frames)!, before };
}

test.describe('AI reattach grace across a page reload', () => {
  for (const transport of TRANSPORTS) {
    test(`@smoke ${transport}: a reload mid-stream reattaches with the run id and receives the rest through complete`,
        async ({ page }) => {
      const seen = trackTransports(page);
      await instrumentBeacons(page);
      const { prompt, runId, before } = await startRun(page, transport, 'reload', 3);

      // The reload happens mid-stream: the model call is still running, and the
      // first load holds a prefix of the answer (none of it over long-polling, which
      // hands a reply over when it ends) and no terminal frame.
      const streamedBefore = texts(before.frames);
      expect(streamedBefore).toEqual(TOKENS.slice(0, streamedBefore.length));
      expect(streamedBefore.length).toBeGreaterThanOrEqual(transport === 'long-polling' ? 0 : 3);
      expect(before.frames.filter((f) => TERMINAL.includes(f.type))).toEqual([]);
      expect((await modelCall(prompt))?.state, 'the reload must come before the answer ended').toBe('RUNNING');

      await page.reload();
      const after = await terminalLoad(page);

      expect(after.load, 'the page must have reloaded').toBe(2);
      expect(after.reattachRunId, 'the reloaded page must subscribe with the run id').toBe(runId);
      expect(after.error).toBeNull();
      // The whole answer, once, in order: the replay of what the first load saw,
      // then the rest as the model streams it, with nothing repeated.
      expect(texts(after.frames)).toEqual(TOKENS);
      expect(count(after.frames, 'complete'), 'exactly one complete').toBe(1);
      expect(count(after.frames, 'cancelled'), 'the run must not be cancelled').toBe(0);
      expect(count(after.frames, 'error')).toBe(0);
      expect(after.frames.at(-1)?.type).toBe('complete');

      // The server ran the model call to its end.
      const call = await modelCall(prompt);
      expect(call, 'the harness must have recorded the model call').not.toBeNull();
      expect(call!.state).toBe('COMPLETED');
      expect(call!.tokens).toBe(TOKENS.length);

      // How the page told the server it was going away.
      const sent = await beacons(page);
      if (transport === 'websocket') {
        // Closing the socket (1001) tells the server; no close message is sent.
        expect(sent).toEqual([]);
      } else {
        expect(sent.filter((u) => u.includes('X-Atmosphere-Transport=close')
            && u.includes('X-Atmosphere-Close-Reason=going-away')).length,
          `the ${transport} page must send its close message marked going-away: ${JSON.stringify(sent)}`)
          .toBe(1);
      }
      expectTransport(seen, transport);
    });

    test(`${transport}: subscription.close() cancels the model call at once and a reattach gets cancelled`,
        async ({ page }) => {
      const seen = trackTransports(page);
      await instrumentBeacons(page);
      const { prompt, runId } = await startRun(page, transport, 'close', 2);

      const closedAt = Date.now();
      await closeSubscription(page);

      // At once: well inside the 30s grace a lost connection would get, and before
      // the answer would have ended on its own (12 tokens, 6s).
      await expect.poll(async () => (await modelCall(prompt))?.state,
        { timeout: 4_000, message: 'close() must cancel the model call at once' }).toBe('CANCELLED');
      const cancelledAfter = Date.now() - closedAt;
      const call = await modelCall(prompt);
      expect(call!.tokens, `cancelled ${cancelledAfter}ms after close()`).toBeLessThan(TOKENS.length);

      // A client that comes back with the run id learns the run was cancelled.
      await page.reload();
      const after = await terminalLoad(page);
      expect(after.reattachRunId).toBe(runId);
      expect(count(after.frames, 'cancelled'), 'the reattach must end with the cancelled frame').toBe(1);
      expect(count(after.frames, 'complete')).toBe(0);
      expect(after.frames.at(-1)?.type).toBe('cancelled');
      expect(texts(after.frames)).toEqual(TOKENS.slice(0, call!.tokens));
      expectTransport(seen, transport);
    });
  }

  // The same reload, with a page that keeps nothing: atmosphere.js stores the run
  // id for the tab and the reloaded page's subscription reattaches with it.
  for (const transport of [...TRANSPORTS, 'streaming'] as const) {
    test(`${transport}: atmosphere.js keeps the run id across the reload by itself`, async ({ page }) => {
      const seen = trackTransports(page);
      const { prompt, runId } = await startRun(page, transport, 'library', 3, 'library');
      expect((await modelCall(prompt))?.state, 'the reload must come before the answer ended').toBe('RUNNING');

      await page.reload();
      const after = await terminalLoad(page);

      expect(after.load).toBe(2);
      expect(after.reattachRunId, 'atmosphere.js must have stored the run id for the tab').toBe(runId);
      expect(texts(after.frames)).toEqual(TOKENS);
      expect(count(after.frames, 'complete')).toBe(1);
      expect(count(after.frames, 'cancelled')).toBe(0);
      expect((await modelCall(prompt))?.state).toBe('COMPLETED');
      // The complete frame ended the run: the tab holds no run id any more.
      expect(await page.evaluate((key) => sessionStorage.getItem(key),
        `atmosphere.js:run-id:${server.baseUrl}${ENDPOINT}`)).toBeNull();
      expectTransport(seen, transport);
    });
  }

  test('long-polling: an uninterrupted reply arrives once, its run id first', async ({ page }) => {
    // The run id reaches a long-polling page before the reply ends, and atmosphere.js
    // then sends it on every poll until the terminal frame. A poll of the client the
    // reply is for is not a reattach: the reply must still arrive exactly once.
    await openReattachPage(page, server.baseUrl, 'long-polling', ENDPOINT);
    await waitForOpen(page);
    const prompt = `whole-long-polling-${Date.now()}`;
    await sendPrompt(page, prompt);
    const load = await terminalLoad(page);

    expect(texts(load.frames)).toEqual(TOKENS);
    expect(count(load.frames, 'complete')).toBe(1);
    expect(count(load.frames, 'cancelled')).toBe(0);
    expect(count(load.frames, 'error')).toBe(0);
    expect(load.frames.filter((f) => f.type === 'metadata' && f.key === 'X-Atmosphere-Run-Id').length).toBe(1);
    const runIdAt = load.frames.findIndex((f) => f.type === 'metadata' && f.key === 'X-Atmosphere-Run-Id');
    const firstTextAt = load.frames.findIndex((f) => f.type === 'streaming-text');
    expect(runIdAt, 'the run id must come before the text').toBeLessThan(firstTextAt);
    expect((await modelCall(prompt))?.state).toBe('COMPLETED');
  });

  for (const transport of ['sse', 'long-polling'] as const) {
    test(`${transport}: without the going-away mark on the close beacon, a reload cancels the run`,
        async ({ page }) => {
      // What the mark buys: the same reload, with the mark stripped from the beacon
      // (a client that predates it), is a leave, and the run is cancelled at once.
      await instrumentBeacons(page, { stripGoingAway: true });
      const { prompt, runId } = await startRun(page, transport, 'unmarked', 3);

      await page.reload();
      const after = await terminalLoad(page);

      const sent = await beacons(page);
      expect(sent.filter((u) => u.includes('X-Atmosphere-Transport=close')).length,
        `the page must send its close message: ${JSON.stringify(sent)}`).toBe(1);
      expect(sent.some((u) => u.includes('X-Atmosphere-Close-Reason'))).toBe(false);

      expect(after.reattachRunId).toBe(runId);
      expect(count(after.frames, 'cancelled'), 'an unmarked close message is a leave').toBe(1);
      expect(count(after.frames, 'complete')).toBe(0);
      const call = await modelCall(prompt);
      expect(call!.state).toBe('CANCELLED');
      expect(call!.tokens).toBeLessThan(TOKENS.length);
    });
  }
});
