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

import { describe, it, expect, vi } from 'vitest';
import { createElement } from 'react';
import { renderToString } from 'react-dom/server';
import { ChatInput } from '../../../src/chat/ChatInput';

describe('ChatInput', () => {
  it('should render input and send button', () => {
    const html = renderToString(createElement(ChatInput, { onSend: vi.fn() }));
    expect(html).toContain('input');
    expect(html).toContain('Send');
  });

  it('should use default placeholder', () => {
    const html = renderToString(createElement(ChatInput, { onSend: vi.fn() }));
    expect(html).toContain('Type a message');
  });

  it('should use custom placeholder', () => {
    const html = renderToString(
      createElement(ChatInput, { onSend: vi.fn(), placeholder: 'Ask a question…' }),
    );
    expect(html).toContain('Ask a question');
  });

  it('should render disabled state', () => {
    const html = renderToString(
      createElement(ChatInput, { onSend: vi.fn(), disabled: true }),
    );
    expect(html).toContain('disabled');
  });

  it('should apply dark theme', () => {
    const html = renderToString(
      createElement(ChatInput, { onSend: vi.fn(), theme: 'ai' }),
    );
    expect(html).toContain('#16213e');
  });

  it('should apply custom theme', () => {
    const html = renderToString(
      createElement(ChatInput, {
        onSend: vi.fn(),
        theme: { gradient: ['#ff0000', '#00ff00'], accent: '#0000ff' },
      }),
    );
    expect(html).toContain('#ff0000');
  });
});
