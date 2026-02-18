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
import { MessageList } from '../../../src/chat/MessageList';
import type { ChatMessage } from '../../../src/chat/types';

describe('MessageList', () => {
  const msgs: ChatMessage[] = [
    { author: 'Alice', message: 'Hello!' },
    { author: 'Bob', message: 'Hi there' },
  ];

  it('should render all messages', () => {
    const html = renderToString(createElement(MessageList, { messages: msgs }));
    expect(html).toContain('Alice');
    expect(html).toContain('Hello!');
    expect(html).toContain('Bob');
    expect(html).toContain('Hi there');
  });

  it('should render empty list without errors', () => {
    const html = renderToString(createElement(MessageList, { messages: [] }));
    expect(html).toBeDefined();
    expect(html).not.toContain('Alice');
  });

  it('should render system messages with system style', () => {
    const systemMsgs: ChatMessage[] = [
      { author: 'system', message: 'User joined' },
    ];
    const html = renderToString(createElement(MessageList, { messages: systemMsgs }));
    expect(html).toContain('User joined');
    // System messages use #28a745 color
    expect(html).toContain('#28a745');
  });

  it('should highlight own messages when currentUser is set', () => {
    const html = renderToString(
      createElement(MessageList, { messages: msgs, currentUser: 'Alice' }),
    );
    // Own messages use flex-end alignment
    expect(html).toContain('flex-end');
    // Other messages use flex-start
    expect(html).toContain('flex-start');
  });

  it('should apply dark theme', () => {
    const html = renderToString(
      createElement(MessageList, { messages: msgs, theme: 'ai' }),
    );
    expect(html).toContain('#16213e');
  });
});
