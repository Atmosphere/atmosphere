import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-ai-tools']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('@AiTool Pipeline', () => {
  test('page loads with AI chat layout', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });
    await expect(page.getByTestId('chat-send')).toBeVisible();
  });

  test('tool call: time query triggers get_city_time', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });

    await page.getByTestId('chat-input').fill('What time is it in Tokyo?');
    await page.getByTestId('chat-send').click();

    await expect(page.getByText('get_city_time', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('tokyo', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('tool call: weather query triggers get_weather', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });

    await page.getByTestId('chat-input').fill('What is the weather in Paris?');
    await page.getByTestId('chat-send').click();

    await expect(page.getByText('get_weather', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('paris', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('tool call: temperature conversion triggers convert_temperature', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });

    await page.getByTestId('chat-input').fill('Convert 100F to Celsius');
    await page.getByTestId('chat-send').click();

    await expect(page.getByText('convert_temperature', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('greeting describes framework-agnostic capabilities', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });

    await page.getByTestId('chat-input').fill('Hello!');
    await page.getByTestId('chat-send').click();

    await expect(page.getByText('Atmosphere', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('multi-turn conversation works within same session', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });

    // First message
    await page.getByTestId('chat-input').fill('What time is it in London?');
    await page.getByTestId('chat-send').click();
    await expect(page.getByText('london', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });

    // Wait for first response to complete
    await page.waitForTimeout(3000);

    // Second message in same session
    await page.getByTestId('chat-input').fill('What is the weather in Sydney?');
    await page.getByTestId('chat-send').click();
    await expect(page.getByText('sydney', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('tool activity panel shows tool-start and tool-result events', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });

    await page.getByTestId('chat-input').fill('What is the weather in Tokyo?');
    await page.getByTestId('chat-send').click();

    // The ToolActivity component should appear with tool events
    await expect(page.getByTestId('tool-activity'))
      .toBeVisible({ timeout: 30_000 });
    // Should show the tool name
    await expect(page.getByText('get_weather', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
    // Text response should also appear
    await expect(page.getByText('tokyo', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('three concurrent clients all receive broadcast responses', async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    const ctx3 = await browser.newContext();
    const page1 = await ctx1.newPage();
    const page2 = await ctx2.newPage();
    const page3 = await ctx3.newPage();

    // All three navigate to the same room
    await page1.goto(server.baseUrl + '/atmosphere/console/');
    await page2.goto(server.baseUrl + '/atmosphere/console/');
    await page3.goto(server.baseUrl + '/atmosphere/console/');

    await expect(page1.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });
    await expect(page2.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });
    await expect(page3.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });

    // Client 1 sends a prompt — all clients in the room should see the response
    await page1.getByTestId('chat-input').fill('What time is it in Tokyo?');
    await page1.getByTestId('chat-send').click();

    // All three clients see the broadcast
    await expect(page1.getByText('get_city_time', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
    await expect(page2.getByText('get_city_time', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
    await expect(page3.getByText('get_city_time', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });

    await ctx1.close();
    await ctx2.close();
    await ctx3.close();
  });
});
