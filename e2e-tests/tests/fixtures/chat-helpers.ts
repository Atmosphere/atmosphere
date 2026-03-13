import { expect, type Browser, type BrowserContext, type Page } from '@playwright/test';

/**
 * Shared helper functions for Atmosphere chat E2E tests.
 *
 * These work with any sample that uses the atmosphere.js/chat React components,
 * which all share the same data-testid attributes:
 *   - chat-layout:    top-level container
 *   - status-label:   connection state text ("Connected", "Connecting...", etc.)
 *   - chat-input:     text input field
 *   - chat-send:      send button
 *   - message-list:   scrollable message container
 *   - message-bubble: individual message bubble
 *   - message-author: author name span inside a bubble
 */

/** Wait for the chat UI to be fully connected and ready to send messages. */
export async function waitForConnected(page: Page): Promise<void> {
  await page.waitForSelector('[data-testid="chat-layout"]');
  await expect(page.locator('[data-testid="status-label"]')).toHaveText('Connected', {
    timeout: 15_000,
  });
  // The input should be enabled once connected
  await expect(page.locator('[data-testid="chat-input"]')).toBeEnabled();
}

/** Join the chat by entering a username (the first message sets the name). */
export async function joinChat(page: Page, name: string): Promise<void> {
  const input = page.locator('[data-testid="chat-input"]');
  await input.fill(name);
  await page.locator('[data-testid="chat-send"]').click();
  // Wait for the "joined" message bubble to appear
  await expect(
    page.locator('[data-testid="message-bubble"]', { hasText: `${name} has joined!` }),
  ).toBeVisible({ timeout: 10_000 });
}

/** Send a chat message (user must already be joined). */
export async function sendMessage(page: Page, text: string): Promise<void> {
  const input = page.locator('[data-testid="chat-input"]');
  await input.fill(text);
  await page.locator('[data-testid="chat-send"]').click();
}

/** Assert that a message bubble with the given text is visible on the page. */
export async function expectMessage(page: Page, text: string, timeout = 10_000): Promise<void> {
  await expect(
    page.locator('[data-testid="message-bubble"]', { hasText: text }),
  ).toBeVisible({ timeout });
}

/** Create N browser contexts, each with one page. */
export async function openBrowsers(
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
export async function navigateAndConnect(pages: Page[]): Promise<void> {
  await Promise.all(pages.map((page) => page.goto('/')));
  await Promise.all(pages.map((page) => waitForConnected(page)));
}

/** Close all browser contexts. */
export async function closeAll(contexts: BrowserContext[]): Promise<void> {
  await Promise.all(contexts.map((ctx) => ctx.close()));
}
