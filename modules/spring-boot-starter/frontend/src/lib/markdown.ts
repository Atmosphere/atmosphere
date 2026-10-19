import { Marked } from 'marked'
import DOMPurify from 'dompurify'
import { shallowRef } from 'vue'
import { extractMath, MATH_PLACEHOLDER, type MathSpan } from './mathSpans'

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

type Katex = typeof import('katex').default

/**
 * KaTeX is loaded on the first reply that carries math, as its own chunk, so
 * the console pays nothing for it until then. Until it has loaded (or if it
 * fails to), math shows as its TeX source. Bumping `mathEpoch` re-runs every
 * render that read it, so those replies re-render as math once it arrives.
 */
let katex: Katex | null = null
let katexLoad: Promise<void> | null = null
const mathEpoch = shallowRef(0)

/** Load KaTeX (once) and resolve when math renders as MathML. */
export function loadMath(): Promise<void> {
  if (!katexLoad) {
    katexLoad = import('katex').then(
      (module) => {
        katex = module.default
        mathEpoch.value++
      },
      (error: unknown) => {
        console.warn('[console] KaTeX failed to load, math stays as TeX source', error)
      },
    )
  }
  return katexLoad
}

function mathSource(span: MathSpan): string {
  const kind = span.display ? 'math-source math-display' : 'math-source'
  return `<span class="${kind}">${escapeHtml(span.source)}</span>`
}

/**
 * Render one math span as MathML. MathML only: KaTeX's HTML output is laid
 * out with inline `style` attributes, which the console's nonce-based CSP
 * (no 'unsafe-inline') blocks, and needs its stylesheet and web fonts.
 * `trust: false` keeps \href, \url, \htmlClass and the other HTML-emitting
 * commands inert (KaTeX shows them as unsupported text). TeX that does not
 * parse falls back to its source rather than KaTeX's error markup, which
 * carries an inline style.
 */
function renderSpan(span: MathSpan): string {
  // Empty delimiters (a reply naming `\( \)` itself) would render as nothing.
  if (!span.closed || span.tex.trim() === '') {
    return mathSource(span)
  }
  if (!katex) {
    void loadMath()
    return mathSource(span)
  }
  let mathml: string
  try {
    mathml = katex.renderToString(span.tex, {
      displayMode: span.display,
      output: 'mathml',
      throwOnError: true,
      trust: false,
      strict: 'ignore',
      maxExpand: 500,
      maxSize: 20,
    })
  } catch {
    return mathSource(span)
  }
  // The TeX annotation would surface as text: DOMPurify drops <semantics>
  // and <annotation> but keeps their content.
  mathml = mathml
    .replace(/<annotation\b[^>]*>[\s\S]*?<\/annotation>/g, '')
    .replace(/<\/?semantics>/g, '')
  const kind = span.display ? 'math-display' : 'math-inline'
  return `<span class="math ${kind}">${mathml}</span>`
}

/**
 * Render untrusted markdown (LLM output, tool results, user messages) to
 * sanitized HTML safe to bind with `v-html`.
 *
 * TeX math (`\( \)`, `\[ \]`, `$$ $$`, `$ $`) is lifted out before marked
 * parses the text, so its backslashes survive, and put back as MathML.
 *
 * The final HTML, math included, is passed through DOMPurify before it can
 * reach the DOM, stripping `<script>`, inline event-handler attributes
 * (`onerror`, `onclick`, …), `javascript:` URLs, and other XSS vectors.
 * Without this, a model that emits `<img src=x onerror=...>` would execute
 * script in the console origin.
 */
export function renderMarkdown(text: string | null | undefined): string {
  // Read on every call so a caller's render re-runs once KaTeX has loaded.
  void mathEpoch.value
  const { text: source, spans } = extractMath(text ?? '')
  let html = markdown.parse(source, { async: false }) as string
  if (spans.length > 0) {
    html = html.replace(MATH_PLACEHOLDER, (match: string, before: string, index: string, after: string, offset: number) => {
      const span = spans[Number(index)]
      // Inside a tag (a link title, say) only the escaped source fits.
      const at = offset + before.length
      if (html.lastIndexOf('<', at) > html.lastIndexOf('>', at)) {
        return before + escapeHtml(span.source) + after
      }
      // A display span is a block of its own: the line breaks `breaks: true`
      // turned its surrounding newlines into would add an empty line.
      const rendered = renderSpan(span)
      return span.display && span.closed ? rendered : before + rendered + after
    })
  }
  return DOMPurify.sanitize(html)
}
