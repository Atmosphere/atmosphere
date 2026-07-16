import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-ai-classroom']);
});

test.afterAll(async () => {
  await server?.stop();
});

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

  // Known issue: browser console WebSocket never connects in CI
  test.skip('student sends question and sees streaming response', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('pick-math').click();

    await page.getByTestId('chat-input').fill('What is a prime number?');
    await page.getByTestId('chat-send').click();

    await expect(page.getByText('What is a prime number?')).toBeVisible();

    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  // Known issue: browser console WebSocket never connects in CI
  test.skip('code room shows a response', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('pick-code').click();

    await page.getByTestId('chat-input').fill('How do I write clean code?');
    await page.getByTestId('chat-send').click();

    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  // Known issue: browser console WebSocket never connects in CI
  test.skip('science room shows a response', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('pick-science').click();

    await page.getByTestId('chat-input').fill('What is photosynthesis?');
    await page.getByTestId('chat-send').click();

    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  test('send button is disabled when input is empty', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('pick-math').click();
    await expect(page.getByTestId('chat-send')).toBeDisabled();
  });

  // Known issue: browser console WebSocket never connects in CI
  test.skip('input clears after sending', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('pick-math').click();

    await page.getByTestId('chat-input').fill('Test message');
    await page.getByTestId('chat-send').click();
    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });
});
