import type { Page } from '@playwright/test';

/**
 * Fetch WebTransport info from the sample server.
 * Returns port and certificate hash for self-signed dev certs.
 */
export async function fetchWebTransportInfo(
  baseUrl: string,
): Promise<{ port: number; enabled: boolean; certificateHash?: string } | null> {
  try {
    const res = await fetch(`${baseUrl}/api/webtransport-info`);
    if (res.ok) return (await res.json()) as { port: number; enabled: boolean; certificateHash?: string };
  } catch { /* endpoint may not exist */ }
  return null;
}

/**
 * Collect console messages matching the Atmosphere transport log pattern.
 * Returns the transport name used (e.g. 'webtransport', 'websocket').
 */
export async function getAtmosphereTransport(page: Page): Promise<string | null> {
  const messages = await page.evaluate(() => {
    // @ts-ignore — __atmosphereConsole is set by the test setup
    return (window as any).__atmosphereConsoleMessages as string[] | undefined;
  });
  if (messages) {
    for (const msg of messages) {
      const match = msg.match(/via (\w+)/);
      if (match) return match[1];
    }
  }
  return null;
}

/**
 * Wait for a console message matching a pattern on the page.
 */
export function waitForConsoleMessage(
  page: Page,
  pattern: RegExp,
  timeoutMs = 15_000,
): Promise<string> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(
      () => reject(new Error(`Console message matching ${pattern} not found within ${timeoutMs}ms`)),
      timeoutMs,
    );
    const handler = (msg: { text: () => string }) => {
      const text = msg.text();
      if (pattern.test(text)) {
        clearTimeout(timer);
        page.removeListener('console', handler);
        resolve(text);
      }
    };
    page.on('console', handler);
  });
}
