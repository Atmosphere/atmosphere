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
import { test, expect } from '@playwright/test';

/**
 * E2E contract for the ai-classroom sample's headline: one student asks, and
 * EVERY student in the same room sees the AI reply stream — the collaborative
 * broadcast (@AiEndpoint(broadcastReply = true)).
 *
 * REQUIRES: samples/spring-boot-ai-classroom running KEYLESS on port 8080
 * (no LLM key → deterministic DemoAgentRuntime replies; the fan-out is a
 * transport-layer property, independent of which runtime produced the text).
 *
 * Pins the 4.0.60 release-gate regression: presence fanned out ("2 online"
 * synced) but the streamed reply went only to the asking resource, so a
 * second student in the same room never saw the answer — the endpoint was
 * missing broadcastReply, so DefaultStreamingSession unicast to the origin
 * instead of broadcasting to the room.
 */

// Two raw WebSocket subscribers on the same room broadcaster, driven from a
// single page's context. The room fan-out is a server property; a raw socket
// observes it without any UI-accumulation noise.
const ROOM = '/atmosphere/classroom/math';

test.describe('ai-classroom: room broadcast fan-out', () => {
  test('a non-asking room member receives the streamed reply', async ({ page, baseURL }) => {
    await page.goto(`${baseURL}/`, { waitUntil: 'commit' });

    const result = await page.evaluate(async (room) => {
      const url = `ws://${location.host}${room}`;
      const open = (ws: WebSocket) => new Promise<boolean>((r) => {
        ws.onopen = () => r(true);
        ws.onerror = () => r(false);
        setTimeout(() => r(false), 8000);
      });

      // Observer joins first and only listens — it never sends a prompt.
      const observer = new WebSocket(url);
      const observerText: string[] = [];
      let observerComplete = false;
      observer.onmessage = (e) => {
        const s = String(e.data);
        if (s.includes('"streaming-text"')) observerText.push(s);
        if (s.includes('"type":"complete"')) observerComplete = true;
      };
      if (!(await open(observer))) return { error: 'observer failed to open' };

      // Asker joins the SAME room and sends one question.
      const asker = new WebSocket(url);
      if (!(await open(asker))) return { error: 'asker failed to open' };
      await new Promise((r) => setTimeout(r, 300));
      asker.send('What is 5 times 5?');

      // Give the demo runtime time to stream word-by-word + complete.
      await new Promise((r) => setTimeout(r, 6000));

      const seqs = observerText.map((f) => (f.match(/"seq":(\d+)/) || [])[1]);
      const uniqueSeqs = new Set(seqs).size;
      observer.close();
      asker.close();
      return {
        observerTextFrames: observerText.length,
        observerUniqueSeqs: uniqueSeqs,
        observerComplete,
      };
    }, ROOM);

    expect(result.error, `websocket setup failed: ${result.error}`).toBeUndefined();
    // The observer never asked, yet must receive the streamed reply — the
    // fan-out. Zero frames here is the exact regression (unicast to origin).
    expect(result.observerTextFrames ?? 0,
        'a room member who did not ask must receive the streamed reply').toBeGreaterThan(0);
    // Exactly-once delivery: no frame is duplicated to the observer.
    expect(result.observerUniqueSeqs).toBe(result.observerTextFrames);
    expect(result.observerComplete, 'the observer must see the terminal complete frame').toBe(true);
  });

  test('a member of a DIFFERENT room does not receive the reply', async ({ page, baseURL }) => {
    await page.goto(`${baseURL}/`, { waitUntil: 'commit' });

    const result = await page.evaluate(async () => {
      const open = (ws: WebSocket) => new Promise<boolean>((r) => {
        ws.onopen = () => r(true);
        ws.onerror = () => r(false);
        setTimeout(() => r(false), 8000);
      });

      // Observer in the CODE room; asker in the MATH room.
      const observer = new WebSocket(`ws://${location.host}/atmosphere/classroom/code`);
      const observerText: string[] = [];
      observer.onmessage = (e) => {
        const s = String(e.data);
        if (s.includes('"streaming-text"')) observerText.push(s);
      };
      if (!(await open(observer))) return { error: 'observer failed to open' };

      const asker = new WebSocket(`ws://${location.host}/atmosphere/classroom/math`);
      if (!(await open(asker))) return { error: 'asker failed to open' };
      await new Promise((r) => setTimeout(r, 300));
      asker.send('What is 5 times 5?');
      await new Promise((r) => setTimeout(r, 6000));

      observer.close();
      asker.close();
      return { crossRoomTextFrames: observerText.length };
    });

    expect(result.error, `websocket setup failed: ${result.error}`).toBeUndefined();
    // Each room has its own broadcaster ({room} path param) — the code room
    // must NOT see a math-room reply.
    expect(result.crossRoomTextFrames ?? -1,
        'a reply must stay within its own room broadcaster').toBe(0);
  });
});
