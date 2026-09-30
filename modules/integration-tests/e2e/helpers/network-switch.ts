import type { BrowserContext, Page, WebSocketRoute } from '@playwright/test';

/**
 * A network cut the page's Atmosphere connection actually notices.
 *
 * `context.setOffline(true)` alone is not one: Chromium's offline emulation
 * fails NEW requests but leaves an established WebSocket open, and it does not
 * touch a WebTransport session at all. Measured against the spring-boot-chat
 * Console on 2026-09-30: after setOffline(true) the connection badge stayed
 * `data-phase="open"` for the full 15 s wait, over WebSocket and WebTransport
 * alike — so every spec that simulated a disconnect that way failed at its
 * first "not open" assertion without ever exercising the resilience path.
 *
 * {@link installNetworkSwitch} makes the cut real:
 *
 *   - Every WebSocket the page opens to `pattern` is proxied through
 *     `page.routeWebSocket`, so the switch holds a handle on it.
 *   - `down()` sets the context offline (HTTP, and so the long-polling
 *     fallback, fails too) and closes each proxied socket with 1006, the code a
 *     browser reports for a lost connection. While down, new sockets are
 *     refused before they open.
 *   - `up()` restores the network; the next reconnect attempt proxies through.
 *   - The Console's `/api/console/info` answer is served without its
 *     `webTransport` block, so the Console rides a WebSocket the switch can
 *     cut instead of an HTTP/3 session it cannot.
 */
export interface NetworkSwitch {
  down(): Promise<void>;
  up(): Promise<void>;
  /** Sockets the switch has proxied to the server so far (reconnects included). */
  readonly connections: number;
}

export async function installNetworkSwitch(
  page: Page,
  context: BrowserContext,
  pattern: RegExp,
): Promise<NetworkSwitch> {
  let online = true;
  let connections = 0;
  const live = new Set<WebSocketRoute>();

  await hideWebTransport(page);

  await page.routeWebSocket(pattern, (ws) => {
    if (!online) {
      // Refused before it opens: the page sees a close with no open, as it
      // would for a handshake that cannot reach the server.
      void ws.close({ code: 1006, reason: 'network down' });
      return;
    }
    connections++;
    live.add(ws);
    ws.connectToServer();
  });

  return {
    async down() {
      online = false;
      await context.setOffline(true);
      const sockets = [...live];
      live.clear();
      for (const ws of sockets) {
        await ws.close({ code: 1006, reason: 'network down' }).catch(() => {
          /* already closed by the page or the server */
        });
      }
    },
    async up() {
      await context.setOffline(false);
      online = true;
    },
    get connections() {
      return connections;
    },
  };
}

/**
 * Serve the Console's `/api/console/info` without its `webTransport` block, so
 * the Console connects over WebSocket (long-polling fallback) instead of its
 * WT-first HTTP/3 sidecar. Pins the transport a spec observes: with the
 * sidecar advertised, bundled Chromium lands on WebTransport on some boots and
 * on WebSocket on others, and a closed browser context tears a WebTransport
 * session down on QUIC's schedule rather than the socket's.
 */
export async function hideWebTransport(page: Page): Promise<void> {
  await page.route('**/api/console/info', async (route) => {
    const response = await route.fetch();
    const info = await response.json();
    delete info.webTransport;
    await route.fulfill({ response, json: info });
  });
}
