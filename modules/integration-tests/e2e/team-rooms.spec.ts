import { test, expect } from '@playwright/test';
import { ChatPage } from './helpers/chat-page';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-team-rooms']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Spring Boot Team Rooms', () => {
  test('the Console round-trips a line through the room', async ({ page }) => {
    // The Console is this sample's UI. The room used to reject the Console's
    // send (decoder failure on plain text, no text on the {author, message}
    // envelope) and reply {room, author, text, at} with no `message` field,
    // so nothing came back. The echo is the server's frame, rendered
    // author-prefixed ("<author>: <line>"); the sender's own bubble is not.
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();

    const line = `standup in five ${Date.now()}`;
    await chat.sendMessage(line);
    await expect(chat.messageBubbles.filter({ hasText: `: ${line}` }).first())
      .toBeVisible({ timeout: 10_000 });
  });
});
