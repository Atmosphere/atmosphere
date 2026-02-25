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
import type { ReactNode } from 'react';
import type { ChatTheme } from './types';
import { themes } from './types';
import type { ConnectionState } from '../types';
import { containerStyle, headerStyle, titleStyle, subtitleStyle } from './styles';
import { StatusBar } from './StatusBar';

export interface ChatLayoutProps {
  /** Main title shown in the header. Accepts a string or ReactNode (e.g. an image + text). */
  title: ReactNode;
  /** Subtitle shown below the title. */
  subtitle?: string;
  /** Theme name (preset key) or custom ChatTheme object. */
  theme?: ChatTheme | string;
  /** Current connection state for the status bar. */
  state?: ConnectionState;
  /** Extra elements rendered in the header (e.g. model badge). */
  headerExtra?: ReactNode;
  /** The chat body (MessageList, input, etc.). */
  children: ReactNode;
}

/**
 * Top-level chat container with gradient header, status bar, and content area.
 */
export function ChatLayout({
  title,
  subtitle,
  theme: themeProp,
  state,
  headerExtra,
  children,
}: ChatLayoutProps) {
  const resolved = resolveTheme(themeProp);

  return createElement(
    'div',
    { 'data-testid': 'chat-layout', style: containerStyle(resolved.dark) },
    createElement(
      'div',
      { style: headerStyle(resolved) },
      createElement('h1', { style: titleStyle }, title),
      subtitle ? createElement('p', { style: subtitleStyle }, subtitle) : null,
      headerExtra ?? null,
    ),
    state ? createElement(StatusBar, { state }) : null,
    children,
  );
}

function resolveTheme(t?: ChatTheme | string): ChatTheme {
  if (!t) return themes.default;
  if (typeof t === 'string') return themes[t] ?? themes.default;
  return t;
}
