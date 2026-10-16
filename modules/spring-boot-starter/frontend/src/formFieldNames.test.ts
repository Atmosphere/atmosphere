import { describe, expect, it } from 'vitest'

/**
 * Chrome flags "A form field element should have an id or name attribute" for
 * every <input>, <textarea> and <select> without one; the 2026-10-16 sweep saw
 * it on every Console page (the chat box, then each tab's fields). Every
 * field in every Console template carries an id or a name.
 */
const templates = import.meta.glob(['./**/*.vue'], { query: '?raw', import: 'default', eager: true }) as Record<string, string>

function fieldsWithoutIdOrName(source: string): string[] {
  const template = source.slice(source.indexOf('<template>'))
  const missing: string[] = []
  // Attribute values may hold '>' (v-if="owners.length > 0"): skip quoted runs.
  for (const match of template.matchAll(/<(input|textarea|select)\b((?:[^>"']|"[^"]*"|'[^']*')*)>/g)) {
    const attrs = match[2]
    if (!/(^|\s)(:?id|:?name)\s*=/.test(attrs)) {
      missing.push(match[0].replace(/\s+/g, ' ').slice(0, 120))
    }
  }
  return missing
}

describe('Console form fields', () => {
  it('scans the Console templates', () => {
    expect(Object.keys(templates).length).toBeGreaterThan(10)
  })

  it.each(Object.entries(templates))('%s: every input, textarea and select has an id or a name', (_file, source) => {
    expect(fieldsWithoutIdOrName(source)).toEqual([])
  })

  it('flags a field with neither', () => {
    expect(fieldsWithoutIdOrName('<template><input v-model="x" class="y" /></template>')).toHaveLength(1)
    expect(fieldsWithoutIdOrName('<template><input id="x" /><select\n name="y"></select></template>')).toEqual([])
    expect(fieldsWithoutIdOrName('<template><select v-if="n > 0" id="s"></select></template>')).toEqual([])
  })
})
