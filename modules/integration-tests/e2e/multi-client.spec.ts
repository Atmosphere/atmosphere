import { test, expect } from '@playwright/test';
import { ChatPage } from './helpers/chat-page';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Multi-Client Broadcast', () => {
  test('message from one client is received by another', async ({ browser }) => {
    // Open two independent browser contexts (simulates two users)
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    const page1 = await ctx1.newPage();
    const page2 = await ctx2.newPage();

    const alice = new ChatPage(page1);
    const bob = new ChatPage(page2);

    // Both connect
    await alice.goto(server.baseUrl);
    await bob.goto(server.baseUrl);
    await alice.waitForConnected();
    await bob.waitForConnected();

    // Both join
    await alice.joinAs('Alice');
    await bob.joinAs('Bob');

    // Wait for joins to propagate
    await page1.waitForTimeout(1000);

    // Alice sends a message
    await alice.sendMessage('Can you see this, Bob?');

    // Bob should receive it
    await bob.expectMessage('Can you see this, Bob?', { timeout: 10_000 });

    // Bob replies
    await bob.sendMessage('Yes I can, Alice!');

    // Alice should receive Bob's reply
    await alice.expectMessage('Yes I can, Alice!', { timeout: 10_000 });

    await ctx1.close();
    await ctx2.close();
  });

  test('broadcast shows correct author on receiving client', async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    const page1 = await ctx1.newPage();
    const page2 = await ctx2.newPage();

    const alice = new ChatPage(page1);
    const bob = new ChatPage(page2);

    await alice.goto(server.baseUrl);
    await bob.goto(server.baseUrl);
    await alice.waitForConnected();
    await bob.waitForConnected();

    await alice.joinAs('Alice');
    await bob.joinAs('Bob');
    await page1.waitForTimeout(1000);

    await alice.sendMessage('Hello everyone!');

    // On Bob's screen, the message should show Alice as the author
    await bob.expectMessageFrom('Alice', 'Hello everyone!');

    await ctx1.close();
    await ctx2.close();
  });
});
