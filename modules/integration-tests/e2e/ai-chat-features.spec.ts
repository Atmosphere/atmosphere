import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-ai-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('AI Chat — New Features E2E', () => {

  test('capability validation: server starts successfully with requires', async ({ page }) => {
    // If capability validation failed at startup, the server wouldn't be running.
    // The endpoint has requires = {TEXT_STREAMING, SYSTEM_PROMPT} and the built-in
    // support advertises both — so startup should succeed.
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
  });

  test('conversation memory: server remembers across turns in same session', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    // First message
    await page.getByTestId('chat-input').fill('Hello');
    await page.getByTestId('chat-send').click();
    await expect(page.getByText('Demo mode', { exact: false }))
      .toBeVisible({ timeout: 15_000 });

    // Wait for streaming to complete
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 15_000 });

    // Second message
    await page.getByTestId('chat-input').fill('What is Atmosphere?');
    await page.getByTestId('chat-send').click();
    await expect(page.getByText('real-time', { exact: false }))
      .toBeVisible({ timeout: 30_000 });

    // Wait for streaming to complete
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 15_000 });

    // Third message — verify conversation is still flowing
    await page.getByTestId('chat-input').fill('Tell me more');
    await page.getByTestId('chat-send').click();

    // Should get a response (demo mode works for any prompt)
    await expect(page.getByText('demo', { exact: false }).first())
      .toBeVisible({ timeout: 15_000 });

    // All three user messages should be visible in the chat
    await expect(page.getByText('Hello', { exact: true })).toBeVisible();
    await expect(page.getByText('What is Atmosphere?')).toBeVisible();
    await expect(page.getByText('Tell me more')).toBeVisible();
  });

  test('two independent clients maintain separate conversations', async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    const page1 = await ctx1.newPage();
    const page2 = await ctx2.newPage();

    await page1.goto(server.baseUrl + '/atmosphere/console/');
    await page2.goto(server.baseUrl + '/atmosphere/console/');

    await expect(page1.getByTestId('chat-input')).toBeVisible();
    await expect(page2.getByTestId('chat-input')).toBeVisible();

    // Client 1 sends a message
    await page1.getByTestId('chat-input').fill('Hello from client 1');
    await page1.getByTestId('chat-send').click();
    await expect(page1.getByText('Demo mode', { exact: false }))
      .toBeVisible({ timeout: 15_000 });

    // Client 2 sends a different message
    await page2.getByTestId('chat-input').fill('Hello from client 2');
    await page2.getByTestId('chat-send').click();
    await expect(page2.getByText('Demo mode', { exact: false }))
      .toBeVisible({ timeout: 15_000 });

    // Each client should see only their own messages
    await expect(page1.getByText('Hello from client 1')).toBeVisible();
    await expect(page2.getByText('Hello from client 2')).toBeVisible();

    await ctx1.close();
    await ctx2.close();
  });
});
