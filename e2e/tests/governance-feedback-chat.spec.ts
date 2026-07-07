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
 * E2E for "governance as a learning signal" in the shipped console
 * (samples/spring-boot-ai-chat GovernanceFeedbackConfig + GovernanceFeedbackInterceptor).
 *
 * REQUIRES: samples/spring-boot-ai-chat running on port 8080 WITH A REAL LLM
 * (Ollama lane), e.g.:
 *   LLM_MODE=local LLM_MODEL=qwen2.5:3b LLM_BASE_URL=http://localhost:11434/v1 \
 *   LLM_API_KEY=ollama ./mvnw spring-boot:run -pl samples/spring-boot-ai-chat
 *
 * A real LLM is mandatory: demo mode (no key) bypasses the pipeline entirely
 * (AiChat.onPrompt streams a canned response and returns), so neither the
 * PreferencePolicy nor the GovernanceFeedbackInterceptor run. The loop is only
 * observable on the real streaming path.
 *
 * Proves both halves of the loop:
 *  1. PRODUCE — asking about a production deploy fires the org's
 *     `production-release-advisor` PreferencePolicy, recording a PREFER decision
 *     (deterministic; visible in the console Decisions tab).
 *  2. CARRY — the GovernanceFeedbackInterceptor injects that advisory into the
 *     same request, so the model's answer names the Example Corp `release-bot` /
 *     `#prod-releases` process. Those tokens are unknowable to the base model, so
 *     this assertion FAILS when the loop is off — it is not trivially true.
 */
test.describe('governance-feedback: soft-preference steers the answer', () => {
  test('a production-deploy question yields a PREFER + org-specific guidance', async ({ page, baseURL }) => {
    await page.goto(`${baseURL}/atmosphere/console/`);
    await expect(page.getByText('Connected', { exact: false }).first())
      .toBeVisible({ timeout: 15_000 });

    const input = page.getByRole('textbox');
    await input.fill('How do I deploy the billing service to production?');
    await input.press('Enter');

    // CARRY: the injected advisory steers the model to the org-specific process.
    // `release-bot` is a token the base model cannot know — it only appears because
    // the PreferencePolicy's advisory was injected into the request.
    await expect(page.getByText(/release-bot/i).first())
      .toBeVisible({ timeout: 60_000 });

    // PRODUCE: the decision was recorded as a PREFER from the advisor policy.
    await page.getByRole('button', { name: /Decisions/ }).click();
    await expect(page.getByText('PREFER').first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('production-release-advisor').first()).toBeVisible();
  });
});
