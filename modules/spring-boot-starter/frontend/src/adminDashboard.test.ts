// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
// The admin dashboard is a standalone page, not part of the Console SPA: one
// copy per host (Spring Boot 4, Spring Boot 3, Quarkus). Every copy runs here.
import sb4Dashboard from '../../src/main/resources/META-INF/resources/atmosphere/admin/index.html?raw'
import sb3Dashboard from '../../../spring-boot3-starter/src/main/resources/META-INF/resources/atmosphere/admin/index.html?raw'
import quarkusDashboard from '../../../quarkus-admin-extension/runtime/src/main/resources/META-INF/resources/admin/index.html?raw'

const DASHBOARDS: Array<[string, string]> = [
  ['spring-boot-starter', sb4Dashboard],
  ['spring-boot3-starter', sb3Dashboard],
  ['quarkus-admin-extension', quarkusDashboard],
]

type Route = { status: number; body: unknown }

interface Dashboard {
  win: Window
  doc: Document
  sockets: FakeSocket[]
}

class FakeSocket {
  onopen: (() => void) | null = null
  onmessage: ((e: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  constructor(readonly url: string, registry: FakeSocket[]) {
    registry.push(this)
  }
  close() {}
}

/**
 * Boot one dashboard copy in its own iframe window, with fetch and WebSocket
 * replaced before the page script runs. `routes` maps an /api/admin path
 * prefix to a canned answer; anything else answers 200 [].
 */
function boot(html: string, routes: Record<string, Route>): Dashboard {
  const iframe = document.createElement('iframe')
  document.body.appendChild(iframe)
  const win = iframe.contentWindow as Window & typeof globalThis
  const sockets: FakeSocket[] = []
  win.fetch = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input).replace('/api/admin', '')
    const match = Object.keys(routes).find((prefix) => path.startsWith(prefix))
    const route = match ? routes[match] : { status: 200, body: [] }
    return new Response(JSON.stringify(route.body), { status: route.status })
  }) as typeof fetch
  win.WebSocket = class extends FakeSocket {
    constructor(url: string) { super(url, sockets) }
  } as unknown as typeof WebSocket
  win.document.open()
  win.document.write(html)
  win.document.close()
  return { win, doc: win.document, sockets }
}

async function settle() {
  for (let i = 0; i < 5; i++) await new Promise((r) => setTimeout(r, 0))
}

function openTab(d: Dashboard, tab: string) {
  ;(d.doc.querySelector(`.tab[data-tab="${tab}"]`) as HTMLElement).click()
}

afterEach(() => {
  document.body.innerHTML = ''
})

describe.each(DASHBOARDS)('admin dashboard (%s)', (_host, html) => {
  /**
   * The journal and audit reads sit behind the default-deny content-read
   * gate. `api()` turned the 401 into null and the tab rendered its empty
   * state, so an operator could not tell "unauthorized" from "nothing
   * recorded".
   */
  it('shows an unauthorized journal and audit read as access denied, not as empty', async () => {
    const d = boot(html, {
      '/journal': { status: 401, body: { error: 'Unauthorized' } },
      '/audit': { status: 401, body: { error: 'Unauthorized' } },
    })
    await settle()

    openTab(d, 'journal')
    await settle()
    const journal = d.doc.getElementById('journal-list')!.textContent ?? ''
    expect(journal).not.toContain('No journal events')
    expect(journal).toContain('Access denied')
    expect(journal).toContain('content-read-auth-required')

    openTab(d, 'control')
    await settle()
    const audit = d.doc.getElementById('audit-list')!.textContent ?? ''
    expect(audit).not.toContain('No audit entries')
    expect(audit).toContain('Access denied')
  })

  it('reports another failed read with its status and keeps the empty state for a real empty list', async () => {
    const d = boot(html, {
      '/journal': { status: 500, body: { error: 'boom' } },
      '/audit': { status: 200, body: [] },
    })
    await settle()

    openTab(d, 'journal')
    await settle()
    const journal = d.doc.getElementById('journal-list')!.textContent ?? ''
    expect(journal).toContain('HTTP 500')
    expect(journal).not.toContain('No journal events')

    openTab(d, 'control')
    await settle()
    expect(d.doc.getElementById('audit-list')!.textContent).toContain('No audit entries')
  })
})
