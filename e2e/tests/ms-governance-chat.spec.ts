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
import { test, expect, Page } from '@playwright/test';

/**
 * E2E contract for the ms-governance-chat sample: the README "Try each rule"
 * table driven through the shipped console UI.
 *
 * REQUIRES: samples/spring-boot-ms-governance-chat on port 8090, started with
 *   --demo.support-hours.always-open=true
 * (the support-business-hours policy honestly denies outside 08:00–20:00 ET
 * Mon–Sat; the switch widens the window so this spec is deterministic at any
 * CI wall-clock).
 *
 * Pins the 4.0.60 release-gate regression chain:
 *
 *  1. The sample's own require-tenant-id / require-support-role policies
 *     denied every console turn (the console carries no identity), so the
 *     README's YAML-rule table was unreachable — DemoIdentityInterceptor now
 *     stamps the demo tenant/role the way an auth layer would.
 *  2. The spring-boot-starter's GovernancePolicy bean bridge clobbered the
 *     sample's composed policy chain (dropping the MS-schema YAML layer,
 *     which is not a Spring bean) — the bridge now merges instead. If the
 *     YAML layer is missing, "DROP TABLE" is ADMITTED and this spec fails.
 *  3. @AiEndpoint(interceptors=...) never fire on a manual
 *     PolicyAdmissionGate path (they run inside session.stream only) — the
 *     handler now runs its chain explicitly, which this spec observes
 *     end-to-end through the deny/allow outcomes.
 */

async function send(page: Page, message: string): Promise<void> {
  const input = page.getByRole('textbox');
  await input.fill(message);
  await input.press('Enter');
}

test.describe('ms-governance-chat: MS-schema rules over the console', () => {
  test.beforeEach(async ({ page, baseURL }) => {
    await page.goto(`${baseURL}/atmosphere/console/`);
    await expect(page.getByText('Connected', { exact: false }).first()).toBeVisible({ timeout: 15_000 });
  });

  test('destructive SQL is denied with the verbatim MS message', async ({ page }) => {
    await send(page, 'please DROP TABLE users');
    // The MS rule's own message: field, no re-phrasing. A deny from
    // require-tenant-id here means the demo identity stamp regressed; an
    // ADMIT means the YAML layer was clobbered off the policy chain.
    await expect(page.getByText(
        "Denied by policy 'ms-customer-service-demo': Destructive SQL statements are not permitted in this chat."))
        .toBeVisible({ timeout: 15_000 });
  });

  test('legal language is routed to a human, SSNs are refused', async ({ page }) => {
    await send(page, "I will sue us if this isn't fixed");
    await expect(page.getByText('Legal-language queries are routed to a human agent'))
        .toBeVisible({ timeout: 15_000 });

    await send(page, 'my SSN is 123-45-6789');
    await expect(page.getByText('US Social Security number'))
        .toBeVisible({ timeout: 15_000 });
  });

  test('a benign question is admitted (defaults.action: allow)', async ({ page }) => {
    await send(page, 'hello, what services do you offer?');
    await expect(page.getByText('Thanks for contacting Example Corp support'))
        .toBeVisible({ timeout: 15_000 });
  });
});
