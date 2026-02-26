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
import { ChatLayout } from '../../../src/chat/ChatLayout';

describe('ChatLayout', () => {
  it('should render title', () => {
    const html = renderToString(
      createElement(ChatLayout, { title: 'Test Chat' }, createElement('div', null, 'body')),
    );
    expect(html).toContain('Test Chat');
    expect(html).toContain('body');
  });

  it('should render subtitle when provided', () => {
    const html = renderToString(
      createElement(
        ChatLayout,
        { title: 'T', subtitle: 'Powered by AI' },
        createElement('div'),
      ),
    );
    expect(html).toContain('Powered by AI');
  });

  it('should not render subtitle when omitted', () => {
    const html = renderToString(
      createElement(ChatLayout, { title: 'T' }, createElement('div')),
    );
    expect(html).not.toContain('<p');
  });

  it('should render status bar when state is provided', () => {
    const html = renderToString(
      createElement(
        ChatLayout,
        { title: 'T', state: 'connected' },
        createElement('div'),
      ),
    );
    expect(html).toContain('Connected');
  });

  it('should not render status bar when state is omitted', () => {
    const html = renderToString(
      createElement(ChatLayout, { title: 'T' }, createElement('div')),
    );
    expect(html).not.toContain('Connected');
    expect(html).not.toContain('Disconnected');
  });

  it('should render headerExtra content', () => {
    const html = renderToString(
      createElement(
        ChatLayout,
        { title: 'T', headerExtra: createElement('span', null, 'badge') },
        createElement('div'),
      ),
    );
    expect(html).toContain('badge');
  });

  it('should apply default theme (light)', () => {
    const html = renderToString(
      createElement(ChatLayout, { title: 'T' }, createElement('div')),
    );
    // Light theme uses #f5f6fa background
    expect(html).toContain('#f5f6fa');
  });

  it('should apply named theme', () => {
    const html = renderToString(
      createElement(ChatLayout, { title: 'T', theme: 'ai' }, createElement('div')),
    );
    // Dark theme uses #1a1a2e background
    expect(html).toContain('#1a1a2e');
  });

  it('should apply custom theme object', () => {
    const html = renderToString(
      createElement(
        ChatLayout,
        {
          title: 'T',
          theme: { gradient: ['#ff0000', '#00ff00'], accent: '#0000ff', dark: true },
        },
        createElement('div'),
      ),
    );
    expect(html).toContain('#ff0000');
    expect(html).toContain('#1a1a2e');
  });

  it('should fall back to default theme for unknown name', () => {
    const html = renderToString(
      createElement(ChatLayout, { title: 'T', theme: 'nonexistent' }, createElement('div')),
    );
    expect(html).toContain('#4a5a8a');
  });
});
