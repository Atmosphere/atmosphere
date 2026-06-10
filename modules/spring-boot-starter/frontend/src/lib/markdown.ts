import { marked } from 'marked'
import DOMPurify from 'dompurify'

marked.setOptions({ breaks: true, gfm: true })

/**
 * Render untrusted markdown (LLM output, tool results, user messages) to
 * sanitized HTML safe to bind with `v-html`.
 *
 * The marked output is passed through DOMPurify before it can reach the DOM,
 * stripping `<script>`, inline event-handler attributes (`onerror`, `onclick`,
 * …), `javascript:` URLs, and other XSS vectors. Without this, a model that
 * emits `<img src=x onerror=...>` would execute script in the console origin.
 */
export function renderMarkdown(text: string | null | undefined): string {
  const html = marked.parse(text ?? '', { async: false }) as string
  return DOMPurify.sanitize(html)
}
