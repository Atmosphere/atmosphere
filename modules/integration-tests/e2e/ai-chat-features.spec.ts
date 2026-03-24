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

  // Known issue: spring-boot-ai-chat browser console WebSocket never connects in CI — skip
  test.skip('conversation memory: server remembers across turns in same session', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    // First message
    await page.getByTestId('chat-input').fill('Hello');
    await page.getByTestId('chat-send').click();
    await expect(page.locator('[class*="assistant"], [class*="message"]').first())
      .not.toBeEmpty({ timeout: 30_000 });

    // Wait for streaming to complete
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });

    // Second message
    await page.getByTestId('chat-input').fill('What is Atmosphere?');
    await page.getByTestId('chat-send').click();

    // Wait for streaming to complete
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });

    // Third message — verify conversation is still flowing
    await page.getByTestId('chat-input').fill('Tell me more');
    await page.getByTestId('chat-send').click();

    // Wait for response
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });

    // All three user messages should be visible in the chat
    await expect(page.getByText('Hello', { exact: true })).toBeVisible();
    await expect(page.getByText('What is Atmosphere?')).toBeVisible();
    await expect(page.getByText('Tell me more')).toBeVisible();
  });

  // Known issue: spring-boot-ai-chat browser console WebSocket never connects in CI — skip
  test.skip('two independent clients maintain separate conversations', async ({ browser }) => {
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
    await expect(page1.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });

    // Client 2 sends a different message
    await page2.getByTestId('chat-input').fill('Hello from client 2');
    await page2.getByTestId('chat-send').click();
    await expect(page2.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });

    // Each client should see only their own messages
    await expect(page1.getByText('Hello from client 1')).toBeVisible();
    await expect(page2.getByText('Hello from client 2')).toBeVisible();

    await ctx1.close();
    await ctx2.close();
  });
});
