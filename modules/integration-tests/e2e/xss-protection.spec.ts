import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  // Use ai-chat sample (not spring-boot-chat) because the console sends raw text
  // and the @ManagedService chat handler expects {author,message} JSON via JacksonDecoder.
  // The ai-chat sample's @AiEndpoint accepts raw text prompts natively.
  server = await startSample(SAMPLES['spring-boot-ai-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

const CONSOLE_PATH = '/atmosphere/console/';

/** Navigate to the console and wait for the WebSocket to connect. */
async function openConsole(page: import('@playwright/test').Page, baseUrl: string) {
  await page.goto(baseUrl + CONSOLE_PATH);
  await expect(page.getByTestId('chat-layout')).toBeVisible();
  await expect(page.getByTestId('status-label')).toHaveText('Connected', { timeout: 15_000 });
}

/** Type a message into the console textarea and press Enter to send. */
async function sendMessage(page: import('@playwright/test').Page, text: string) {
  const input = page.getByTestId('chat-input');
  await input.fill(text);
  await input.press('Enter');
}

test.describe('XSS Protection', () => {
  test('script tags in messages are not executed', async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    const sender = await ctx1.newPage();
    const receiver = await ctx2.newPage();

    await openConsole(sender, server.baseUrl);
    await openConsole(receiver, server.baseUrl);

    // Send XSS payload
    await sendMessage(sender, '<script>window.__xss=true</script>');
    await sender.waitForTimeout(2000);

    // Verify the payload text is visible but the script was NOT executed on either page
    const xssOnSender = await sender.evaluate(() => (window as any).__xss);
    expect(xssOnSender).toBeFalsy();

    const xssOnReceiver = await receiver.evaluate(() => (window as any).__xss);
    expect(xssOnReceiver).toBeFalsy();

    // Verify the message list contains the escaped text (rendered safely)
    await expect(sender.getByTestId('message-list')).toContainText('<script>', { timeout: 5_000 });

    await ctx1.close();
    await ctx2.close();
  });

  test('img onerror XSS payload is neutralized', async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    const sender = await ctx1.newPage();
    const receiver = await ctx2.newPage();

    await openConsole(sender, server.baseUrl);
    await openConsole(receiver, server.baseUrl);

    // Send img onerror payload
    await sendMessage(sender, '<img src=x onerror="window.__xss_img=true">');
    await sender.waitForTimeout(2000);

    const xssOnSender = await sender.evaluate(() => (window as any).__xss_img);
    expect(xssOnSender).toBeFalsy();

    const xssOnReceiver = await receiver.evaluate(() => (window as any).__xss_img);
    expect(xssOnReceiver).toBeFalsy();

    // The raw payload text should be visible (escaped, not interpreted as HTML)
    await expect(sender.getByTestId('message-list')).toContainText('onerror', { timeout: 5_000 });

    await ctx1.close();
    await ctx2.close();
  });

  test('event handler attributes in messages are neutralized', async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    const sender = await ctx1.newPage();
    const receiver = await ctx2.newPage();

    await openConsole(sender, server.baseUrl);
    await openConsole(receiver, server.baseUrl);

    // Send SVG onload payload
    await sendMessage(sender, '<svg onload="window.__xss_svg=true">');
    await sender.waitForTimeout(2000);

    const xssOnSender = await sender.evaluate(() => (window as any).__xss_svg);
    expect(xssOnSender).toBeFalsy();

    const xssOnReceiver = await receiver.evaluate(() => (window as any).__xss_svg);
    expect(xssOnReceiver).toBeFalsy();

    await ctx1.close();
    await ctx2.close();
  });

  test('javascript: URI payloads are not executable', async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();

    await openConsole(page, server.baseUrl);

    // Send a markdown link with javascript: URI
    await sendMessage(page, '[click me](javascript:window.__xss_uri=true)');
    await page.waitForTimeout(2000);

    const xssTriggered = await page.evaluate(() => (window as any).__xss_uri);
    expect(xssTriggered).toBeFalsy();

    // If marked renders a link, clicking it should not trigger the payload
    const link = page.getByTestId('message-list').locator('a[href*="javascript"]');
    if (await link.count() > 0) {
      await link.first().click({ force: true });
      await page.waitForTimeout(500);
      const xssAfterClick = await page.evaluate(() => (window as any).__xss_uri);
      expect(xssAfterClick).toBeFalsy();
    }

    await ctx.close();
  });

  test('message text containing HTML entities is displayed safely', async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();

    await openConsole(page, server.baseUrl);

    // Send a message with HTML-like content that should be displayed as text
    await sendMessage(page, 'Use <div> and <span> for layout');

    // The angle-bracket content should appear as visible text, not interpreted as DOM
    await expect(page.getByTestId('message-list')).toContainText('<div>', { timeout: 10_000 });
    await expect(page.getByTestId('message-list')).toContainText('<span>', { timeout: 5_000 });

    // Verify no actual <div> or <span> elements were injected into message content
    const injectedDivCount = await page.evaluate(() => {
      const list = document.querySelector('[data-testid="message-list"]');
      if (!list) return 0;
      // Count div/span elements inside message-content that are NOT part of the
      // console's own component structure (message-content renders via innerHTML)
      const contentDivs = list.querySelectorAll('.message-content div');
      return contentDivs.length;
    });
    expect(injectedDivCount).toBe(0);

    await ctx.close();
  });

  test('multiple XSS vectors do not set any window properties', async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();

    await openConsole(page, server.baseUrl);

    const payloads = [
      '<script>window.__xss_test_1=1</script>',
      '<img src=x onerror="window.__xss_test_2=1">',
      '<svg/onload="window.__xss_test_3=1">',
      '<body onload="window.__xss_test_4=1">',
      '<iframe src="javascript:window.__xss_test_5=1">',
      '<details open ontoggle="window.__xss_test_6=1">',
    ];

    for (const payload of payloads) {
      await sendMessage(page, payload);
    }
    await page.waitForTimeout(3000);

    // Verify none of the XSS payloads executed
    const results = await page.evaluate(() => {
      const w = window as any;
      return {
        test1: w.__xss_test_1,
        test2: w.__xss_test_2,
        test3: w.__xss_test_3,
        test4: w.__xss_test_4,
        test5: w.__xss_test_5,
        test6: w.__xss_test_6,
      };
    });

    expect(results.test1).toBeFalsy();
    expect(results.test2).toBeFalsy();
    expect(results.test3).toBeFalsy();
    expect(results.test4).toBeFalsy();
    expect(results.test5).toBeFalsy();
    expect(results.test6).toBeFalsy();
  });
});
