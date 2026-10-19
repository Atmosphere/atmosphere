/**
 * Per-tab memory of the room (endpoint option) picked on a Console whose chat
 * offers several, so a page reloaded mid-answer reconnects to the same room
 * and its reattach replays that room's run, rather than landing on the picker.
 *
 * Same lifetime as atmosphere.js keeps the run id for: the tab's
 * `sessionStorage`, which survives a reload and nothing else, keyed by the
 * Console page's origin and path (no query or fragment). It is written when a
 * room is picked, replaced when another is, and removed when the user leaves
 * the room or the stored room is not among the rooms the server offers any
 * more (the Console then shows the picker). Storage can be missing or throw
 * (blocked, quota): every access is best effort, and the Console then shows
 * the picker after a reload, as before.
 */

const KEY_PREFIX = 'atmosphere.console:room:'

function storage(): Storage | null {
  try {
    return typeof sessionStorage !== 'undefined' && sessionStorage !== null ? sessionStorage : null
  } catch {
    return null
  }
}

/** The storage key of the Console page this tab shows: origin and path. */
export function pickedRoomKey(): string {
  const page = typeof location !== 'undefined' && location ? location.origin + location.pathname : ''
  return KEY_PREFIX + page
}

/** Remember `endpoint` as the room this tab picked. */
export function savePickedRoom(endpoint: string): void {
  try {
    storage()?.setItem(pickedRoomKey(), endpoint)
  } catch {
    // Best effort: without storage a reload shows the picker.
  }
}

/** Forget the room this tab picked. */
export function clearPickedRoom(): void {
  try {
    storage()?.removeItem(pickedRoomKey())
  } catch {
    // Best effort.
  }
}

/**
 * The room this tab picked before a reload, when it is still one of
 * `offered` (the endpoints the server lists); a stored room no longer offered
 * is forgotten and `undefined` returned, so the Console shows the picker.
 */
export function restorePickedRoom(offered: ReadonlyArray<string>): string | undefined {
  let stored: string | null = null
  try {
    stored = storage()?.getItem(pickedRoomKey()) ?? null
  } catch {
    return undefined
  }
  if (stored === null || stored.length === 0) {
    return undefined
  }
  if (!offered.includes(stored)) {
    clearPickedRoom()
    return undefined
  }
  return stored
}
