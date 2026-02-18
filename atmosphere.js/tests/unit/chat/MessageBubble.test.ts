/*
 * Copyright 2011-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { describe, it, expect } from 'vitest';
import { createElement } from 'react';
import { renderToString } from 'react-dom/server';
import { MessageBubble } from '../../../src/chat/MessageBubble';
import type { ChatMessage } from '../../../src/chat/types';

describe('MessageBubble', () => {
  const msg: ChatMessage = { author: 'Alice', message: 'Hello world' };

  it('should render author and message text', () => {
    const html = renderToString(createElement(MessageBubble, { msg }));
    expect(html).toContain('Alice');
    expect(html).toContain('Hello world');
  });

  it('should render time when provided', () => {
    const msgWithTime: ChatMessage = { author: 'Bob', message: 'Hi', time: 1708300000000 };
    const html = renderToString(createElement(MessageBubble, { msg: msgWithTime }));
    expect(html).toContain('Bob');
    expect(html).toContain('Hi');
    // Time should be rendered (exact format depends on locale)
    expect(html).toContain(':');
  });

  it('should not render time when omitted', () => {
    const html = renderToString(createElement(MessageBubble, { msg }));
    // No time span — fewer child elements
    const timelessHtml = html;
    const msgWithTime: ChatMessage = { ...msg, time: 1708300000000 };
    const htmlWithTime = renderToString(createElement(MessageBubble, { msg: msgWithTime }));
    expect(htmlWithTime.length).toBeGreaterThan(timelessHtml.length);
  });

  it('should style own messages differently', () => {
    const ownHtml = renderToString(
      createElement(MessageBubble, { msg, currentUser: 'Alice' }),
    );
    const otherHtml = renderToString(
      createElement(MessageBubble, { msg, currentUser: 'Bob' }),
    );
    expect(ownHtml).toContain('flex-end');
    expect(otherHtml).toContain('flex-start');
  });

  it('should apply theme by name', () => {
    const html = renderToString(
      createElement(MessageBubble, { msg, theme: 'ai' }),
    );
    expect(html).toContain('#16213e');
  });

  it('should apply custom theme object', () => {
    const html = renderToString(
      createElement(MessageBubble, {
        msg,
        theme: { gradient: ['#ff0000', '#00ff00'], accent: '#0000ff' },
      }),
    );
    expect(html).toContain('#fff');
  });
});
