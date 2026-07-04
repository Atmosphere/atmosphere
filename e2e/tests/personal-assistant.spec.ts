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
    // The admin REST API is mounted at /api/admin/*; /atmosphere/admin/* is
    // the WS/UI namespace and returns 500 when hit as a plain HTTP GET (no
    // AtmosphereHandler registered for that path). Using the REST path also
    // pins the four-agent roster the foundation wire-in registers: primary
    // plus the scheduler / research / drafter crew.
    const res = await request.get('/api/admin/agents');
    expect(res.status()).toBe(200);
    const agents = await res.json();
    const names = agents.map((a: { name?: string }) => a.name);
    for (const expected of [
      'primary-assistant',
      'scheduler-agent',
      'research-agent',
      'drafter-agent',
    ]) {
      expect(names, `missing agent: ${expected}`).toContain(expected);
    }
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

  /**
   * Exercises the second @AiTool (research_topic → research-agent). The
   * research path is the simplest route — one crew member, one skill,
   * one artifact. This is the smoke test for "crew dispatch over
   * InMemoryProtocolBridge still works when the framework type-injects
   * AgentFleet instead of a ThreadLocal shim".
   */
  test('research request dispatches to the research specialist', async ({ page }) => {
    test.skip(
      !process.env.LLM_API_KEY && !process.env.GEMINI_API_KEY && !process.env.OPENAI_API_KEY,
      'no LLM credentials in env; this test requires a live LLM for the @AiTool loop'
    );

    await page.goto('/atmosphere/console/');
    await expect(page.getByText(/connected/i)).toBeVisible({ timeout: 10_000 });

    await page.getByRole('textbox').first()
      .fill('Give me a quick brief on WebTransport adoption in the browser.');
    await page.getByRole('button', { name: /send/i }).click();

    // Research tool card + the specialist's canned prefix in the artifact.
    await expect(page.getByText(/research.?topic|Summarize Topic/i).first())
      .toBeVisible({ timeout: 60_000 });
    await expect(page.getByText(/Research brief for/i).first())
      .toBeVisible({ timeout: 60_000 });
  });

  /**
   * Exercises the third @AiTool (draft_message → drafter-agent) — the only
   * tool with two @Param arguments. If the LLM can pull `recipient` and
   * `intent` out of a natural-language prompt, tool-argument schema
   * generation is round-tripping both fields correctly. Regression surface
   * for the AiTool-parameter-exclusion change in DefaultToolRegistry
   * (framework-typed params were filtered but business @Param args must
   * still appear in the schema).
   */
  test('draft request passes both @Param args through the schema', async ({ page }) => {
    test.skip(
      !process.env.LLM_API_KEY && !process.env.GEMINI_API_KEY && !process.env.OPENAI_API_KEY,
      'no LLM credentials in env; this test requires a live LLM for the @AiTool loop'
    );

    await page.goto('/atmosphere/console/');
    await expect(page.getByText(/connected/i)).toBeVisible({ timeout: 10_000 });

    await page.getByRole('textbox').first()
      .fill('Draft a short note to the ops team letting them know the rollout is paused.');
    await page.getByRole('button', { name: /send/i }).click();

    // Draft tool fires with both params visible on the tool-call card.
    await expect(page.getByText(/draft.?message/i).first())
      .toBeVisible({ timeout: 60_000 });
    await expect(page.getByText(/recipient/i).first())
      .toBeVisible({ timeout: 60_000 });
    await expect(page.getByText(/intent/i).first())
      .toBeVisible({ timeout: 60_000 });
  });

  /**
   * Harness runtime truth through the full stack. The sample activates the
   * harness through all three agent-family surfaces without any app-wide
   * flag: @Agent crew members and the @Coordinator primary are
   * batteries-included by default (harness() defaults to {Harness.ALL}),
   * and UpstreamMcpAgent opts its bare @AiEndpoint in per-endpoint with
   * harness = {Harness.ALL}. The console info endpoint must report each
   * primitive genuinely ACTIVE — not config intent, but confirmed attach
   * (Correctness Invariant #5). If the batteries-on default regresses, or
   * the per-feature gating drops a primitive, these assertions go red.
   */
  test('console info reports the harness batteries genuinely ACTIVE', async ({
    request,
  }) => {
    const res = await request.get('/api/console/info');
    expect(res.status()).toBe(200);
    const info = await res.json();

    expect(info.harness, 'harness runtime-truth block must be published')
      .toBeDefined();
    expect(info.harness['conversation-memory']).toBe('ACTIVE');
    // Long-term memory reports the resolved store class alongside the state,
    // e.g. ACTIVE(org.atmosphere.ai.memory.InMemoryLongTermMemory).
    expect(info.harness['long-term-memory']).toMatch(/^ACTIVE/);
    expect(info.harness['prompt-cache-default']).toBe('conservative');
    expect(info.harness['delegation'], 'a declared fleet keeps delegation on by default')
      .toBe('ACTIVE');
    expect(info.harness['compaction'], 'a compaction strategy must be selected')
      .toBeTruthy();
    // The sample ships no framework runtime adapter with a native plan or
    // file surface, so the AUTO mode knobs must land on the built-in floors
    // (write_todos + the six file tools) — and the state must be the
    // attach-time truth, never the INACTIVE seed (Invariant #5).
    expect(info.harness['planning'], 'the write_todos floor must genuinely attach')
      .toMatch(/^ACTIVE\(builtin/);
    expect(info.harness['filesystem'], 'the file-tool floor must genuinely attach')
      .toMatch(/^ACTIVE\(builtin/);
  });

  /**
   * The admin plan surface for the harness PLANNING primitive. The endpoint
   * resolves the exact AgentPlanStore the coordinator's attach registered, so
   * a fresh probe session answers 404 (surface attached, no plan written yet)
   * or 200 with the plan-update wire shape — never 500. The traversal probe
   * pins that the route is genuinely mapped (an unmapped path would 404, not
   * 400) and that session ids are validated at the boundary (Invariant #4).
   */
  test('admin plan endpoint serves the harness plan surface', async ({ request }) => {
    const probe = await request.get(
      '/api/admin/agents/primary-assistant/plan?sessionId=e2e-plan-probe'
    );
    expect(
      [200, 404],
      `plan endpoint must answer 404 (no plan yet) or 200 (plan), got ${probe.status()}`
    ).toContain(probe.status());
    if (probe.status() === 200) {
      const plan = await probe.json();
      expect(plan.agent).toBe('primary-assistant');
      expect(Array.isArray(plan.steps), 'a served plan must carry wire steps').toBe(true);
    }

    const traversal = await request.get(
      '/api/admin/agents/primary-assistant/plan?sessionId=..'
    );
    expect(traversal.status(), 'a traversal session id must be rejected with 400').toBe(400);
    const err = await traversal.json();
    expect(err.error, 'the rejection must carry a clear message').toBeTruthy();
  });
});
