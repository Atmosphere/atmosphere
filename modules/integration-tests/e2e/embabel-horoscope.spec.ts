import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-embabel-horoscope']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Embabel Horoscope', () => {
  test('page loads with AI chat layout', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
    await expect(page.getByTestId('chat-send')).toBeVisible();
  });

  test('generates horoscope for Leo', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill("What's my horoscope for Leo?");
    await page.getByTestId('chat-send').click();

    // Should receive a response (demo or real API)
    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  test('generates horoscope for Pisces', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Horoscope for Pisces today');
    await page.getByTestId('chat-send').click();

    // Should receive a response (demo or real API)
    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  test('responds to general fortune request', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Hello, tell me my fortune');
    await page.getByTestId('chat-send').click();

    // Should receive a response (demo or real API)
    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  test('send button is disabled when input is empty', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-send')).toBeDisabled();
  });

  test('input clears after sending', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('Horoscope for Gemini');
    await page.getByTestId('chat-send').click();
    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });

  test('receives response for Aries horoscope', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('Horoscope for Aries');
    await page.getByTestId('chat-send').click();

    // Should receive a response (demo or real API)
    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  test('multi-turn: second horoscope request works', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    // First request
    await page.getByTestId('chat-input').fill('Horoscope for Aries');
    await page.getByTestId('chat-send').click();
    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });

    // Wait for streaming to finish
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 15_000 });

    // Second request in same session
    await page.getByTestId('chat-input').fill('Now for Scorpio');
    await page.getByTestId('chat-send').click();
    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });
});
