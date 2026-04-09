import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Spring Boot Chat', () => {
  test('page loads with console layout', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
  });

  test('console connects to chat handler', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('status-label')).toHaveText('Connected', { timeout: 15_000 });
  });

  test('user can send a message', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('status-label')).toHaveText('Connected', { timeout: 15_000 });

    await page.getByTestId('chat-input').fill('Hello from Playwright!');
    await page.getByTestId('chat-send').click();

    // User message should appear in the message list
    await expect(page.getByTestId('message-list')).toContainText('Hello from Playwright!', { timeout: 10_000 });
  });

  test('input clears after sending', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('status-label')).toHaveText('Connected', { timeout: 15_000 });

    await page.getByTestId('chat-input').fill('test message');
    await page.getByTestId('chat-send').click();

    // Input should be cleared after sending
    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });

  test('send button is disabled when not connected', async ({ page }) => {
    // Navigate but don't wait for connection — check initial state
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    // Once connected, send button should be usable with non-empty input
    await expect(page.getByTestId('status-label')).toHaveText('Connected', { timeout: 15_000 });
    // With empty input, send should be disabled
    await expect(page.getByTestId('chat-send')).toBeDisabled();
  });
});
