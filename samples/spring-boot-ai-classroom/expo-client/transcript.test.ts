import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { memberLabel, withMemberPrompt, type Message } from './transcript';

const FROM = '3f9c2a7be41d4c0a9e15';

describe('a room member prompt in the transcript', () => {
  test("another member's prompt becomes a user bubble labelled with its member id", () => {
    const before: Message[] = [{ role: 'assistant', text: 'Earlier answer', complete: true }];
    expect(withMemberPrompt(before, '', 'What is 2 + 2?', FROM, false)).toEqual([
      ...before,
      { role: 'user', text: 'What is 2 + 2?', author: 'Member 3f9c2a7b' },
    ]);
  });

  test("this connection's own prompt is not added again", () => {
    const before: Message[] = [{ role: 'user', text: 'What is 2 + 2?' }];
    expect(withMemberPrompt(before, '', 'What is 2 + 2?', FROM, true)).toBeNull();
  });

  test('an empty prompt adds nothing', () => {
    expect(withMemberPrompt([], '', '', FROM, false)).toBeNull();
  });

  test('a prompt with no member id is shown unlabelled', () => {
    expect(withMemberPrompt([], '', 'Hi', '', false)).toEqual([{ role: 'user', text: 'Hi' }]);
  });

  test('a reply still on screen is finished before the next turn opens', () => {
    const before: Message[] = [{ role: 'user', text: 'First question' }];
    expect(withMemberPrompt(before, 'First answer', 'Second question', FROM, false)).toEqual([
      ...before,
      { role: 'assistant', text: 'First answer', complete: true },
      { role: 'user', text: 'Second question', author: 'Member 3f9c2a7b' },
    ]);
  });

  test('the label is the first eight characters of the member id, as in the Console', () => {
    expect(memberLabel(FROM)).toBe('Member 3f9c2a7b');
  });
});

describe('App.tsx', () => {
  // The app cannot be rendered under bun (React Native), so this is the check
  // that the hook's member prompts reach the transcript at all.
  test('passes onPrompt to useStreamingRN', () => {
    const source = readFileSync(join(import.meta.dir, 'App.tsx'), 'utf8');
    const call = source.slice(source.indexOf('useStreamingRN({'));
    expect(call.slice(0, call.indexOf('\n  });'))).toContain('onPrompt:');
  });
});
