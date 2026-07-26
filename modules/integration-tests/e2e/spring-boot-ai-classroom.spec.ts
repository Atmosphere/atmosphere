import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-ai-classroom']);
});

test.afterAll(async () => {
  await server?.stop();
});

/**
 * Join a classroom room and wait for its WebSocket to report Connected. The
 * sample is not auth-gated; the picker click swaps the console's active
 * endpoint, so the connect must be awaited AFTER the room is chosen.
 */
async function joinRoom(page: import('@playwright/test').Page, room: string) {
  await page.goto(server.baseUrl + '/atmosphere/console/');
  await page.getByTestId(`pick-${room}`).click();
  await expect(page.getByTestId('chat-input')).toBeVisible();
  await expect(page.getByText('Connected')).toBeVisible({ timeout: 30_000 });
}

/** Send a prompt into the joined room and wait for the assistant bubble. */
async function ask(page: import('@playwright/test').Page, prompt: string) {
  await page.getByTestId('chat-input').fill(prompt);
  await page.getByTestId('chat-send').click();
  await expect(page.locator('.message--user').last()).toContainText(prompt, { timeout: 10_000 });
  const assistant = page.locator('.message--assistant').last();
  await expect(assistant).toBeVisible({ timeout: 30_000 });
  await expect(assistant).not.toBeEmpty();
}

test.describe('Spring Boot AI Classroom', () => {
  test('page loads with room selector', async ({ page }) => {
    // The Console renders its endpoint picker from the sample's
    // console-endpoints config (Math/Code/Science classroom rooms).
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('endpoint-picker')).toBeVisible();
    await expect(page.getByTestId('pick-math')).toBeVisible();
  });

  test('joining math room shows classroom layout', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('pick-math').click();

    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
  });

  test('student question in the math room gets an answer', async ({ page }) => {
    await joinRoom(page, 'math');
    await ask(page, 'What is a prime number?');
  });

  test('code room answers on its own endpoint', async ({ page }) => {
    await joinRoom(page, 'code');
    await ask(page, 'How do I write clean code?');
  });

  test('science room answers on its own endpoint', async ({ page }) => {
    await joinRoom(page, 'science');
    await ask(page, 'What is photosynthesis?');
  });

  test('send button is disabled when input is empty', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('pick-math').click();
    await expect(page.getByTestId('chat-send')).toBeDisabled();
  });

  test('input clears after sending', async ({ page }) => {
    await joinRoom(page, 'math');

    await page.getByTestId('chat-input').fill('Test message');
    await page.getByTestId('chat-send').click();
    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });
});
