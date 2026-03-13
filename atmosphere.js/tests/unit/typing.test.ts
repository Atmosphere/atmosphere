import { describe, it, expect } from 'vitest';
import type { RoomMessage, TypingEvent, RoomHandlers } from '../../src/types';

describe('Typing Indicator Types', () => {
  it('RoomMessage supports typing type', () => {
    const msg: RoomMessage = {
      type: 'typing',
      room: 'lobby',
    };
    expect(msg.type).toBe('typing');
    expect(msg.room).toBe('lobby');
  });

  it('TypingEvent has correct structure', () => {
    const event: TypingEvent = {
      room: 'lobby',
      memberId: 'user-1',
      typing: true,
      timestamp: Date.now(),
    };
    expect(event.room).toBe('lobby');
    expect(event.memberId).toBe('user-1');
    expect(event.typing).toBe(true);
    expect(event.timestamp).toBeGreaterThan(0);
  });

  it('TypingEvent allows null memberId', () => {
    const event: TypingEvent = {
      room: 'chat',
      memberId: null,
      typing: false,
      timestamp: Date.now(),
    };
    expect(event.memberId).toBeNull();
  });

  it('RoomHandlers includes typing callback', () => {
    const typingEvents: TypingEvent[] = [];
    const handlers: RoomHandlers = {
      typing: (event) => typingEvents.push(event),
    };

    const event: TypingEvent = {
      room: 'lobby',
      memberId: 'alice',
      typing: true,
      timestamp: Date.now(),
    };

    handlers.typing?.(event);
    expect(typingEvents).toHaveLength(1);
    expect(typingEvents[0].memberId).toBe('alice');
  });
});
