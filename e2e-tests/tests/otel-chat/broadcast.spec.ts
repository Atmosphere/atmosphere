import { test, expect } from '@playwright/test';

/**
 * E2E tests for the OpenTelemetry Chat sample.
 *
 * This sample uses the spring-boot-chat frontend (Room Protocol) with a
 * simple @ManagedService echo backend + OTel tracing. The backend does not
 * implement Room Protocol join_ack, so we test basic connectivity and UI
 * rendering rather than the full broadcast suite.
 */
test.describe('OTel Chat — Basic Connectivity', () => {
  test('page loads with chat layout and connects', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
    await expect(page.getByTestId('status-label')).toHaveText('Connected', {
      timeout: 15_000,
    });
  });

  test('can type and send a message', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('status-label')).toHaveText('Connected', {
      timeout: 15_000,
    });

    await page.getByTestId('chat-input').fill('Hello OTel');
    await page.getByTestId('chat-send').click();
    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });
});
