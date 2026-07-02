/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
import { test, expect, Page } from '@playwright/test';

/**
 * E2E wire contract for the kotlin-dsl-chat sample (shaded jar, embedded
 * Jetty 12, handler built with the Kotlin `atmosphere {}` DSL).
 *
 * REQUIRES: samples/kotlin-dsl-chat running on port 8099
 *   ./mvnw -q -pl samples/kotlin-dsl-chat -am package -DskipTests
 *   java -jar samples/kotlin-dsl-chat/target/atmosphere-kotlin-dsl-chat-*.jar
 *
 * Pins the three failure modes the 4.0.60 release-gate sweep caught in the
 * packaged jar — none visible to unit tests, because all three live at the
 * wire/packaging layer:
 *
 *  1. WebSocket upgrades answered 501 "Websocket protocol not supported":
 *     the embedded Jetty context never provisioned the jakarta.websocket
 *     ServerContainer (the jetty-ee10-websocket-jakarta-server dependency
 *     alone wires nothing).
 *  2. Suspended raw-HTTP subscribers (curl -N / streaming fetch, i.e.
 *     transport UNDEFINED) never received broadcasts: the DSL handler wrote
 *     without the per-transport terminal step (flush for streaming, resume
 *     for long-polling).
 *  3. SLF4J fell back to NOP because the parent pom pins logback-core to
 *     test scope and the shaded jar bundled logback-classic without
 *     logback-core. Not directly assertable over HTTP, but it masked 1–2;
 *     the sample pom now restates compile scope.
 */

/** Open a streaming-fetch subscription to /chat inside the page. */
async function subscribe(page: Page, key: string): Promise<void> {
  await page.evaluate((k) => {
    const state = { received: [] as string[], controller: new AbortController() };
    (window as unknown as Record<string, unknown>)[k] = state;
    fetch('/chat', { signal: state.controller.signal }).then(async (res) => {
      const reader = res.body!.getReader();
      const dec = new TextDecoder();
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        const s = dec.decode(value).trim();
        if (s) state.received.push(s);
      }
    }).catch(() => { /* aborted at the end of the test */ });
  }, key);
  // Give the GET time to reach the server and suspend before anyone posts.
  await page.waitForTimeout(1000);
}

async function receivedBy(page: Page, key: string): Promise<string> {
  return page.evaluate(
    (k) => ((window as unknown as Record<string, { received: string[] }>)[k]).received.join('|'),
    key,
  );
}

async function unsubscribe(page: Page, key: string): Promise<void> {
  await page.evaluate(
    (k) => ((window as unknown as Record<string, { controller: AbortController }>)[k]).controller.abort(),
    key,
  );
}

test.describe('kotlin-dsl-chat: DSL endpoint wire contract', () => {
  test.beforeEach(async ({ page, baseURL }) => {
    // The sample serves no static root; any response sets the page origin
    // so in-page fetch()/WebSocket hit the sample same-origin.
    await page.goto(`${baseURL}/`, { waitUntil: 'commit' });
  });

  test('WebSocket upgrade succeeds and round-trips through the DSL agent', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const ws = new WebSocket(`ws://${location.host}/chat`);
      const msgs: string[] = [];
      ws.onmessage = (e) => { const s = String(e.data).trim(); if (s) msgs.push(s); };
      const opened = await new Promise<boolean>((r) => {
        ws.onopen = () => r(true);
        ws.onerror = () => r(false);
        setTimeout(() => r(false), 8000);
      });
      if (opened) {
        ws.send('ping');
        await new Promise((r) => setTimeout(r, 1500));
        ws.send('release-gate');
        await new Promise((r) => setTimeout(r, 1500));
        ws.close();
      }
      return { opened, msgs };
    });

    // Regression 1: with no jakarta.websocket ServerContainer the upgrade
    // is refused with 501 and `opened` stays false.
    expect(result.opened, 'WebSocket upgrade must be accepted, not 501').toBe(true);
    expect(result.msgs, 'DeterministicAgent must answer ping with pong').toContain('pong');
    expect(result.msgs, 'non-ping messages must be echoed').toContain('echo: release-gate');
  });

  test('raw streaming HTTP subscriber receives broadcasts from a POST', async ({ page }) => {
    await subscribe(page, '__dslSub');

    const post = await page.evaluate(async () =>
      (await fetch('/chat', { method: 'POST', body: 'ping' })).status);
    expect(post, 'POST /chat must be accepted').toBe(200);

    // Regression 2: without the per-transport flush the suspended GET never
    // sees a byte even though the broadcaster reports a completed delivery.
    await expect
      .poll(() => receivedBy(page, '__dslSub'), {
        timeout: 10_000,
        message: 'suspended raw-HTTP subscriber must receive the broadcast reply',
      })
      .toContain('pong');

    await unsubscribe(page, '__dslSub');
  });

  test('broadcasts fan out across transports (WS sender → HTTP subscriber)', async ({ page }) => {
    await subscribe(page, '__dslSub2');

    const wsSent = await page.evaluate(async () => {
      const ws = new WebSocket(`ws://${location.host}/chat`);
      const opened = await new Promise<boolean>((r) => {
        ws.onopen = () => r(true);
        ws.onerror = () => r(false);
        setTimeout(() => r(false), 8000);
      });
      if (!opened) return false;
      ws.send('cross-transport');
      await new Promise((r) => setTimeout(r, 1000));
      ws.close();
      return true;
    });
    expect(wsSent, 'WS sender must connect').toBe(true);

    await expect
      .poll(() => receivedBy(page, '__dslSub2'), {
        timeout: 10_000,
        message: 'HTTP subscriber must receive the WS sender broadcast',
      })
      .toContain('echo: cross-transport');

    await unsubscribe(page, '__dslSub2');
  });
});
