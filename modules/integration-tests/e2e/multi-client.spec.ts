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

/**
 * spring-boot-chat's @ManagedService pairs a JacksonDecoder that expects
 * {author,message} JSON with the Console, which sends raw text prompts — so
 * a console-typed message is never decoded and never rebroadcast. The
 * cross-client *delivery* contract is therefore asserted at the protocol
 * level by WAsyncChatIntegrationTest (JUnit, samples/spring-boot-chat), which
 * speaks the encoded wire format.
 *
 * What only a browser can prove — and what this spec now actually runs, rather
 * than skipping wholesale — is that two independent contexts each establish
 * their own console session against one server, render their own sends, and
 * stay isolated from each other.
 */
test.describe('Multi-Client Console Sessions', () => {
  test('two independent contexts each connect to the same server', async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    try {
      const page1 = await ctx1.newPage();
      const page2 = await ctx2.newPage();

      // Both reach "Connected" — one server, two concurrent console sessions.
      await openConsole(page1);
      await openConsole(page2);

      await expect(page1.getByTestId('chat-layout')).toBeVisible();
      await expect(page2.getByTestId('chat-layout')).toBeVisible();
    } finally {
      await ctx1.close();
      await ctx2.close();
    }
  });

  test('each client renders its own send', async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    try {
      const page1 = await ctx1.newPage();
      const page2 = await ctx2.newPage();

      await openConsole(page1);
      await openConsole(page2);

      await sendMessage(page1, 'Hello from client 1');
      await sendMessage(page2, 'Hello from client 2');

      // Each console renders its own outbound message. Cross-client delivery
      // is deliberately NOT asserted here: whether the undecodable console
      // text reaches the other session depends on broadcaster timing rather
      // than on a contract this sample defines, so pinning it either way would
      // be asserting an accident. WAsyncChatIntegrationTest owns the delivery
      // contract, over the encoded wire format the decoder actually accepts.
      await expectMessage(page1, 'Hello from client 1');
      await expectMessage(page2, 'Hello from client 2');
    } finally {
      await ctx1.close();
      await ctx2.close();
    }
  });
});
