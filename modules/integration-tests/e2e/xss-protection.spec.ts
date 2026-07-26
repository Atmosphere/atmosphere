import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  // Use dentist-agent — no auth required, @Agent accepts raw text natively.
  // spring-boot-chat's @ManagedService expects {author,message} JSON,
  // spring-boot-ai-chat requires auth token.
  server = await startSample(SAMPLES['spring-boot-dentist-agent']);
});

test.afterAll(async () => {
  await server?.stop();
});

const CONSOLE_PATH = '/atmosphere/console/';

/** Navigate to the console and wait for the WebSocket to connect. */
async function openConsole(page: import('@playwright/test').Page, baseUrl: string) {
  await page.goto(baseUrl + CONSOLE_PATH);
  await expect(page.getByTestId('chat-layout')).toBeVisible();
  await expect(page.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });
}

/** Type a message into the console textarea and press Enter to send. */
async function sendMessage(page: import('@playwright/test').Page, text: string) {
  const input = page.getByTestId('chat-input');
  await input.fill(text);
  await input.press('Enter');
}

/**
 * XSS Protection E2E — drives real payloads through the console against the
 * dentist-agent sample (no auth, @Agent accepts raw text) and asserts none of
 * them execute.
 *
 * This complements, rather than duplicates, the headless unit coverage:
 * renderMarkdown() routes all console output through DOMPurify before any
 * `v-html` binding (modules/spring-boot-starter/frontend/src/lib/markdown.ts)
 * and markdown.test.ts asserts script / onerror / javascript: payloads are
 * stripped in isolation. These tests prove the sanitizer is actually on the
 * rendering path in a real browser, which a unit test cannot show.
 */
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

    // The sanitizer STRIPS the script element rather than escaping it for
    // display, so assert on the DOM: no <script> node ever reaches the
    // message content. (An earlier version of this test asserted the literal
    // text '<script>' was visible — that was never the behaviour; DOMPurify
    // removes the node and its content outright.)
    await expect(sender.locator('[data-testid="message-list"] script')).toHaveCount(0);

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

    // <img> itself is on the sanitizer's allow-list — what must not survive is
    // the event-handler attribute. Assert the attribute is gone rather than the
    // element: an assertion that no <img> renders would be testing the wrong
    // thing and would break the moment someone legitimately posts an image.
    await expect(sender.locator('[data-testid="message-list"] [onerror]')).toHaveCount(0);
    const imgHandlers = await sender.evaluate(() => {
      const list = document.querySelector('[data-testid="message-list"]');
      if (!list) return -1;
      return Array.from(list.querySelectorAll('img')).filter((el) =>
        Array.from(el.attributes).some((a) => a.name.toLowerCase().startsWith('on')),
      ).length;
    });
    expect(imgHandlers).toBe(0);

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

    // Layout tags are on the sanitizer's allow-list — they render as real, inert
    // elements, which is safe. What must NOT survive is anything scriptable, so
    // assert the boundary where it actually is: benign markup renders, and no
    // event-handler attribute or script node comes with it.
    await sendMessage(page, 'Use <div>a</div> and <span>b</span> for layout');

    // Scope to the USER bubble: `.message-content` unqualified also matches the
    // agent's reply, and `.last()` races whichever bubble renders last.
    const sent = page.locator('.message--user .message-content').last();
    await expect(sent).toContainText('for layout', { timeout: 15_000 });

    // No executable residue anywhere in the rendered message content.
    await expect(page.locator('[data-testid="message-list"] script')).toHaveCount(0);
    const handlerCount = await page.evaluate(() => {
      const list = document.querySelector('[data-testid="message-list"]');
      if (!list) return -1;
      return Array.from(list.querySelectorAll('*')).filter((el) =>
        Array.from(el.attributes).some((a) => a.name.toLowerCase().startsWith('on')),
      ).length;
    });
    expect(handlerCount).toBe(0);

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
