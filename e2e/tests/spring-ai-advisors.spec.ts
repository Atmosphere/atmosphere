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
 * E2E contract for the spring-ai-advisors sample: "bind your own ChatClient"
 * (blog §3) driven through the shipped console UI.
 *
 * REQUIRES: samples/spring-boot-spring-ai-advisors running KEYLESS on 8098
 * (no LLM_API_KEY / GEMINI_API_KEY / SPRING_AI_OPENAI_API_KEY in the env) —
 * keyless operation IS the feature under test: the sample binds a
 * caller-built ChatClient over an offline LocalEchoChatModel.
 *
 * Pins the two halves of the 4.0.60 release-gate regression:
 *
 *  1. Keyless, the resolver picked the demo runtime over the explicitly
 *     bound client (canned placeholder replies; "Runtime: demo" badge) —
 *     DemoAgentRuntime now yields when an explicit client binding exists.
 *  2. With an ambient key, auto-configuration clobbered the bound client
 *     with its own context bean, so the client's defaultAdvisors(...) never
 *     fired (default-advisor stayed 0) — auto-configuration now offers
 *     instead of binding.
 *
 * The audit-log endpoint is the sample's own proof surface: default-advisor
 * must fire on EVERY turn (it rides the bound client), per-request-advisor
 * only on the turn the interceptor stamps it onto.
 */

test.describe('spring-ai-advisors: bound ChatClient contract', () => {
  test('keyless run resolves spring-ai (not demo) and both advisor kinds fire', async ({ page, baseURL }) => {
    // Regression 1: the resolver must pick the runtime carrying the bound
    // client, not the demo fallback, even with no API key configured.
    const info = await (await page.request.get(`${baseURL}/api/console/info`)).json();
    expect(info.runtime, 'bound ChatClient must beat the demo fallback keyless').toBe('spring-ai');

    await page.goto(`${baseURL}/atmosphere/console/`);
    await expect(page.getByText('Connected', { exact: false }).first()).toBeVisible({ timeout: 15_000 });

    const input = page.getByRole('textbox');
    // Turn 1 — only the bound client's default advisor should run.
    await input.fill('hello');
    await input.press('Enter');
    // LocalEchoChatModel terminates the chain — the reply renders offline.
    await expect(page.getByText('[local-echo]').first()).toBeVisible({ timeout: 15_000 });

    // Turn 2 — the interceptor attaches the per-request advisor.
    await input.fill('audit my last answer');
    await input.press('Enter');
    await expect(page.getByText('[local-echo]').nth(1)).toBeVisible({ timeout: 15_000 });

    // Regression 2: default-advisor rides the bound client — 2 turns = 2
    // invocations. Zero here means dispatch bypassed the bound client.
    await expect
      .poll(async () => (await page.request.get(`${baseURL}/api/advisors/audit-log`)).json(), {
        timeout: 10_000,
        message: 'default advisor must fire on every turn, per-request only on its turn',
      })
      .toMatchObject({ 'default-advisor': 2, 'per-request-advisor': 1 });
  });
});
