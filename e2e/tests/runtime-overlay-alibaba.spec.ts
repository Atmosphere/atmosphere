import { expect, test } from '@playwright/test';

/**
 * Browser-level e2e for the Alibaba runtime overlays
 * (`atmosphere new --runtime agentscope --force` and
 * `--runtime spring-ai-alibaba --force`) applied to the existing
 * `samples/spring-boot-ai-chat` template.
 *
 * The companion shell harness `cli/e2e-test-runtime-overlay.sh` covers
 * the SPI-resolution side: it scaffolds + boots the sample with each
 * overlay and asserts `/api/admin/runtimes/active` reports the right
 * `AgentRuntime.name()`. This Playwright spec re-runs that admin
 * assertion from the browser AND verifies the chat UI loads + connects
 * over Atmosphere transport, which the shell harness cannot exercise.
 *
 * REQUIRES:
 *   For agentscope:        sample running on port 18808
 *   For spring-ai-alibaba: sample running on port 18809
 *
 * The CLI overlay e2e script boots each runtime on those ports. To run
 * this spec against a running instance:
 *
 *   ATMO_RUNTIME=agentscope npx playwright test runtime-overlay-alibaba
 *   ATMO_RUNTIME=spring-ai-alibaba npx playwright test runtime-overlay-alibaba
 *
 * A full LLM round-trip is NOT asserted here — the sample uses a bogus
 * `LLM_API_KEY=test-key-not-real` in CI to dislodge the demo runtime;
 * any real chat send would fail at the OpenAI endpoint with 401. That's
 * documented behavior and out of scope for this admission test.
 */

const RUNTIME = (process.env.ATMO_RUNTIME ?? 'agentscope') as
    | 'agentscope'
    | 'spring-ai-alibaba';

const PORT_BY_RUNTIME: Record<string, number> = {
    'agentscope': 18808,
    'spring-ai-alibaba': 18809,
};

const port = PORT_BY_RUNTIME[RUNTIME];
if (!port) {
    throw new Error(
        `ATMO_RUNTIME=${RUNTIME} is not in the Alibaba overlay matrix; ` +
        `expected one of: ${Object.keys(PORT_BY_RUNTIME).join(', ')}`,
    );
}

test.describe(`${RUNTIME} runtime overlay`, () => {
    test.use({ baseURL: `http://localhost:${port}` });

    test(`/api/admin/runtimes/active reports ${RUNTIME}`, async ({ request }) => {
        const res = await request.get('/api/admin/runtimes/active');
        expect(res.status()).toBe(200);
        const body = await res.json();
        // The Atmosphere AgentRuntime SPI picks the highest-priority
        // ServiceLoader-registered runtime that reports isAvailable=true.
        // After `--force` strips other adapter deps, only the requested
        // overlay is on the classpath, so this assertion guards against
        // SPI iteration order regressions.
        expect(body.name, `expected runtime name=${RUNTIME}`).toBe(RUNTIME);
        expect(body.isAvailable, `runtime must report isAvailable=true`).toBe(
            true,
        );
    });

    test('chat UI loads and connects over Atmosphere transport', async ({
        page,
    }) => {
        // The bundled console at /atmosphere/console/ is the canonical UI
        // shipped with `samples/spring-boot-ai-chat`. The "connected"
        // indicator only renders after the Atmosphere client has
        // negotiated WebSocket / SSE / long-poll, which exercises the
        // full transport stack — a regression in the SPI bridge would
        // surface here as a missing connect.
        await page.goto('/atmosphere/console/');
        await expect(page.getByText(/connected/i)).toBeVisible({
            timeout: 30_000,
        });

        // The chat input is part of the bundled console UI. Visibility
        // confirms the sample's HTTP root is serving correctly under the
        // overlay's classpath (some overlays drag in conflicting Spring
        // versions; if so, the static resources would 404).
        await expect(page.getByRole('textbox').first()).toBeVisible();
    });
});
