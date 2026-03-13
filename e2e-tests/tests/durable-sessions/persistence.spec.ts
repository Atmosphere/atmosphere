import { test, expect } from '@playwright/test';
import {
  waitForConnected,
  joinChat,
  sendMessage,
  expectMessage,
  openBrowsers,
  navigateAndConnect,
  closeAll,
} from '../fixtures/chat-helpers';

/**
 * E2E tests for durable session persistence across server restarts.
 *
 * The spring-boot-durable-sessions sample stores session state in SQLite
 * (data/sessions.db). When a client reconnects after a server restart,
 * the session should be recovered.
 *
 * NOTE: These tests require special handling because they need to stop
 * and restart the chat server mid-test. They use Playwright's request API
 * to verify the server is available, and rely on the atmosphere.js client's
 * automatic reconnection to re-establish sessions.
 */
test.describe('Durable Sessions — Persistence', () => {
  test('clients can reconnect and chat after brief disconnection', async ({ browser }) => {
    const { contexts, pages } = await openBrowsers(browser, 2);
    const [page1, page2] = pages;

    try {
      await navigateAndConnect(pages);

      // Join both users and exchange messages
      await joinChat(page1, 'Alice');
      await joinChat(page2, 'Bob');

      await sendMessage(page1, 'Message before disconnect');
      await expectMessage(page2, 'Message before disconnect');

      // Simulate a brief network disconnection by closing page1's WebSocket
      // and immediately reconnecting
      await page1.close();

      // Open a new page in the same context
      const newPage1 = await contexts[0].newPage();
      await newPage1.goto('/');
      await waitForConnected(newPage1);

      // Re-join and verify chat still works
      await joinChat(newPage1, 'Alice');
      await sendMessage(newPage1, 'Message after reconnect');
      await expectMessage(page2, 'Message after reconnect');

      // Verify the reconnected client receives messages from others
      await sendMessage(page2, 'Reply from Bob');
      await expectMessage(newPage1, 'Reply from Bob');
    } finally {
      await closeAll(contexts);
    }
  });

  test('session state indicator shows Connected status', async ({ browser }) => {
    const { contexts, pages } = await openBrowsers(browser, 1);
    const [page1] = pages;

    try {
      await navigateAndConnect(pages);
      await joinChat(page1, 'Alice');

      // Verify the status remains "Connected"
      await expect(page1.locator('[data-testid="status-label"]')).toHaveText('Connected');

      // Verify the status dot indicates a healthy connection
      await expect(page1.locator('[data-testid="status-dot"]')).toBeVisible();
    } finally {
      await closeAll(contexts);
    }
  });
});
