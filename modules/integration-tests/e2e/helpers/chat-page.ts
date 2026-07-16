import type { Page, Locator } from '@playwright/test';
import { expect } from '@playwright/test';

/**
 * Page Object Model for the Atmosphere Console chat surface — the UI every
 * sample serves at /atmosphere/console/ (ChatContainer, ChatInput,
 * ChatMessage, ConnectionStatus share these test ids).
 */
export class ChatPage {
  readonly input: Locator;
  readonly sendButton: Locator;
  readonly messageList: Locator;
  readonly statusLabel: Locator;

  constructor(readonly page: Page) {
    this.input = page.getByTestId('chat-input');
    this.sendButton = page.getByTestId('chat-send');
    this.messageList = page.getByTestId('message-list');
    this.statusLabel = page.getByTestId('status-label');
  }

  /** Navigate to the sample's Console and wait for the chat layout to render. */
  async goto(url: string): Promise<void> {
    // Land on the Console directly — the sample root is a meta-refresh
    // redirect to it, and racing that navigation flakes waitFor/evaluate.
    await this.page.goto(url.replace(/\/$/, '') + '/atmosphere/console/');
    await this.page.getByTestId('chat-layout').waitFor({ state: 'visible' });
  }

  /** Wait for the WebSocket connection to be established. */
  async waitForConnected(): Promise<void> {
    await expect(this.statusLabel).toHaveText(/^Connected/, { timeout: 15_000 });
  }

  /** Type text into the chat input and press Enter to send. */
  async send(text: string): Promise<void> {
    await this.input.fill(text);
    await this.sendButton.click();
  }

  /** Join a chat room by entering a name (first message = join). */
  async joinAs(name: string): Promise<void> {
    await this.send(name);
    // Wait for the join to be acknowledged — a system message or bubble should appear
    await this.page.waitForTimeout(1000);
  }

  /** Send a chat message (after already joined). */
  async sendMessage(text: string): Promise<void> {
    await this.send(text);
  }

  /** Get all message bubbles (non-system messages). */
  get messageBubbles(): Locator {
    return this.page.getByTestId('message-bubble');
  }

  /** Get count of visible message bubbles. */
  async messageCount(): Promise<number> {
    return this.messageBubbles.count();
  }

  /** Assert that a message with the given text appears in the chat. */
  async expectMessage(text: string, options?: { timeout?: number }): Promise<void> {
    await expect(this.messageList).toContainText(text, {
      timeout: options?.timeout ?? 10_000,
    });
  }

  /** Assert that a message from a specific author appears. */
  async expectMessageFrom(author: string, text: string, options?: { timeout?: number }): Promise<void> {
    // Find a bubble that contains both the author and the message text
    const bubble = this.messageBubbles.filter({ hasText: author }).filter({ hasText: text });
    await expect(bubble.first()).toBeVisible({ timeout: options?.timeout ?? 10_000 });
  }

  /** Assert the status pill shows a specific state (prefix match — the
   *  Console pill names the live wire, e.g. "Connected · websocket"). */
  async expectStatus(text: string): Promise<void> {
    await expect(this.statusLabel).toHaveText(new RegExp('^' + text));
  }

  /** Get the text content of the last message bubble. */
  async lastMessageText(): Promise<string | null> {
    const count = await this.messageCount();
    if (count === 0) return null;
    return this.messageBubbles.nth(count - 1).innerText();
  }

  /** Wait for at least N message bubbles to appear. */
  async waitForMessages(n: number, options?: { timeout?: number }): Promise<void> {
    await expect(this.messageBubbles).toHaveCount(n, {
      timeout: options?.timeout ?? 10_000,
    });
  }
}
