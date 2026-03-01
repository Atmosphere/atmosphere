import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-adk-tools']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('ADK Tools', () => {
  test('page loads with AI chat layout', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
    await expect(page.getByTestId('chat-send')).toBeVisible();
  });

  test('user can ask about time and receive tool call response', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('What time is it in London?');
    await page.getByTestId('chat-send').click();

    // Demo mode shows the tool call with city name
    await expect(page.getByText('getCurrentTime', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('london', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('user can ask about weather', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('What is the weather in Tokyo?');
    await page.getByTestId('chat-send').click();

    // Demo mode shows the weather tool call
    await expect(page.getByText('getWeather', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('tokyo', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('token budget information is available', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Tell me about token budgets');
    await page.getByTestId('chat-send').click();

    // Demo response describes the token budget system
    await expect(page.getByText('10,000', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('greeting message describes ADK capabilities', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Hello!');
    await page.getByTestId('chat-send').click();

    await expect(page.getByText('ADK', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });
});
