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
import type { ChatMessage, ChatTheme } from './types';
import { themes } from './types';
import { bubbleStyle, authorStyle, timeStyle } from './styles';

export interface MessageBubbleProps {
  msg: ChatMessage;
  currentUser?: string;
  theme?: ChatTheme | string;
}

/**
 * Single chat message bubble with author, optional time, and text.
 */
export function MessageBubble({ msg, currentUser, theme: themeProp }: MessageBubbleProps) {
  const resolved = resolveTheme(themeProp);
  const isOwn = !!currentUser && msg.author === currentUser;

  return createElement(
    'div',
    { 'data-testid': 'message-bubble', style: bubbleStyle(isOwn, resolved) },
    createElement(
      'div',
      { style: { display: 'flex', alignItems: 'baseline' } },
      createElement('span', { 'data-testid': 'message-author', style: authorStyle }, msg.author),
      msg.time
        ? createElement('span', { style: timeStyle }, formatTime(msg.time))
        : null,
    ),
    createElement('div', { style: { marginTop: 4 } }, msg.message),
  );
}

function formatTime(ts: number): string {
  const d = new Date(ts);
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function resolveTheme(t?: ChatTheme | string): ChatTheme {
  if (!t) return themes.default;
  if (typeof t === 'string') return themes[t] ?? themes.default;
  return t;
}
