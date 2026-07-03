import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-browser-agent']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Browser agent', () => {
  test('console loads with the chat input', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();
    await expect(page.getByTestId('chat-send')).toBeVisible();
  });

  // Runtime truth for the headline capability: this sample is a "code-as-action"
  // agent — the model writes Playwright/JS that the framework runs in an
  // isolated container via the code_exec tool. Driving a full browse to a live
  // site needs both a real LLM (the sample pins Cohere) and a container engine,
  // neither of which the keyless e2e lane has. But the sample must at least BOOT
  // with the capability actually wired — the code_exec tool registered and
  // code-as-action enabled on the endpoint — not merely declared. The startup
  // log is the runtime-truth surface; the container EXECUTION path itself is
  // covered by CodeExecSandboxIntegrationTest (Docker-gated) in modules/ai.
  test('boots with code-as-action wired: code_exec tool registered and enabled', async () => {
    const out = server.getOutput();
    expect(out, "code-as-action must be enabled on the @AiEndpoint")
      .toContain("Code-as-action enabled: registered 'code_exec' tool");
    expect(out, 'the code_exec tool must be registered in the tool registry')
      .toMatch(/Registered AI tool: code_exec/);
    expect(out, 'code execution must be enabled (container sandbox advertised)')
      .toMatch(/Code execution ENABLED/);
  });

  // Regression for the browser-agent key handling. The sample pins the Cohere
  // runtime; the generic key resolver falls back to LLM_API_KEY / OPENAI_API_KEY
  // / GEMINI_API_KEY, so before the fix a stray non-Cohere key was sent to
  // Cohere and produced a confusing "401 Incorrect API key". The fix checks
  // COHERE_API_KEY directly and renders a clear hint when it is absent.
  //
  // CI runs samples without a COHERE_API_KEY, so this drives the no-key path and
  // asserts the hint renders — and that the leaked-key 401 does NOT.
  test('without a Cohere key, shows the COHERE_API_KEY hint instead of a 401', async ({ page }) => {
    test.skip(
      !!process.env.COHERE_API_KEY,
      'COHERE_API_KEY is set in this environment — the no-key hint path is not exercised',
    );

    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill("What's the top story on news.ycombinator.com?");
    await page.getByTestId('chat-send').click();

    await expect(page.getByText(/COHERE_API_KEY/)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(/Incorrect API key|returned 401/i)).toHaveCount(0);
  });
});
