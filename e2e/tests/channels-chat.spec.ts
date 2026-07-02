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
 * E2E contract for the channels-chat sample's WEB channel, keyless.
 *
 * REQUIRES: samples/spring-boot-channels-chat running KEYLESS on port 8080
 * (no LLM key → the single @Agent answers via DemoAgentRuntime; the console
 * routes to it through the /atmosphere/ai-chat alias).
 *
 * The 4.0.60 release-gate flagged "keyless demo mode silently swallows web
 * messages" — that turned out to be a stale-socket test artifact, not a
 * sample defect (a clean boot answers every turn). This spec exists to keep
 * it that way: it fails loudly if the omnichannel agent ever stops answering
 * the web surface without a key, and asserts multiple turns in a row (the
 * "swallowed second message" shape) all get a reply.
 */

test.describe('channels-chat: web channel answers keyless', () => {
  test('the omnichannel agent replies to consecutive web turns in demo mode', async ({ page, baseURL }) => {
    await page.goto(`${baseURL}/atmosphere/console/`);
    await expect(page.getByText('Connected', { exact: false }).first()).toBeVisible({ timeout: 15_000 });

    const input = page.getByRole('textbox');

    await input.fill('Hello omnichannel');
    await input.press('Enter');
    await expect(page.getByText('You said: “Hello omnichannel”')).toBeVisible({ timeout: 15_000 });
    // Wait for turn 1 to FULLY complete before turn 2: the console gates
    // input while a turn is streaming (the same as every AI sample), so a
    // second message typed mid-stream is dropped by the client. The per-turn
    // token/latency badge only renders on complete().
    await expect(page.getByText('tok/s').first()).toBeVisible({ timeout: 15_000 });

    // The reported failure was the SECOND turn being swallowed — assert a
    // second consecutive turn also lands once the first has completed.
    await input.fill('Second turn still works');
    await input.press('Enter');
    await expect(page.getByText('You said: “Second turn still works”')).toBeVisible({ timeout: 15_000 });

    // Both replies come from the keyless demo runtime — proves the web channel
    // dispatched to the @Agent, not that the message vanished.
    await expect(page.getByText('Demo mode').first()).toBeVisible();
  });
});
