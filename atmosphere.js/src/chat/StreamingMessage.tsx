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

import { createElement } from 'react';
import Markdown from 'react-markdown';
import { cursorStyle, progressStyle, errorStyle } from './styles';
import { normalizeBlockElements } from './markdown';

/**
 * A single AI streaming message with optional blinking cursor.
 * Renders markdown by default using react-markdown.
 */
export interface StreamingMessageProps {
  /** Accumulated text so far. */
  text: string;
  /** Whether tokens are still arriving. */
  isStreaming: boolean;
  /** Dark theme flag. */
  dark?: boolean;
  /** Render text as markdown (default: true). */
  markdown?: boolean;
}

export function StreamingMessage({ text, isStreaming, dark, markdown = true }: StreamingMessageProps) {
  const containerStyle = {
    alignSelf: 'flex-start' as const,
    background: dark ? '#16213e' : '#fff',
    border: dark ? '1px solid #2a2a4a' : '1px solid #e9ecef',
    color: dark ? '#e0e0e0' : '#333',
    padding: '10px 14px',
    borderRadius: '16px 16px 16px 4px',
    maxWidth: '85%',
    wordBreak: 'break-word' as const,
    lineHeight: 1.5,
  };

  if (markdown) {
    const normalized = normalizeBlockElements(text);
    return createElement(
      'div',
      { style: containerStyle },
      createElement(Markdown, {
        components: {
          // Style code blocks for dark theme
          pre: ({ children }) => createElement('pre', {
            style: {
              background: '#0d1117',
              border: '1px solid #30363d',
              borderRadius: 8,
              padding: 12,
              overflowX: 'auto' as const,
              fontSize: 13,
              lineHeight: 1.45,
              color: '#e6edf3',
            },
          }, children),
          code: ({ children, className }) => {
            const isBlock = className?.startsWith('language-');
            if (isBlock) return createElement('code', { className }, children);
            return createElement('code', {
              style: {
                background: 'rgba(110,118,129,0.2)',
                padding: '2px 6px',
                borderRadius: 4,
                fontSize: '0.9em',
              },
            }, children);
          },
        },
      }, normalized),
      isStreaming ? createElement('span', { style: cursorStyle }) : null,
    );
  }

  return createElement(
    'div',
    { style: { ...containerStyle, whiteSpace: 'pre-wrap' as const } },
    text,
    isStreaming ? createElement('span', { style: cursorStyle }) : null,
  );
}

export interface StreamingProgressProps {
  message: string;
}

export function StreamingProgress({ message }: StreamingProgressProps) {
  return createElement('div', { style: progressStyle }, message);
}

export interface StreamingErrorProps {
  message: string;
}

export function StreamingError({ message }: StreamingErrorProps) {
  return createElement('div', { style: errorStyle }, `⚠ ${message}`);
}

export { cursorStyle, progressStyle, errorStyle };
