import { expect, test } from '@playwright/test';

/**
 * Happy-path E2E test for the personal-assistant proof sample.
 *
 * REQUIRES: `samples/spring-boot-personal-assistant` running on port 8080.
 * Spring Boot startup:
 *   cd samples/spring-boot-personal-assistant
 *   ../../mvnw spring-boot:run
 *
 * The test drives the bundled chat UI and asserts the primary assistant
 * routes keyword-matching messages to the right crew member, surfacing
 * the tool-call events the sample emits via session.emit().
 */
test.describe('Personal assistant sample', () => {
  test('loads the chat UI', async ({ page }) => {
    await page.goto('/');
    // The sample bundles a minimal chat UI; we only assert the page loads
    // and exposes an input element a user can type into.
    await expect(page.locator('body')).toBeVisible();
    const inputs = page.locator('input, textarea');
    await expect(inputs.first()).toBeAttached({ timeout: 5_000 });
  });

  test('admin control plane exposes the agent', async ({ request }) => {
    const res = await request.get('/atmosphere/admin/agents');
    expect(res.status()).toBe(200);
    const agents = await res.json();
    expect(
      agents.some(
        (a: { name?: string }) => a.name === 'primary-assistant'
      ),
      'primary-assistant must appear in the agent registry'
    ).toBe(true);
  });

  /**
   * Regression for the strict OpenAI-compat tool-round-trip wire shape.
   * The pa sample drives {@code OpenAiCompatibleClient} through the
   * {@code @AiTool → AgentFleet} loop. OpenAI treats {@code tool_calls}
   * on the assistant message and {@code name} on the tool-role reply as
   * optional, but stricter OpenAI-compatible endpoints reject the second
   * LLM round when either is missing. After the fix, the LLM must
   * complete a second round and emit a narrative summary of the proposed
   * slots — asserting both the tool-call card and the final narrative
   * pins both halves of the wire contract.
   */
  test('schedule request fires tool call and returns narrative response', async ({
    page,
  }) => {
    test.skip(
      !process.env.LLM_API_KEY && !process.env.GEMINI_API_KEY && !process.env.OPENAI_API_KEY,
      'no LLM credentials in env; this test requires a live LLM for the @AiTool loop'
    );

    await page.goto('/atmosphere/console/');
    await expect(page.getByText(/connected/i)).toBeVisible({ timeout: 10_000 });

    const input = page.getByRole('textbox').first();
    await input.fill(
      'I need to book a 30-min check-in with Sarah next week — can you suggest some slots?'
    );
    await page.getByRole('button', { name: /send/i }).click();

    // The tool-call card must surface the scheduler skill that the LLM
    // picked; this is the "assistant emitted tool_calls" half of the fix.
    await expect(page.getByText(/schedule.?meeting/i)).toBeVisible({
      timeout: 60_000,
    });

    // The narrative answer proves the second LLM round completed. Prior to
    // the fix, strict OpenAI-compatible endpoints rejected the follow-up
    // with a 400 and the UI showed "Error: API returned 400". Asserting
    // *both* markers is stricter than either alone — the tool card can
    // appear without the narrative if the wire-shape regression returns.
    await expect(
      page.getByText(/slot|suggest|available|proposed/i).first()
    ).toBeVisible({ timeout: 60_000 });

    // Guard: no upstream 400 rendered. If this assertion starts failing,
    // check ChatMessageSerializationTest for a wire-shape regression.
    await expect(page.getByText(/function_response\.name|API returned 4\d\d/i))
      .toHaveCount(0);
  });
});
