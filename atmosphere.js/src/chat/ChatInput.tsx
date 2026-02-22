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

import { createElement, useState, useCallback } from 'react';
import type { KeyboardEvent } from 'react';
import type { ChatTheme } from './types';
import { themes } from './types';
import { inputBarStyle, inputStyle, sendButtonStyle } from './styles';

export interface ChatInputProps {
  onSend: (message: string) => void;
  placeholder?: string;
  disabled?: boolean;
  theme?: ChatTheme | string;
}

/**
 * Text input with enter-to-send and a Send button.
 */
export function ChatInput({
  onSend,
  placeholder = 'Type a message…',
  disabled = false,
  theme: themeProp,
}: ChatInputProps) {
  const [text, setText] = useState('');
  const resolved = resolveTheme(themeProp);

  const handleSend = useCallback(() => {
    const trimmed = text.trim();
    if (!trimmed) return;
    onSend(trimmed);
    setText('');
  }, [text, onSend]);

  const handleKey = useCallback(
    (e: KeyboardEvent) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        handleSend();
      }
    },
    [handleSend],
  );

  return createElement(
    'div',
    { style: inputBarStyle(resolved.dark) },
    createElement('input', {
      type: 'text',
      'data-testid': 'chat-input',
      value: text,
      onChange: (e: any) => setText(e.target.value),
      onKeyDown: handleKey,
      placeholder,
      disabled,
      style: inputStyle(resolved),
    }),
    createElement(
      'button',
      { 'data-testid': 'chat-send', onClick: handleSend, disabled: disabled || !text.trim(), style: sendButtonStyle(resolved) },
      'Send',
    ),
  );
}

function resolveTheme(t?: ChatTheme | string): ChatTheme {
  if (!t) return themes.default;
  if (typeof t === 'string') return themes[t] ?? themes.default;
  return t;
}
