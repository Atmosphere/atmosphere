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
 *   LLM_MODE=local LLM_MODEL=qwen2.5:1.5b LLM_BASE_URL=http://localhost:11434/v1 \
 *   LLM_API_KEY=ollama LLM_TEMPERATURE=0 ./mvnw spring-boot:run -pl samples/spring-boot-ai-chat \
 *     -Dspring-boot.run.arguments=--atmosphere.admin.content-read-auth-required=false
 *
 * The content-read opt-out is REQUIRED: the sample keeps the framework's
 * fail-closed default for /api/admin/governance/decisions (Inv #6), so the
 * token-less console needs this flag to render the Decisions tab it asserts on.
 *
 * A real LLM is mandatory: demo mode (no key) bypasses the pipeline entirely
 * (AiChat.onPrompt streams a canned response and returns), so neither the
 * PreferencePolicy nor the GovernanceFeedbackInterceptor run. The loop is only
 * observable on the real streaming path.
 *
 * Proves both halves of the loop:
 *  1. PRODUCE — asking about a production deploy fires the org's
 *     `production-release-advisor` PreferencePolicy, recording a PREFER decision
 *     (deterministic; asserted on /api/admin/governance/decisions for this
 *     conversation, and that same conversation's row in the console Decisions tab).
 *  2. CARRY — the GovernanceFeedbackInterceptor injects that advisory into the
 *     same request, so the model's answer names the Example Corp `release-bot` /
 *     `#prod-releases` process. Those tokens are unknowable to the base model, so
 *     this assertion FAILS when the loop is off — it is not trivially true.
 */
const PROMPT = 'How do I deploy the billing service to production?';
const ADVISOR = 'production-release-advisor';

interface DecisionEntry {
  policy_name: string;
  decision: string;
  context_snapshot?: Record<string, unknown>;
}

/** Conversation ids that already carry a PREFER from the advisor (earlier runs on this server). */
function preferConversations(entries: DecisionEntry[]): Set<string> {
  return new Set(entries
    .filter((e) => e.policy_name === ADVISOR && e.decision === 'prefer')
    .map((e) => String(e.context_snapshot?.conversation_id ?? '')));
}

test.describe('governance-feedback: soft-preference steers the answer', () => {
  // Connect (15 s) + a full streamed answer from a CPU-only Ollama on a CI runner.
  // The default 30 s test budget is shorter than the reply wait below, so a slow
  // first turn was killed by the test timeout rather than by the assertion.
  test.setTimeout(120_000);

  test('a production-deploy question yields a PREFER + org-specific guidance', async ({ page, baseURL }) => {
    const decisionsUrl = `${baseURL}/api/admin/governance/decisions?limit=200`;
    const readDecisions = async (): Promise<DecisionEntry[]> => {
      const res = await page.request.get(decisionsUrl);
      expect(res.ok(), `GET ${decisionsUrl} -> ${res.status()}`).toBeTruthy();
      return res.json();
    };
    const seenBefore = preferConversations(await readDecisions());

    await page.goto(`${baseURL}/atmosphere/console/`);
    // The status pill reads "Disconnected" until the transport opens, and a plain
    // getByText('Connected') substring-matches that word — so it must be a prefix
    // match on the pill itself. While disconnected the console ignores Enter and
    // keeps the prompt in the textarea, which is the failure CI hit.
    await expect(page.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });

    await page.getByTestId('chat-input').fill(PROMPT);
    // The send button stays disabled until the console is connected; click()
    // waits for it to be enabled, so the prompt cannot be dropped.
    await page.getByTestId('chat-send').click();
    const bubbles = page.getByTestId('message-bubble');
    await expect(bubbles.first()).toContainText(PROMPT);

    // PRODUCE: the policy plane recorded a PREFER from the advisor for THIS
    // conversation (one this server had not seen before the send), carrying the
    // Example Corp process as the preferred path. Structured, model-independent.
    let conversationId = '';
    await expect.poll(async () => {
      const fresh = (await readDecisions()).find((e) =>
        e.policy_name === ADVISOR && e.decision === 'prefer'
        && e.context_snapshot?.message === PROMPT
        && !seenBefore.has(String(e.context_snapshot?.conversation_id ?? '')));
      conversationId = String(fresh?.context_snapshot?.conversation_id ?? '');
      return fresh ? String(fresh.context_snapshot?.preferred ?? '') : null;
    }, { timeout: 15_000, message: 'a new PREFER decision from ' + ADVISOR })
      .toMatch(/release-bot/);
    // The row below is located by this id, so an empty one would match any row.
    expect(conversationId, 'the fresh PREFER carries a conversation_id').not.toBe('');

    // Terminal frame: the session-stats footer renders only once the `complete`
    // frame has finalized the assistant message (an `error` frame never shows it).
    await expect(page.getByTestId('session-stats')).toBeVisible({ timeout: 90_000 });

    // CARRY: the injected advisory steered the finished answer to the org-specific
    // process. `release-bot` is a token the base model cannot know — it only appears
    // because the PreferencePolicy's advisory was injected into the request.
    await expect(bubbles).toHaveCount(2);
    await expect(bubbles.nth(1)).toContainText(/release-bot/i);

    // The console's Decisions tab renders THIS run's PREFER row. Each row carries its
    // context snapshot (collapsed <details> JSON, still in the DOM), so the row is
    // located by this conversation's id: a PREFER left by an earlier run on the same
    // server (--repeat-each, a reused server) cannot satisfy it.
    await page.getByRole('button', { name: /Decisions/ }).click();
    const preferRow = page.getByTestId('governance-decisions').locator('li.decision')
      .filter({ hasText: ADVISOR })
      .filter({ has: page.locator('.badge-prefer') })
      .filter({ hasText: conversationId });
    await expect(preferRow).toHaveCount(1, { timeout: 15_000 });
    await expect(preferRow).toBeVisible();
  });
});
