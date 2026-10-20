// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'

// The KaTeX chunk fails to load while the network is down, then loads.
const network = vi.hoisted(() => ({ down: true }))
vi.mock('katex', async (importOriginal) => {
  if (network.down) throw new Error('net::ERR_INTERNET_DISCONNECTED')
  return importOriginal()
})

import { renderMarkdown, loadMath } from './markdown'

function dom(html: string): HTMLElement {
  const div = document.createElement('div')
  div.innerHTML = html
  return div
}

/**
 * A failed load of the KaTeX chunk was kept as the load: the page showed TeX
 * source until a reload, though the network came back. The next render after
 * a failure loads it again.
 */
describe('renderMarkdown math after the KaTeX chunk failed to load', () => {
  it('loads KaTeX again on the next render and renders the math', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    expect(dom(renderMarkdown('$$x^2$$')).querySelector('math')).toBeNull()
    await loadMath()
    expect(dom(renderMarkdown('$$x^2$$')).querySelector('math')).toBeNull()

    network.down = false
    renderMarkdown('$$x^2$$')
    await loadMath()
    expect(dom(renderMarkdown('$$x^2$$')).querySelector('.math-display math msup')).not.toBeNull()
  })
})
