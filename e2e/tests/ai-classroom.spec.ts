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
 * EVERY student in the same room sees the question and the AI reply stream —
 * the collaborative broadcast (@AiEndpoint(broadcastReply = true)).
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
    // Land on the Console SPA itself — the sample root is a meta-refresh
    // redirect to it, which would destroy the evaluate() context mid-test.
    await page.goto(`${baseURL}/atmosphere/console/`, { waitUntil: 'commit' });

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
      // Every JSON frame the observer receives, in order (a WebSocket message
      // may carry a length prefix, or several newline-separated frames).
      const observerFrames: Record<string, unknown>[] = [];
      let observerComplete = false;
      observer.onmessage = (e) => {
        const s = String(e.data);
        if (s.includes('"streaming-text"')) observerText.push(s);
        if (s.includes('"type":"complete"')) observerComplete = true;
        for (const part of s.replace(/^\d+\|/, '').split('\n')) {
          try { observerFrames.push(JSON.parse(part)); } catch { /* not a JSON frame */ }
        }
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
      const prompts = observerFrames.filter((f) => f.type === 'prompt');
      const firstPrompt = observerFrames.findIndex((f) => f.type === 'prompt');
      const firstText = observerFrames.findIndex((f) => f.type === 'streaming-text');
      return {
        observerTextFrames: observerText.length,
        observerUniqueSeqs: uniqueSeqs,
        observerComplete,
        promptFrames: prompts.length,
        promptText: prompts[0]?.text,
        promptFrom: prompts[0]?.from,
        promptData: prompts[0]?.data,
        promptBeforeReply: firstPrompt >= 0 && (firstText < 0 || firstPrompt < firstText),
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
    // The observer also sees what the asker typed: one "type":"prompt" frame,
    // the prompt in text (never data), from the asker, ahead of the reply.
    expect(result.promptFrames, "the observer must receive the asker's prompt once").toBe(1);
    expect(result.promptText).toBe('What is 5 times 5?');
    // Its sender by room member id (32 hex characters), never by tracking id.
    expect(String(result.promptFrom), 'the prompt frame names its sender by room member id')
        .toMatch(/^[0-9a-f]{32}$/);
    expect(result.promptData, 'the prompt rides in text, never data').toBeUndefined();
    expect(result.promptBeforeReply, 'the prompt arrives before the first reply frame').toBe(true);
  });

  test("the Console shows a classmate's question as a user bubble on the observer's page", async ({ browser, baseURL }) => {
    // Two students, each in a browser context of their own (their own
    // connection), both picking the math room in the Console.
    const askerContext = await browser.newContext();
    const observerContext = await browser.newContext();
    try {
      const asker = await askerContext.newPage();
      const observer = await observerContext.newPage();
      for (const page of [observer, asker]) {
        await page.goto(`${baseURL}/atmosphere/console/`);
        await page.getByTestId('pick-math').click();
        await expect(page.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });
      }

      // Unique per run, with no digit run the classroom's PII policy would redact.
      const question = `What is six times seven, ${Math.random().toString(36).replace(/[0-9.]/g, '')}?`;
      await asker.getByTestId('chat-input').fill(question);
      await asker.getByTestId('chat-send').click();

      // The observer never typed anything, yet the question appears as a user
      // bubble, followed by the reply in an assistant bubble.
      await expect(observer.locator('.message--user', { hasText: question })).toHaveCount(1, { timeout: 15_000 });
      await expect(observer.locator('.message--assistant').last()).not.toBeEmpty({ timeout: 15_000 });
      // The asker shows its own question once: its echo is skipped.
      await expect(asker.locator('.message--user', { hasText: question })).toHaveCount(1);
    } finally {
      await askerContext.close();
      await observerContext.close();
    }
  });

  test('a member of a DIFFERENT room does not receive the reply', async ({ page, baseURL }) => {
    // Same as above: avoid the sample root's meta-refresh redirect.
    await page.goto(`${baseURL}/atmosphere/console/`, { waitUntil: 'commit' });

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
