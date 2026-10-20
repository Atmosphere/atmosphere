/**
 * The classroom transcript: what the app keeps on screen, and how a room
 * member's prompt joins it. Kept free of React Native so `bun test` runs it.
 */

export interface UserMessage {
  role: 'user';
  text: string;
  /** Parked in the offline queue; goes out when the connection returns. */
  queued?: boolean;
  /** Set on another room member's prompt: who asked it. */
  author?: string;
}

export interface AssistantMessage {
  role: 'assistant';
  text: string;
  complete: boolean;
}

export type Message = UserMessage | AssistantMessage;

/** How another member is named in the transcript, as the Console names it. */
export function memberLabel(from: string): string {
  return `Member ${from.slice(0, 8)}`;
}

/**
 * The transcript once a room member's prompt (the hook's `onPrompt`) arrives,
 * or `null` when it does not change.
 *
 * This connection's own prompt (`own`) is already on screen, put there when
 * it was sent, so it is not added twice. Another member's opens a turn: a
 * reply still streaming on screen (`liveText`) is finished first, so the
 * reply that follows gets a bubble of its own, then the prompt is added,
 * labelled with the member's id.
 */
export function withMemberPrompt(
  messages: Message[],
  liveText: string,
  text: string,
  from: string,
  own: boolean,
): Message[] | null {
  if (own || !text) return null;
  const next: Message[] = liveText
    ? [...messages, { role: 'assistant', text: liveText, complete: true }]
    : [...messages];
  next.push(from ? { role: 'user', text, author: memberLabel(from) } : { role: 'user', text });
  return next;
}
