import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-adk-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('ADK Chat', () => {
  test('page loads with AI chat layout', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });
    await expect(page.getByTestId('chat-send')).toBeVisible();
  });

  test('user can send hello and receive ADK agent response', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('Hello');
    await page.getByTestId('chat-send').click();

    await expect(page.getByText('Hello', { exact: true })).toBeVisible();

    // DemoEventProducer responds with text containing "ADK agent" for hello
    await expect(page.getByText('ADK agent', { exact: false }))
      .toBeVisible({ timeout: 15_000 });
  });

  test('user can send a prompt and receive streaming response', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });

    await page.getByTestId('chat-input').fill('Tell me about atmosphere');
    await page.getByTestId('chat-send').click();

    await expect(page.getByText('Tell me about atmosphere')).toBeVisible();

    // DemoEventProducer responds with text containing "real-time" for atmosphere
    await expect(page.getByText('real-time', { exact: false }))
      .toBeVisible({ timeout: 30_000 });
  });

  test('input clears after sending', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('Test message');
    await page.getByTestId('chat-send').click();

    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });

  test('send button is disabled when input is empty', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-send')).toBeDisabled();
  });

  test('multi-turn conversation preserves history', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');

    // First message
    await page.getByTestId('chat-input').fill('Hello');
    await page.getByTestId('chat-send').click();
    await expect(page.getByText('ADK agent', { exact: false }))
      .toBeVisible({ timeout: 15_000 });

    // Wait for streaming to finish
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 15_000 });

    // Second message
    await page.getByTestId('chat-input').fill('Tell me about atmosphere');
    await page.getByTestId('chat-send').click();
    await expect(page.getByText('real-time', { exact: false }))
      .toBeVisible({ timeout: 30_000 });

    // Both user messages should still be visible
    await expect(page.getByText('Hello', { exact: true })).toBeVisible();
    await expect(page.getByText('Tell me about atmosphere')).toBeVisible();
  });
});
