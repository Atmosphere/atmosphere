import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-ai-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Spring Boot AI Chat', () => {
  test('page loads with AI chat layout', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
  });

  test('send button is disabled when input is empty', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-send')).toBeDisabled();
  });

  // Auth-gated sample: console WebSocket blocked without token.
  // Full auth flow tested in auth-token.spec.ts (raw WebSocket with X-Atmosphere-Auth).
  test.skip('user receives a response after sending a message', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('Hello');
    await page.getByTestId('chat-send').click();

    await expect(page.getByTestId('message-list')).toContainText('demo mode', { timeout: 30_000 });
  });

  // Auth-gated: console can't connect without token
  test.skip('user can send a prompt and receive streaming response', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('What is Atmosphere?');
    await page.getByTestId('chat-send').click();

    await expect(page.getByTestId('message-list')).toContainText('Atmosphere', { timeout: 30_000 });
  });

  // Auth-gated: console can't connect without token
  test.skip('input clears after sending', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('Test message');
    await page.getByTestId('chat-send').click();

    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });

  // Auth-gated: console can't connect without token
  test.skip('multi-turn conversation preserves history', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');

    await page.getByTestId('chat-input').fill('Hello');
    await page.getByTestId('chat-send').click();
    await expect(page.getByTestId('message-list')).toContainText('demo mode', { timeout: 30_000 });

    await page.getByTestId('chat-input').fill('What is Atmosphere?');
    await page.getByTestId('chat-send').click();

    await expect(page.getByText('Hello', { exact: true })).toBeVisible();
    await expect(page.getByText('What is Atmosphere?')).toBeVisible();
  });
});
