import { copyFileSync, existsSync, mkdirSync, mkdtempSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import { join, resolve } from 'path';
import type { Page } from '@playwright/test';
import { ROOT } from './packaged-build';

/**
 * A browser page that talks to an AI endpoint through the real atmosphere.js and
 * survives a reload the way an application keeps a conversation going: it stashes
 * the run id the server surfaces (the `X-Atmosphere-Run-Id` metadata frame) in
 * sessionStorage and, once reloaded, subscribes again with it as `request.runId`.
 * atmosphere.js keeps the run id in memory only, so a page that wants its run back
 * after a reload has to keep it itself; this page does no more than that.
 *
 * The sample serves the page and the atmosphere.js browser bundle itself, from a
 * directory added to its static locations ({@link reattachPageJvmArgs}), so the page's
 * requests to the endpoint and its close beacon are same-origin, exactly as from a
 * page the application served. Not through `page.route`: with request interception
 * on, Chromium aborts a beacon sent from `pagehide` as the page unloads
 * (net::ERR_ABORTED), so the close message reaches the server only when it wins the
 * race against the navigation, which a browser without interception does not do.
 *
 * `navigator.sendBeacon` is wrapped to record each beacon URL (sessionStorage
 * `ai-reattach:beacons`), so a spec can see the close message atmosphere.js sends
 * from `pagehide`; with `stripGoingAway` the wrapper also removes the
 * `X-Atmosphere-Close-Reason=going-away` mark before the beacon leaves, which is what
 * a client that predates the mark sends.
 */

/** Path of the test page on the sample's origin. */
export const REATTACH_PAGE_PATH = '/e2e-reattach/index.html';
const BUNDLE_PATH = '/e2e-reattach/atmosphere.global.js';

/** One AI frame as the page received it. */
export interface AiFrame {
  type: string;
  data?: string;
  key?: string;
  value?: string;
  seq?: number;
}

/** What one load of the page saw. */
export interface PageLoad {
  /** 1 for the first load, 2 after one reload, ... */
  load: number;
  /** The run id this load subscribed with, or null. */
  reattachRunId: string | null;
  /** The transport atmosphere.js was asked for. */
  transport: string;
  opened: boolean;
  error: string | null;
  frames: AiFrame[];
}

/**
 * The atmosphere.js browser bundle (tsup's IIFE, `dist/atmosphere.global.js`). It
 * is a build output: the spring-boot-starter Maven build runs `npm run build` in
 * atmosphere.js before it bundles the Console, and so does `npm run build` there.
 */
export function atmosphereBundle(): string {
  const bundle = resolve(ROOT, 'atmosphere.js', 'dist', 'atmosphere.global.js');
  if (!existsSync(bundle)) {
    throw new Error(`${bundle} is missing. Build atmosphere.js: `
      + '`./mvnw install -pl modules/spring-boot-starter -DskipTests` (it runs `npm run build` there), '
      + 'or `cd atmosphere.js && npm ci && npm run build`.');
  }
  return bundle;
}

const PAGE = `<!doctype html>
<html>
<head><meta charset="utf-8"><title>AI reattach</title></head>
<body>
<script src="${BUNDLE_PATH}"></script>
<script>
(function () {
  var params = new URLSearchParams(location.search);
  var transport = params.get('transport');
  var endpoint = params.get('endpoint');
  var key = 'ai-reattach:' + transport;
  var state = JSON.parse(sessionStorage.getItem(key) || '{"runId":null,"loads":0}');
  state.loads += 1;
  sessionStorage.setItem(key, JSON.stringify(state));
  var load = {
    load: state.loads, reattachRunId: state.runId, transport: transport,
    opened: false, error: null, frames: []
  };
  window.__reattach = { load: load, subscription: null };
  window.atmosphere.subscribe({
    url: location.origin + endpoint,
    transport: transport,
    fallbackTransport: transport,
    enableProtocol: true,
    // As the Console does: a long-polling reply can carry several frames, which
    // the server writes back to back, and the length prefix is what splits them.
    trackMessageLength: true,
    runId: state.runId
  }, {
    open: function () { load.opened = true; },
    message: function (response) {
      var body = typeof response.responseBody === 'string' ? response.responseBody : '';
      body.split('\\n').forEach(function (line) {
        line = line.trim();
        if (line.charAt(0) !== '{') return;
        var frame;
        try { frame = JSON.parse(line); } catch (e) { return; }
        if (!frame || typeof frame.type !== 'string') return;
        load.frames.push(frame);
        if (frame.type === 'metadata' && frame.key === 'X-Atmosphere-Run-Id') {
          state.runId = frame.value;
          sessionStorage.setItem(key, JSON.stringify(state));
        }
      });
    },
    error: function (e) { load.error = String(e && e.message ? e.message : e); }
  }).then(function (subscription) {
    window.__reattach.subscription = subscription;
  }, function (e) {
    load.error = String(e && e.message ? e.message : e);
  });
})();
</script>
</body>
</html>`;

/**
 * JVM arguments that make a Spring Boot sample serve the test page and the
 * atmosphere.js bundle under `/e2e-reattach/`, from a fresh temporary directory
 * (`root`, which the caller removes once the sample stopped), on top of its usual
 * static locations (Spring Boot's defaults).
 */
export function reattachPageJvmArgs(): { root: string; jvmArgs: string[] } {
  const root = mkdtempSync(join(tmpdir(), 'atmosphere-e2e-reattach-'));
  const dir = join(root, 'e2e-reattach');
  mkdirSync(dir);
  writeFileSync(join(dir, 'index.html'), PAGE);
  copyFileSync(atmosphereBundle(), join(dir, 'atmosphere.global.js'));
  const locations = [`file:${root}/`, 'classpath:/META-INF/resources/', 'classpath:/resources/',
    'classpath:/static/', 'classpath:/public/'];
  return { root, jvmArgs: [`-Dspring.web.resources.static-locations=${locations.join(',')}`] };
}

/**
 * Wrap `navigator.sendBeacon` in every document the page loads, recording each
 * beacon URL and, with `stripGoingAway`, removing the going-away mark first.
 */
export async function instrumentBeacons(page: Page, opts: { stripGoingAway?: boolean } = {}): Promise<void> {
  await page.addInitScript((strip: boolean) => {
    const original = navigator.sendBeacon.bind(navigator);
    navigator.sendBeacon = (url: string | URL, data?: BodyInit | null) => {
      let target = String(url);
      if (strip) {
        target = target.replace('&X-Atmosphere-Close-Reason=going-away', '');
      }
      const sent = JSON.parse(sessionStorage.getItem('ai-reattach:beacons') || '[]') as string[];
      sent.push(target);
      sessionStorage.setItem('ai-reattach:beacons', JSON.stringify(sent));
      return original(target, data);
    };
  }, opts.stripGoingAway === true);
}

/** Open the page for `transport` against the AI endpoint at `endpoint`. */
export async function openReattachPage(page: Page, baseUrl: string, transport: string,
                                       endpoint: string): Promise<void> {
  const query = new URLSearchParams({ transport, endpoint });
  await page.goto(`${baseUrl}${REATTACH_PAGE_PATH}?${query}`);
}

/** What the current load of the page has seen so far. */
export function pageLoad(page: Page): Promise<PageLoad> {
  return page.evaluate(() => JSON.parse(JSON.stringify(
    (window as unknown as { __reattach: { load: PageLoad } }).__reattach.load)) as PageLoad);
}

/** The beacon URLs the page sent, across loads. */
export function beacons(page: Page): Promise<string[]> {
  return page.evaluate(() =>
    JSON.parse(sessionStorage.getItem('ai-reattach:beacons') || '[]') as string[]);
}

/** Wait until the current load's subscription is open (its tracking id is known). */
export async function waitForOpen(page: Page, timeoutMs = 15_000): Promise<void> {
  await page.waitForFunction(() => {
    const r = (window as unknown as {
      __reattach?: { load: PageLoad; subscription: { uuid: string } | null };
    }).__reattach;
    return !!r && r.load.opened && r.subscription !== null && r.subscription.uuid !== '0';
  }, undefined, { timeout: timeoutMs });
}

/** Send `prompt` on the current load's subscription. */
export async function sendPrompt(page: Page, prompt: string): Promise<void> {
  await page.evaluate((p) => {
    (window as unknown as { __reattach: { subscription: { push(m: string): void } } })
      .__reattach.subscription.push(p);
  }, prompt);
}

/** Close the current load's subscription: a client that leaves on purpose. */
export async function closeSubscription(page: Page): Promise<void> {
  await page.evaluate(() => {
    (window as unknown as { __reattach: { subscription: { close(): Promise<void> | void } } })
      .__reattach.subscription.close();
  });
}

/** The streaming-text of `frames`, in order. */
export function texts(frames: AiFrame[]): string[] {
  return frames.filter((f) => f.type === 'streaming-text').map((f) => f.data ?? '');
}
