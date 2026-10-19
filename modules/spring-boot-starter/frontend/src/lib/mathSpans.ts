/**
 * Finds the TeX math in a markdown reply and swaps each span for a
 * placeholder marked leaves alone, so marked never sees the math: CommonMark
 * reads `\(` and `\[` as escaped punctuation and drops their backslash.
 *
 * Recognized: `\( … \)` and `$ … $` inline, `\[ … \]` and `$$ … $$` display.
 * A single `$` follows the pandoc rule (no space after the opening `$`, none
 * before the closing one, no digit right after it, one line), so dollar
 * amounts stay text, and `\$` is never a delimiter. Code spans and fenced
 * code blocks are copied untouched.
 *
 * An opening `\(`, `\[` or `$$` with no closing delimiter yet (a reply still
 * streaming) becomes an unclosed span holding the rest of its paragraph, shown
 * as text until the delimiter arrives.
 */

export interface MathSpan {
  /** The TeX between the delimiters. */
  tex: string
  /** The span as written, delimiters included. */
  source: string
  display: boolean
  closed: boolean
}

const OPEN = ''
const CLOSE = ''
const PRIVATE_MARKERS = /[]/g

/**
 * Matches a placeholder in marked's output with any line break right before
 * (group 1) or after it (group 3); group 2 is the span index.
 */
export const MATH_PLACEHOLDER = /((?:<br>\n?)?)(\d+)((?:\n?<br>)?)/g

const FENCE_OPEN = /^ {0,3}(`{3,}|~{3,})([^\n]*)/y

function paragraphEnd(src: string, from: number): number {
  const end = src.indexOf('\n\n', from)
  return end < 0 ? src.length : end
}

/** End index (exclusive) of a fenced block opening at `start`, or -1 if none opens there. */
function fenceEnd(src: string, start: number): number {
  FENCE_OPEN.lastIndex = start
  const open = FENCE_OPEN.exec(src)
  if (!open) return -1
  const marker = open[1]
  if (marker[0] === '`' && open[2].includes('`')) return -1
  const close = new RegExp(`^ {0,3}${marker[0] === '`' ? '`' : '~'}{${marker.length},}[ \\t]*$`, 'gm')
  close.lastIndex = start + open[0].length
  const found = close.exec(src)
  return found ? found.index + found[0].length : src.length
}

/** End index (exclusive) of the code span opening at `start`, or -1 if its run never closes. */
function codeSpanEnd(src: string, start: number): number {
  let run = 0
  while (src[start + run] === '`') run++
  let i = start + run
  while (i < src.length) {
    const next = src.indexOf('`', i)
    if (next < 0) return -1
    let len = 0
    while (src[next + len] === '`') len++
    if (len === run) return next + len
    i = next + len
  }
  return -1
}

/** Index of the `$` closing a single-dollar span opened at `start`, or -1. */
function singleDollarEnd(src: string, start: number): number {
  const first = src[start + 1]
  if (first === undefined || /\s/.test(first)) return -1
  for (let i = start + 1; i < src.length; i++) {
    const c = src[i]
    if (c === '\n') return -1
    if (c === '\\') {
      i++
      continue
    }
    if (c === '$') {
      if (/\s/.test(src[i - 1]) || /\d/.test(src[i + 1] ?? '')) return -1
      return i
    }
  }
  return -1
}

export function extractMath(input: string): { text: string; spans: MathSpan[] } {
  const src = input.replace(PRIVATE_MARKERS, '')
  const spans: MathSpan[] = []
  let out = ''
  let i = 0

  const push = (span: MathSpan) => {
    out += OPEN + spans.length + CLOSE
    spans.push(span)
  }
  const unclosed = (from: number) => {
    const end = paragraphEnd(src, from)
    const source = src.slice(from, end)
    push({ tex: '', source, display: false, closed: false })
    return end
  }

  while (i < src.length) {
    if (i === 0 || src[i - 1] === '\n') {
      const end = fenceEnd(src, i)
      if (end >= 0) {
        out += src.slice(i, end)
        i = end
        continue
      }
    }
    const c = src[i]
    if (c === '`') {
      let run = 0
      while (src[i + run] === '`') run++
      const end = codeSpanEnd(src, i)
      const stop = end >= 0 ? end : i + run
      out += src.slice(i, stop)
      i = stop
      continue
    }
    if (c === '\\') {
      const next = src[i + 1]
      if (next === '(' || next === '[') {
        const closer = next === '(' ? '\\)' : '\\]'
        const display = next === '['
        const limit = display ? src.length : paragraphEnd(src, i)
        const end = src.indexOf(closer, i + 2)
        if (end >= 0 && end < limit) {
          push({ tex: src.slice(i + 2, end), source: src.slice(i, end + 2), display, closed: true })
          i = end + 2
        } else {
          i = unclosed(i)
        }
        continue
      }
      out += src.slice(i, i + 2)
      i += 2
      continue
    }
    if (c === '$') {
      if (src[i + 1] === '$') {
        const end = src.indexOf('$$', i + 2)
        if (end >= 0) {
          push({ tex: src.slice(i + 2, end), source: src.slice(i, end + 2), display: true, closed: true })
          i = end + 2
        } else {
          i = unclosed(i)
        }
        continue
      }
      const end = singleDollarEnd(src, i)
      if (end >= 0) {
        push({ tex: src.slice(i + 1, end), source: src.slice(i, end + 1), display: false, closed: true })
        i = end + 1
        continue
      }
    }
    out += c
    i++
  }
  return { text: out, spans }
}
