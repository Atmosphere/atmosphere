import { test, expect, type Page } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

/**
 * Helper: navigate to the Atmosphere AI Console served by spring-boot-chat,
 * wait for the layout to render, then wait for the WebSocket to report
 * "Connected".
 */
async function openConsole(page: Page): Promise<void> {
  await page.goto(server.baseUrl + '/atmosphere/console/');
  await page.getByTestId('chat-layout').waitFor({ state: 'visible' });
  await expect(page.getByText('Connected')).toBeVisible({ timeout: 15_000 });
}

/**
 * Helper: type a message into the console textarea and press Enter to send.
 */
async function sendMessage(page: Page, text: string): Promise<void> {
  const input = page.getByTestId('chat-input');
  await input.fill(text);
  await input.press('Enter');
}

/**
 * Helper: assert that a given text appears inside the messages area.
 */
async function expectMessage(page: Page, text: string, timeout = 10_000): Promise<void> {
  await expect(page.getByTestId('message-list')).toContainText(text, { timeout });
}

test.describe('Multi-Client Broadcast', () => {
  test('message from one client is received by another', async ({ browser }) => {
    // Open two independent browser contexts (simulates two users)
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    const page1 = await ctx1.newPage();
    const page2 = await ctx2.newPage();

    // Both connect to the console
    await openConsole(page1);
    await openConsole(page2);

    // Allow connections to stabilize
    await page1.waitForTimeout(1000);

    // Client 1 sends a message
    await sendMessage(page1, 'Can you see this?');

    // Client 2 should receive it
    await expectMessage(page2, 'Can you see this?');

    // Client 2 replies
    await sendMessage(page2, 'Yes I can!');

    // Client 1 should receive the reply
    await expectMessage(page1, 'Yes I can!');

    await ctx1.close();
    await ctx2.close();
  });

  test('both clients see their own sent messages', async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    const page1 = await ctx1.newPage();
    const page2 = await ctx2.newPage();

    await openConsole(page1);
    await openConsole(page2);

    // Allow connections to stabilize
    await page1.waitForTimeout(1000);

    // Client 1 sends a message
    await sendMessage(page1, 'Hello everyone!');

    // Client 1 should see its own message (added locally by the console)
    await expectMessage(page1, 'Hello everyone!');

    // Client 2 should also receive the broadcast
    await expectMessage(page2, 'Hello everyone!');

    await ctx1.close();
    await ctx2.close();
  });
});
