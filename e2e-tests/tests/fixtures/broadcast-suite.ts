import { test, expect } from '@playwright/test';
import {
  waitForConnected,
  joinChat,
  sendMessage,
  expectMessage,
  openBrowsers,
  navigateAndConnect,
  closeAll,
} from './chat-helpers';

/**
 * Options for configuring the broadcast test suite.
 */
export interface BroadcastSuiteOptions {
  /**
   * Returns the text the joining user sees in a message bubble after joining.
   * Defaults to `(name) => "${name} has joined!"` for simple chat samples.
   * For Room Protocol samples, use `() => "Joined room"` (the join_ack).
   */
  joinConfirmation?: (name: string) => string;
}

/**
 * Registers the standard broadcast test suite that validates core Atmosphere
 * chat functionality. Call this inside a test.describe() block.
 *
 * These tests work with any sample that uses the atmosphere.js/chat components
 * and the standard Chat.java handler (broadcast messages to all subscribers).
 *
 * Tests:
 *   1. Three browsers receive broadcast message
 *   2. Message ordering is preserved across browsers
 *   3. Disconnect and reconnect
 */
export function registerBroadcastTests(options?: BroadcastSuiteOptions): void {
  const joinConfirm = options?.joinConfirmation ?? ((name: string) => `${name} has joined!`);

  test('three browsers receive broadcast message', async ({ browser }) => {
    const { contexts, pages } = await openBrowsers(browser, 3);
    const [page1, page2, page3] = pages;

    try {
      await navigateAndConnect(pages);

      // Join all three users
      await joinChat(page1, 'Alice', joinConfirm('Alice'));
      await joinChat(page2, 'Bob', joinConfirm('Bob'));
      await joinChat(page3, 'Charlie', joinConfirm('Charlie'));

      // Send a message from browser 1
      await sendMessage(page1, 'Hello from Alice!');

      // Verify all 3 browsers display the message
      await expectMessage(page1, 'Hello from Alice!');
      await expectMessage(page2, 'Hello from Alice!');
      await expectMessage(page3, 'Hello from Alice!');

      // Verify the author is shown correctly on other browsers
      const authorOnPage2 = page2
        .locator('[data-testid="message-bubble"]', {
          hasText: 'Hello from Alice!',
        })
        .locator('[data-testid="message-author"]');
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
      await joinChat(page1, 'Alice', joinConfirm('Alice'));
      await joinChat(page2, 'Bob', joinConfirm('Bob'));
      await joinChat(page3, 'Charlie', joinConfirm('Charlie'));

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

      // Verify ordering is correct on each browser
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
      await joinChat(page1, 'Alice', joinConfirm('Alice'));
      await joinChat(page2, 'Bob', joinConfirm('Bob'));

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
      await joinChat(newPage1, 'Alice', joinConfirm('Alice'));

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
}
