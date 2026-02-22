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

import { createElement, useEffect, useRef } from 'react';
import type { ChatMessage, ChatTheme } from './types';
import { themes } from './types';
import { messageAreaStyle, systemStyle } from './styles';
import { MessageBubble } from './MessageBubble';

export interface MessageListProps {
  messages: ChatMessage[];
  currentUser?: string;
  theme?: ChatTheme | string;
}

/**
 * Scrollable list of chat messages with auto-scroll to bottom.
 */
export function MessageList({ messages, currentUser, theme: themeProp }: MessageListProps) {
  const resolved = resolveTheme(themeProp);
  const endRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages.length]);

  return createElement(
    'div',
    { 'data-testid': 'message-list', style: messageAreaStyle(resolved.dark) },
    messages.map((msg, i) =>
      msg.author === 'system'
        ? createElement('div', { key: i, style: systemStyle }, msg.message)
        : createElement(MessageBubble, {
            key: i,
            msg,
            currentUser,
            theme: resolved,
          }),
    ),
    createElement('div', { ref: endRef }),
  );
}

function resolveTheme(t?: ChatTheme | string): ChatTheme {
  if (!t) return themes.default;
  if (typeof t === 'string') return themes[t] ?? themes.default;
  return t;
}
