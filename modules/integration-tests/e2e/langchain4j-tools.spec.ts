import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-langchain4j-tools']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('LangChain4j Tools', () => {
  test('page loads with AI chat layout', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
    await expect(page.getByTestId('chat-send')).toBeVisible();
  });

  test('tool call simulation: weather query shows city and weather data', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('What is the weather in Paris?');
    await page.getByTestId('chat-send').click();

    // Demo response includes the tool name and city
    await expect(page.getByText('weather', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('Paris', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('tool call simulation: time query shows city time', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('What time is it in Tokyo?');
    await page.getByTestId('chat-send').click();

    // Demo response mentions Tokyo and the cityTime tool
    await expect(page.getByText('Tokyo', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('cityTime', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('greeting message lists available tools', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Hello!');
    await page.getByTestId('chat-send').click();

    // Demo greeting mentions LangChain4j and available capabilities
    await expect(page.getByText('LangChain4j', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('multi-turn conversation works within same session', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    // First message
    await page.getByTestId('chat-input').fill('What time is it in London?');
    await page.getByTestId('chat-send').click();
    await expect(page.getByText('London', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });

    // Wait for first response to complete
    await page.waitForTimeout(3000);

    // Second message in same session
    await page.getByTestId('chat-input').fill('What is the weather in Sydney?');
    await page.getByTestId('chat-send').click();
    await expect(page.getByText('Sydney', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });
});
