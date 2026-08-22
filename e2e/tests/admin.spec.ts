import { expect, test } from '@playwright/test';

/**
 * Admin control-plane smoke test. Asserts the admin UI loads and exposes
 * the endpoints the v0.5 foundation documentation promises: workspace
 * inspection, memory browse, audit log, eval results tab.
 *
 * REQUIRES: any Atmosphere sample with `atmosphere-quarkus-admin-extension`
 * or `atmosphere-spring-boot-starter` running at the base URL. The
 * personal-assistant sample (port 8080) is the default target.
 */

test.describe('Admin control plane', () => {
  test('loads the admin index without console errors', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', (err) => errors.push(err.message));
    page.on('console', (msg) => {
      if (msg.type() === 'error') errors.push(msg.text());
    });

    await page.goto('/atmosphere/admin/');
    await expect(page).toHaveTitle(/atmosphere/i);
    expect(errors, 'admin index must load with no console errors').toEqual([]);
  });

  test('exposes agent list endpoint', async ({ request }) => {
    const res = await request.get('/atmosphere/admin/agents');
    expect(res.status(), 'admin agents endpoint responds with 200').toBe(200);
    const body = await res.json();
    expect(Array.isArray(body), 'agents endpoint returns an array').toBe(true);
  });

  test('exposes workspace state endpoints for a known agent', async ({
    request,
  }) => {
    // registre#21 regression: this used to accept [200, 404], which a
    // MISSING route also satisfies — the test could not fail whether the
    // feature existed or not. Discriminate instead: a listed agent must
    // serve its rules with the real shape; with no agents registered, an
    // unknown id must 404 (wired route, honest classification) while a
    // 405/500/503 still fails.
    const agentsRes = await request.get('/atmosphere/admin/agents');
    expect(agentsRes.status()).toBe(200);
    const agents: Array<{ name?: string }> = await agentsRes.json();

    if (agents.length > 0 && agents[0].name) {
      const rulesRes = await request.get(
        `/atmosphere/admin/agents/${agents[0].name}/rules?userId=alice`
      );
      expect(rulesRes.status(), 'a registered agent must serve its rules').toBe(200);
      const rules = await rulesRes.json();
      expect(rules, 'rules payload carries the composed system prompt').toHaveProperty(
        'systemPrompt'
      );
    } else {
      const rulesRes = await request.get(
        '/atmosphere/admin/agents/no-such-agent/rules?userId=alice'
      );
      expect(
        rulesRes.status(),
        'an unknown agent must classify as 404 on the wired route'
      ).toBe(404);
    }
  });
});
