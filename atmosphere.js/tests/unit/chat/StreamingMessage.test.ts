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
import {
  StreamingMessage,
  StreamingProgress,
  StreamingError,
} from '../../../src/chat/StreamingMessage';

describe('StreamingMessage', () => {
  it('should render accumulated text', () => {
    const html = renderToString(
      createElement(StreamingMessage, { text: 'Hello world', isStreaming: false }),
    );
    expect(html).toContain('Hello world');
  });

  it('should show cursor when streaming', () => {
    const html = renderToString(
      createElement(StreamingMessage, { text: 'In progress', isStreaming: true }),
    );
    expect(html).toContain('In progress');
    // Cursor has blink animation
    expect(html).toContain('blink');
  });

  it('should not show cursor when not streaming', () => {
    const html = renderToString(
      createElement(StreamingMessage, { text: 'Done', isStreaming: false }),
    );
    expect(html).not.toContain('blink');
  });

  it('should use light theme by default', () => {
    const html = renderToString(
      createElement(StreamingMessage, { text: 'test', isStreaming: false }),
    );
    expect(html).toContain('#fff');
  });

  it('should use dark theme when dark=true', () => {
    const html = renderToString(
      createElement(StreamingMessage, { text: 'test', isStreaming: false, dark: true }),
    );
    expect(html).toContain('#16213e');
    expect(html).toContain('#e0e0e0');
  });

  it('should handle empty text', () => {
    const html = renderToString(
      createElement(StreamingMessage, { text: '', isStreaming: true }),
    );
    expect(html).toContain('blink');
  });
});

describe('StreamingProgress', () => {
  it('should render progress message', () => {
    const html = renderToString(
      createElement(StreamingProgress, { message: 'Loading model…' }),
    );
    expect(html).toContain('Loading model');
    expect(html).toContain('#ffc107');
  });
});

describe('StreamingError', () => {
  it('should render error message with warning icon', () => {
    const html = renderToString(
      createElement(StreamingError, { message: 'Connection failed' }),
    );
    expect(html).toContain('Connection failed');
    expect(html).toContain('#dc3545');
    expect(html).toContain('⚠');
  });
});
