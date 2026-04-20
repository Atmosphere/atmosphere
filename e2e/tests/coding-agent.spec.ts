import { expect, test } from '@playwright/test';

/**
 * Happy-path E2E test for the coding-agent proof sample.
 *
 * REQUIRES: `samples/spring-boot-coding-agent` running on port 8081 AND
 * either Docker available locally (DockerSandboxProvider) or the
 * in-process provider fallback enabled.
 *
 * Override the base URL at test time:
 *   ATMO_E2E_BASE_URL=http://localhost:8081 npm run test:coding-agent
 */
test.describe('Coding agent sample', () => {
  test.use({ baseURL: process.env.ATMO_E2E_BASE_URL ?? 'http://localhost:8081' });

  test('admin control plane registers the coding-agent', async ({ request }) => {
    // Admin REST API is mounted at /api/admin/*; /atmosphere/admin/* is the
    // WS/UI namespace and returns 500 when hit as a plain HTTP GET.
    const res = await request.get('/api/admin/agents');
    expect(res.status()).toBe(200);
    const agents = await res.json();
    expect(
      agents.some((a: { name?: string }) => a.name === 'coding-agent'),
      'coding-agent must appear in the agent registry'
    ).toBe(true);
  });

  test('sandbox provider is reachable', async ({ page }) => {
    // The sample does not expose a dedicated sandbox status endpoint;
    // we assert the bundled UI loads, which confirms the backend is up
    // and the ServiceLoader-discovered sandbox provider did not fail at
    // wiring time.
    await page.goto('/');
    await expect(page.locator('body')).toBeVisible();
  });

  /**
   * Regression for the {@code session.stream()} → {@code session.send()}
   * fix (commit 3ed3d095eb). The bug: the sample's @Prompt method used
   * {@code session.stream(readmePreview)} which dispatches the argument to
   * the LLM as a *new user turn* (per {@link StreamingSession#stream}
   * javadoc) instead of streaming it to the client. Result: the UI showed a
   * Gemini hallucination ("I'll clone…") and the Docker sandbox never ran.
   * Post-fix, the real README bytes reach the client unchanged.
   *
   * <p>The assertion targets "Hello World!" — the literal README content of
   * octocat/Hello-World. A hallucinated reply from any LLM would not echo
   * that exact string after the "README preview from" header.</p>
   *
   * <p>Skipped when the {@code SKIP_SANDBOX_E2E} env var is set (CI runners
   * without Docker) — the Sandbox SPI contract tests in
   * {@code modules/sandbox} cover the provider surface in isolation.</p>
   */
  test('clone + read emits real sandbox output to the client', async ({
    page,
  }) => {
    test.skip(
      !!process.env.SKIP_SANDBOX_E2E,
      'SKIP_SANDBOX_E2E set (no Docker or in-process sandbox available)'
    );

    await page.goto('/atmosphere/console/');
    await expect(page.getByText(/connected/i)).toBeVisible({ timeout: 10_000 });

    const input = page.getByRole('textbox').first();
    await input.fill(
      'clone https://github.com/octocat/Hello-World.git and read README.md'
    );
    await page.getByRole('button', { name: /send/i }).click();

    // README preview header comes from the sample's own send() call; its
    // presence proves the @Prompt body executed (provisioning → clone → read).
    await expect(page.getByText(/README preview from/i)).toBeVisible({
      timeout: 180_000,
    });

    // Literal bytes of the octocat/Hello-World README. If the response is
    // ever produced by the LLM instead of the sandbox, this assertion fails
    // because the LLM won't echo the exact file content.
    await expect(page.getByText('Hello World!')).toBeVisible({
      timeout: 180_000,
    });

    // Negative guard: the previous incorrect output started with the LLM's
    // stock lead-in. If this reappears, session.stream() is back in the
    // display branches — check CodingAgent.runSandboxFlow.
    await expect(page.getByText(/I'll clone|I've seen the README/i))
      .toHaveCount(0);
  });

  /**
   * Sad-path: clone a repo that doesn't exist. The sample's @Prompt must
   * surface "Clone failed:" (with the underlying git stderr) via
   * session.send() rather than swallow the failure or hand it to the LLM
   * to paper over. Pins Correctness Invariant #2 — terminal paths must
   * complete — for the failure branch of the sandbox flow.
   */
  test('clone of a missing repo streams the real git error', async ({ page }) => {
    test.skip(
      !!process.env.SKIP_SANDBOX_E2E,
      'SKIP_SANDBOX_E2E set (no Docker or in-process sandbox available)'
    );

    await page.goto('/atmosphere/console/');
    await expect(page.getByText(/connected/i)).toBeVisible({ timeout: 10_000 });

    await page.getByRole('textbox').first()
      .fill('clone https://github.com/atmosphere-invalid/does-not-exist.git and read README.md');
    await page.getByRole('button', { name: /send/i }).click();

    // Either "Clone failed:" from the happy path of the failure handler, or
    // "no README found" if the clone somehow succeeded on a mock network —
    // both are real, non-hallucinated outputs from the @Prompt body.
    await expect(page.getByText(/Clone failed|no README/i)).toBeVisible({
      timeout: 180_000,
    });

    // Same hallucination guard as the happy path — if an LLM reply ever
    // lands on the failure branch, something is routing @Prompt output
    // through session.stream() again.
    await expect(page.getByText(/I'll clone|I've seen the README/i))
      .toHaveCount(0);
  });
});
