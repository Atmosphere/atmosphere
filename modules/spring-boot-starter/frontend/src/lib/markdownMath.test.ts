// @vitest-environment jsdom
import { describe, it, expect, beforeAll } from 'vitest'
import { renderMarkdown, loadMath } from './markdown'

/** Parse the rendered HTML the way `v-html` would, to assert on the DOM it builds. */
function dom(html: string): HTMLElement {
  const div = document.createElement('div')
  div.innerHTML = html
  return div
}

/**
 * Models write math as TeX: `\( … \)` inline, `\[ … \]` and `$$ … $$` on their
 * own line, sometimes `$ … $`. marked read `\(` as an escaped bracket and
 * dropped the backslash, so `\(b = \sqrt{144} = 12\)` showed as
 * `(b = \sqrt{144} = 12)`. Math spans are now kept away from marked and
 * rendered as MathML by KaTeX, loaded on the first reply that carries math.
 */
describe('renderMarkdown math before KaTeX has loaded', () => {
  it('shows the TeX source verbatim, backslashes included', () => {
    const out = dom(renderMarkdown('So \\(b = \\sqrt{144} = 12\\) cm.'))
    expect(out.textContent).toContain('\\(b = \\sqrt{144} = 12\\)')
  })
})

describe('renderMarkdown math', () => {
  beforeAll(async () => {
    await loadMath()
  })

  it('renders \\( … \\) as inline MathML', () => {
    const out = dom(renderMarkdown('So \\(b = \\sqrt{144} = 12\\) cm.'))
    const math = out.querySelector('.math-inline math')
    expect(math).not.toBeNull()
    expect(math?.getAttribute('display')).not.toBe('block')
    expect(math?.querySelector('msqrt')?.textContent).toBe('144')
    expect(out.textContent).not.toContain('\\sqrt')
    expect(out.textContent).not.toContain('(b =')
    expect(out.querySelector('p')?.textContent).toMatch(/^So .* cm\.$/)
  })

  it('renders \\[ … \\] as display MathML', () => {
    const out = dom(renderMarkdown('The rule:\n\n\\[\nx^2 + \\frac{b}{a}x = 0\n\\]\n\nDone.'))
    const math = out.querySelector('.math-display math')
    expect(math?.getAttribute('display')).toBe('block')
    expect(math?.querySelector('mfrac')).not.toBeNull()
    expect(out.textContent).not.toContain('\\frac')
  })

  it('adds no empty line around display math written on consecutive lines', () => {
    const out = dom(renderMarkdown('Substituting:\n\\[ b^2 = 13^2 - 5^2 \\]\n\\[ b^2 = 144 \\]\nso \\(b = 12\\)'))
    expect(out.querySelectorAll('.math-display math')).toHaveLength(2)
    for (const display of out.querySelectorAll('.math-display')) {
      expect(display.previousElementSibling?.tagName).not.toBe('BR')
      expect(display.nextElementSibling?.tagName).not.toBe('BR')
    }
    expect(out.textContent).toContain('Substituting:')
  })

  it('renders $$ … $$ as display MathML', () => {
    const out = dom(renderMarkdown('$$a^2 + b^2 = c^2$$'))
    const math = out.querySelector('.math-display math')
    expect(math?.getAttribute('display')).toBe('block')
    expect(math?.querySelectorAll('msup')).toHaveLength(3)
  })

  it('renders $ … $ inline but leaves dollar amounts alone', () => {
    expect(dom(renderMarkdown('Let $x_1$ be the root.')).querySelector('.math-inline math msub')).not.toBeNull()
    const prices = dom(renderMarkdown('It costs $5 and $10, or \\$3 off.'))
    expect(prices.querySelector('math')).toBeNull()
    expect(prices.textContent?.trim()).toBe('It costs $5 and $10, or $3 off.')
  })

  it('keeps markdown formatting around math', () => {
    const out = dom(renderMarkdown('**Area:** \\(A = \\pi r^2\\)\n\n- item \\(n_i\\)'))
    expect(out.querySelector('strong')?.textContent).toBe('Area:')
    expect(out.querySelector('p .math-inline math')).not.toBeNull()
    expect(out.querySelector('li .math-inline math')).not.toBeNull()
  })

  it('leaves code spans and fenced code blocks untouched', () => {
    const src = 'Use `\\(x\\)` or `$y$`.\n\n```latex\n\\[ z \\]\n$$w$$\n```\n\nand \\(v\\)'
    const out = dom(renderMarkdown(src))
    const codes = [...out.querySelectorAll('code')].map((c) => c.textContent)
    expect(codes).toContain('\\(x\\)')
    expect(codes).toContain('$y$')
    expect(out.querySelector('pre code')?.textContent).toBe('\\[ z \\]\n$$w$$\n')
    expect(out.querySelectorAll('math')).toHaveLength(1)
  })

  it('shows an unclosed span as source text while it streams, then renders it once closed', () => {
    const partial = dom(renderMarkdown('So **b** is \\(b = \\sqrt{14'))
    expect(partial.querySelector('math')).toBeNull()
    expect(partial.querySelector('strong')?.textContent).toBe('b')
    expect(partial.textContent).toContain('\\(b = \\sqrt{14')

    const display = dom(renderMarkdown('Then\n\n\\[\nx = \\frac{1'))
    expect(display.querySelector('math')).toBeNull()
    expect(display.textContent).toContain('\\[\nx = \\frac{1')

    const closed = dom(renderMarkdown('So **b** is \\(b = \\sqrt{144}\\)'))
    expect(closed.querySelector('.math-inline math msqrt')).not.toBeNull()
  })

  it('shows empty delimiters as written', () => {
    const out = dom(renderMarkdown('Use \\( \\) inline and $$ $$ for display.'))
    expect(out.querySelector('math')).toBeNull()
    expect(out.textContent).toContain('Use \\( \\) inline and $$ $$ for display.')
  })

  it('keeps math in an attribute as its escaped source', () => {
    const link = dom(renderMarkdown('[area](https://example.com "is \\(\\pi r^2\\)")')).querySelector('a')
    expect(link?.getAttribute('title')).toBe('is \\(\\pi r^2\\)')
    expect(link?.textContent).toBe('area')
  })

  it('falls back to the source text for TeX KaTeX cannot parse', () => {
    const out = dom(renderMarkdown('bad \\(\\frac{1\\) here'))
    expect(out.querySelector('math')).toBeNull()
    expect(out.textContent).toContain('\\(\\frac{1\\)')
  })

  it('emits no inline style the console CSP would block', () => {
    const out = renderMarkdown('\\(\\textcolor{red}{x} + \\sqrt{2}\\) and $$\\sum_{i=1}^n i$$')
    expect(out).toContain('<math')
    expect(out).not.toContain('style=')
  })

  it('opens no XSS path from inside math', () => {
    const attempts = [
      '\\(\\href{javascript:alert(1)}{click}\\)',
      '\\(\\url{javascript:alert(1)}\\)',
      '$$\\htmlClass{x}{y}\\htmlData{onclick=alert(1)}{z}\\htmlId{a}{b}\\htmlStyle{color:red}{c}$$',
      '\\(<img src=x onerror=alert(1)>\\)',
      '\\(\\text{<script>alert(1)</script>}\\)',
      '\\(x\\)</span><img src=x onerror=alert(1)>',
      '\uE000' + '0' + '\uE001 \\(y\\)',
    ]
    for (const src of attempts) {
      const html = renderMarkdown(src)
      const out = dom(html)
      expect(out.querySelector('a, img, script, [onerror], [onclick], [href], [style]'), src).toBeNull()
      expect(html.toLowerCase(), src).not.toContain('javascript:')
    }
  })
})
