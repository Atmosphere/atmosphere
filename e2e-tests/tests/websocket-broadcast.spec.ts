import { test, expect, type Browser, type BrowserContext, type Page } from '@playwright/test';

/**
 * E2E tests for Atmosphere WebSocket broadcast using the embedded Jetty chat sample.
 *
 * The chat app flow:
 *   1. First input sets the user's name and sends a "{name} has joined!" message.
 *   2. Subsequent inputs are broadcast as chat messages to all connected clients.
 *
 * UI selectors (data-testid attributes from atmosphere.js/chat components):
 *   - chat-layout:    top-level container
 *   - status-label:   connection state text ("Connected", "Connecting...", etc.)
 *   - chat-input:     text input field
 *   - chat-send:      send button
 *   - message-list:   scrollable message container
 *   - message-bubble: individual message bubble
 *   - message-author: author name span inside a bubble
 */

/** Wait for the chat UI to be fully connected and ready to send messages. */
async function waitForConnected(page: Page): Promise<void> {
  await page.waitForSelector('[data-testid="chat-layout"]');
  await expect(page.locator('[data-testid="status-label"]')).toHaveText('Connected', {
    timeout: 15_000,
  });
  // The input should be enabled once connected
  await expect(page.locator('[data-testid="chat-input"]')).toBeEnabled();
}

/** Join the chat by entering a username (the first message sets the name). */
async function joinChat(page: Page, name: string): Promise<void> {
  const input = page.locator('[data-testid="chat-input"]');
  await input.fill(name);
  await page.locator('[data-testid="chat-send"]').click();
  // Wait for the "joined" message bubble to appear
  await expect(
    page.locator('[data-testid="message-bubble"]', { hasText: `${name} has joined!` }),
  ).toBeVisible({ timeout: 10_000 });
}

/** Send a chat message (user must already be joined). */
async function sendMessage(page: Page, text: string): Promise<void> {
  const input = page.locator('[data-testid="chat-input"]');
  await input.fill(text);
  await page.locator('[data-testid="chat-send"]').click();
}

/** Assert that a message bubble with the given text is visible on the page. */
async function expectMessage(page: Page, text: string, timeout = 10_000): Promise<void> {
  await expect(
    page.locator('[data-testid="message-bubble"]', { hasText: text }),
  ).toBeVisible({ timeout });
}

/** Create N browser contexts, each with one page navigated to the chat app. */
async function openBrowsers(
  browser: Browser,
  count: number,
): Promise<{ contexts: BrowserContext[]; pages: Page[] }> {
  const contexts: BrowserContext[] = [];
  const pages: Page[] = [];

  for (let i = 0; i < count; i++) {
    const context = await browser.newContext();
    const page = await context.newPage();
    contexts.push(context);
    pages.push(page);
  }

  return { contexts, pages };
}

/** Navigate all pages to the chat and wait for WebSocket connection. */
async function navigateAndConnect(pages: Page[]): Promise<void> {
  // Navigate all pages in parallel
  await Promise.all(pages.map((page) => page.goto('/')));
  // Wait for all to be connected
  await Promise.all(pages.map((page) => waitForConnected(page)));
}

/** Close all browser contexts. */
async function closeAll(contexts: BrowserContext[]): Promise<void> {
  await Promise.all(contexts.map((ctx) => ctx.close()));
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('WebSocket Broadcast', () => {
  test('three browsers receive broadcast message', async ({ browser }) => {
    const { contexts, pages } = await openBrowsers(browser, 3);
    const [page1, page2, page3] = pages;

    try {
      await navigateAndConnect(pages);

      // Join all three users
      await joinChat(page1, 'Alice');
      await joinChat(page2, 'Bob');
      await joinChat(page3, 'Charlie');

      // Send a message from browser 1
      await sendMessage(page1, 'Hello from Alice!');

      // Verify all 3 browsers display the message
      await expectMessage(page1, 'Hello from Alice!');
      await expectMessage(page2, 'Hello from Alice!');
      await expectMessage(page3, 'Hello from Alice!');

      // Verify the author is shown correctly on other browsers
      const authorOnPage2 = page2.locator('[data-testid="message-bubble"]', {
        hasText: 'Hello from Alice!',
      }).locator('[data-testid="message-author"]');
      await expect(authorOnPage2).toHaveText('Alice');
    } finally {
      await closeAll(contexts);
    }
  });

  test('message ordering is preserved across browsers', async ({ browser }) => {
    const { contexts, pages } = await openBrowsers(browser, 3);
    const [page1, page2, page3] = pages;

    try {
      await navigateAndConnect(pages);

      // Join all three users
      await joinChat(page1, 'Alice');
      await joinChat(page2, 'Bob');
      await joinChat(page3, 'Charlie');

      // Send 3 messages rapidly from browser 1
      await sendMessage(page1, 'First message');
      await sendMessage(page1, 'Second message');
      await sendMessage(page1, 'Third message');

      // Wait for all messages to appear on all browsers
      for (const page of pages) {
        await expectMessage(page, 'First message');
        await expectMessage(page, 'Second message');
        await expectMessage(page, 'Third message');
      }

      // Verify ordering is correct on each browser.
      // Get all message bubbles that contain our test messages (skip "joined" messages).
      for (const page of pages) {
        const bubbles = page.locator('[data-testid="message-bubble"]');
        const allTexts = await bubbles.allTextContents();

        // Filter to only our sequenced messages
        const ordered = allTexts.filter(
          (t) =>
            t.includes('First message') ||
            t.includes('Second message') ||
            t.includes('Third message'),
        );

        // The three messages must appear in the correct relative order
        expect(ordered.length).toBe(3);
        expect(ordered[0]).toContain('First message');
        expect(ordered[1]).toContain('Second message');
        expect(ordered[2]).toContain('Third message');
      }
    } finally {
      await closeAll(contexts);
    }
  });

  test('disconnect and reconnect', async ({ browser }) => {
    const { contexts, pages } = await openBrowsers(browser, 2);
    const [page1, page2] = pages;
    const [context1] = contexts;

    try {
      await navigateAndConnect(pages);

      // Join both users
      await joinChat(page1, 'Alice');
      await joinChat(page2, 'Bob');

      // Send a message from browser 1, verify browser 2 receives it
      await sendMessage(page1, 'Before disconnect');
      await expectMessage(page2, 'Before disconnect');

      // Close browser 1's page (simulates disconnect)
      await page1.close();

      // Open a new page in context 1 to simulate reconnect
      const newPage1 = await context1.newPage();
      await newPage1.goto('/');
      await waitForConnected(newPage1);

      // Re-join with the same name
      await joinChat(newPage1, 'Alice');

      // Send a message from browser 2
      await sendMessage(page2, 'After reconnect');

      // The reconnected browser 1 should receive the message
      await expectMessage(newPage1, 'After reconnect');

      // Verify browser 2 also has its own message
      await expectMessage(page2, 'After reconnect');
    } finally {
      await closeAll(contexts);
    }
  });
});
