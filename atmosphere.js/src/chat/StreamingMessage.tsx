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
import remarkGfm from 'remark-gfm';
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
    background: dark ? '#1a1d23' : '#fff',
    border: dark ? '1px solid #2d3039' : '1px solid #e9ecef',
    color: dark ? '#e4e5e7' : '#333',
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
        remarkPlugins: [remarkGfm],
        components: {
          p: ({ children }) => createElement('p', {
            style: { margin: '4px 0' },
          }, children),
          h1: ({ children }) => createElement('h1', {
            style: { fontSize: '1.4em', margin: '12px 0 6px', fontWeight: 700 },
          }, children),
          h2: ({ children }) => createElement('h2', {
            style: { fontSize: '1.2em', margin: '10px 0 4px', fontWeight: 600 },
          }, children),
          h3: ({ children }) => createElement('h3', {
            style: { fontSize: '1.05em', margin: '8px 0 4px', fontWeight: 600 },
          }, children),
          ul: ({ children }) => createElement('ul', {
            style: { margin: '4px 0', paddingLeft: 20 },
          }, children),
          ol: ({ children }) => createElement('ol', {
            style: { margin: '4px 0', paddingLeft: 20 },
          }, children),
          strong: ({ children }) => createElement('strong', {
            style: { color: dark ? '#fff' : 'inherit', fontWeight: 700 },
          }, children),
          blockquote: ({ children }) => createElement('blockquote', {
            style: {
              borderLeft: '3px solid #444',
              paddingLeft: 12,
              color: dark ? '#aaa' : '#666',
              margin: '8px 0',
            },
          }, children),
          table: ({ children }) => createElement('table', {
            style: {
              borderCollapse: 'collapse' as const,
              margin: '8px 0',
              fontSize: '0.95em',
              width: '100%',
            },
          }, children),
          thead: ({ children }) => createElement('thead', {
            style: { borderBottom: '2px solid ' + (dark ? '#444' : '#ddd') },
          }, children),
          th: ({ children }) => createElement('th', {
            style: {
              padding: '6px 10px',
              textAlign: 'left' as const,
              fontWeight: 600,
            },
          }, children),
          td: ({ children }) => createElement('td', {
            style: {
              padding: '6px 10px',
              borderTop: '1px solid ' + (dark ? '#333' : '#eee'),
            },
          }, children),
          // Style code blocks
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
