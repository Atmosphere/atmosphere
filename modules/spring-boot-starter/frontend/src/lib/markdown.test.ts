// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import { renderMarkdown } from './markdown'

/**
 * Regression for the console XSS: chat messages and tool results are LLM/tool
 * output rendered via `v-html`, previously through `marked.parse` with no
 * sanitizer. renderMarkdown now routes everything through DOMPurify, so script
 * injection and inline event handlers can never reach the DOM.
 */
describe('renderMarkdown sanitization', () => {
  it('strips <script> tags', () => {
    const out = renderMarkdown('text before <script>alert(1)</script> text after')
    expect(out).not.toContain('<script')
    expect(out).not.toContain('alert(1)')
  })

  it('strips inline event-handler attributes (onerror)', () => {
    const out = renderMarkdown('<img src=x onerror="alert(document.cookie)">')
    expect(out.toLowerCase()).not.toContain('onerror')
  })

  it('strips javascript: URLs', () => {
    const out = renderMarkdown('[click me](javascript:alert(1))')
    expect(out.toLowerCase()).not.toContain('javascript:')
  })

  it('strips iframe/object injection', () => {
    const out = renderMarkdown('<iframe src="https://evil.example"></iframe>')
    expect(out.toLowerCase()).not.toContain('<iframe')
  })

  it('preserves safe markdown formatting', () => {
    const out = renderMarkdown('**bold** and `code` and [link](https://example.com)')
    expect(out).toContain('<strong>bold</strong>')
    expect(out).toContain('<code>code</code>')
    expect(out).toContain('href="https://example.com"')
  })

  it('handles null/undefined input safely', () => {
    expect(renderMarkdown(null)).toBe('')
    expect(renderMarkdown(undefined)).toBe('')
  })
})
