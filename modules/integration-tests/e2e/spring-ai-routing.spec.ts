import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-spring-ai-routing']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Spring AI Routing', () => {
  test('page loads with AI chat layout', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
    await expect(page.getByTestId('chat-send')).toBeVisible();
  });

  test('code question is routed to code model', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Write a function to sort a list in Java');
    await page.getByTestId('chat-send').click();

    // Demo response shows routing to code-specialized model
    await expect(page.getByText('Routed to', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('code', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('creative prompt is routed to creative model', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Write me a short poem about the ocean');
    await page.getByTestId('chat-send').click();

    // Demo response mentions creative routing
    await expect(page.getByText('creative', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('math question is routed to reasoning model', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Solve x^2 + 3x - 4 = 0');
    await page.getByTestId('chat-send').click();

    // Demo response mentions reasoning model routing
    await expect(page.getByText('reasoning', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('general question uses default model', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Hello, how are you?');
    await page.getByTestId('chat-send').click();

    // Demo response shows default routing
    await expect(page.getByText('default', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });
});
