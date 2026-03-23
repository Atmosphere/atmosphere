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
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });
    await expect(page.getByTestId('chat-send')).toBeVisible();
  });

  test('generates horoscope for Leo with celestial events', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });

    await page.getByTestId('chat-input').fill("What's my horoscope for Leo?");
    await page.getByTestId('chat-send').click();

    // Demo response includes the zodiac sign and horoscope content
    await expect(page.getByText('Leo', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('Horoscope', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('generates horoscope for Pisces', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });

    await page.getByTestId('chat-input').fill('Horoscope for Pisces today');
    await page.getByTestId('chat-send').click();

    // Demo response includes Pisces-specific content
    await expect(page.getByText('Pisces', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('Creativity', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('asks for zodiac sign when none provided', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });

    await page.getByTestId('chat-input').fill('Hello, tell me my fortune');
    await page.getByTestId('chat-send').click();

    // Demo response asks user to specify a zodiac sign
    await expect(page.getByText('zodiac sign', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
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

  test('shows progress steps during horoscope generation', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('Horoscope for Aries');
    await page.getByTestId('chat-send').click();

    // Progress messages appear during multi-step generation
    await expect(page.getByText('Extracting zodiac sign', { exact: false }))
      .toBeVisible({ timeout: 15_000 });
  });

  test('multi-turn: second horoscope request works', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });

    // First request
    await page.getByTestId('chat-input').fill('Horoscope for Aries');
    await page.getByTestId('chat-send').click();
    await expect(page.getByText('Aries', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });

    // Wait for completion
    await page.waitForTimeout(5000);

    // Second request in same session
    await page.getByTestId('chat-input').fill('Now for Scorpio');
    await page.getByTestId('chat-send').click();
    await expect(page.getByText('Scorpio', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });
});
