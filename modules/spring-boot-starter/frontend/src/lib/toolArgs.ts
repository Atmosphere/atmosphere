/** Longest argument preview a tool card shows before eliding the rest. */
export const TOOL_ARG_PREVIEW_CHARS = 120

/**
 * One-line preview of a tool-call argument value. Primitives print as
 * themselves; arrays and objects are serialized as JSON (String() would
 * print `[object Object]`). A value JSON cannot serialize (a cycle) shows
 * a placeholder. Previews longer than {@link TOOL_ARG_PREVIEW_CHARS} are
 * cut and end with an ellipsis.
 */
export function formatToolArg(value: unknown): string {
  let text: string
  if (value === null || typeof value !== 'object') {
    text = String(value)
  } else {
    try {
      text = JSON.stringify(value)
    } catch (e) {
      // A cyclic structure has no JSON form; say what kind of value it is.
      console.debug('Tool argument has no JSON form', e)
      text = Array.isArray(value) ? '[…]' : '{…}'
    }
  }
  return text.length > TOOL_ARG_PREVIEW_CHARS
    ? text.slice(0, TOOL_ARG_PREVIEW_CHARS) + '…'
    : text
}
