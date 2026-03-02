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

test.describe('XSS Protection', () => {
  test('script tags in messages are not executed', async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    const page1 = await ctx1.newPage();
    const page2 = await ctx2.newPage();

    const sender = new ChatPage(page1);
    const receiver = new ChatPage(page2);

    await sender.goto(server.baseUrl);
    await receiver.goto(server.baseUrl);
    await sender.waitForConnected();
    await receiver.waitForConnected();

    await sender.joinAs('Attacker');
    await receiver.joinAs('Victim');
    await page1.waitForTimeout(1000);

    // Send XSS payload
    await sender.sendMessage('<script>window.__xss=true</script>');
    await page2.waitForTimeout(2000);

    // Verify script was NOT executed on receiver
    const xssTriggered = await page2.evaluate(() => (window as any).__xss);
    expect(xssTriggered).toBeFalsy();

    await ctx1.close();
    await ctx2.close();
  });

  test('img onerror XSS payload is neutralized', async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    const page1 = await ctx1.newPage();
    const page2 = await ctx2.newPage();

    const sender = new ChatPage(page1);
    const receiver = new ChatPage(page2);

    await sender.goto(server.baseUrl);
    await receiver.goto(server.baseUrl);
    await sender.waitForConnected();
    await receiver.waitForConnected();

    await sender.joinAs('Attacker2');
    await receiver.joinAs('Victim2');
    await page1.waitForTimeout(1000);

    // Send img onerror payload
    await sender.sendMessage('<img src=x onerror="window.__xss2=true">');
    await page2.waitForTimeout(2000);

    const xssTriggered = await page2.evaluate(() => (window as any).__xss2);
    expect(xssTriggered).toBeFalsy();

    await ctx1.close();
    await ctx2.close();
  });

  test('event handler attributes in messages are neutralized', async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    const page1 = await ctx1.newPage();
    const page2 = await ctx2.newPage();

    const sender = new ChatPage(page1);
    const receiver = new ChatPage(page2);

    await sender.goto(server.baseUrl);
    await receiver.goto(server.baseUrl);
    await sender.waitForConnected();
    await receiver.waitForConnected();

    await sender.joinAs('Attacker3');
    await receiver.joinAs('Victim3');
    await page1.waitForTimeout(1000);

    // Send SVG onload payload
    await sender.sendMessage('<svg onload="window.__xss3=true">');
    await page2.waitForTimeout(2000);

    const xssTriggered = await page2.evaluate(() => (window as any).__xss3);
    expect(xssTriggered).toBeFalsy();

    await ctx1.close();
    await ctx2.close();
  });

  test('message text containing HTML entities is displayed safely', async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    const page1 = await ctx1.newPage();
    const page2 = await ctx2.newPage();

    const sender = new ChatPage(page1);
    const receiver = new ChatPage(page2);

    await sender.goto(server.baseUrl);
    await receiver.goto(server.baseUrl);
    await sender.waitForConnected();
    await receiver.waitForConnected();

    await sender.joinAs('HTMLUser');
    await receiver.joinAs('Reader');
    await page1.waitForTimeout(1000);

    // Send a message with HTML-like content that should be displayed as text
    await sender.sendMessage('Use <div> and <span> for layout');
    await receiver.expectMessage('<div>', { timeout: 10_000 });
    await receiver.expectMessage('<span>', { timeout: 5_000 });

    await ctx1.close();
    await ctx2.close();
  });
});
