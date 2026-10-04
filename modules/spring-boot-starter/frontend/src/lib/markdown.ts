import { Marked } from 'marked'
import DOMPurify from 'dompurify'

const HTML_ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => HTML_ESCAPES[c])
}

/**
 * Raw HTML in the source (block or inline) is rendered as literal text, not
 * markup: model replies routinely mention `List<String>`, `<token>` or
 * `image:<base64>`, which a markdown renderer would otherwise parse as tags
 * and the sanitizer would then drop, silently losing words from the reply.
 * After an inline `<pre>`, `<code>`, `<kbd>` or `<script>` marked flags the
 * following text as already escaped; it is not, since the tag itself is now
 * text, so the flag is cleared and that text is escaped like any other.
 */
const markdown = new Marked({
  breaks: true,
  gfm: true,
  walkTokens(token) {
    if (token.type === 'text' && token.escaped) {
      token.escaped = false
    }
  },
  renderer: {
    html({ text }) {
      return escapeHtml(text)
    },
  },
})

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
  const html = markdown.parse(text ?? '', { async: false }) as string
  return DOMPurify.sanitize(html)
}
