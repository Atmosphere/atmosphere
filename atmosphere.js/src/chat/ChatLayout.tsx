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

// Atmosphere logo as inline SVG data URI (same as website/dist/logo.svg)
const ATMOSPHERE_LOGO_SVG = "data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cdefs%3E%3ClinearGradient id='ga' x1='30' y1='170' x2='160' y2='30' gradientUnits='userSpaceOnUse'%3E%3Cstop offset='0%25' stop-color='%233d2508'/%3E%3Cstop offset='30%25' stop-color='%236a4b1c'/%3E%3Cstop offset='60%25' stop-color='%238c6c35'/%3E%3Cstop offset='85%25' stop-color='%23b8944a'/%3E%3Cstop offset='100%25' stop-color='%23d4b060'/%3E%3C/linearGradient%3E%3ClinearGradient id='gb' x1='170' y1='30' x2='40' y2='170' gradientUnits='userSpaceOnUse'%3E%3Cstop offset='0%25' stop-color='%23efe0b8'/%3E%3Cstop offset='25%25' stop-color='%23dbcda3'/%3E%3Cstop offset='50%25' stop-color='%23c4a86a'/%3E%3Cstop offset='80%25' stop-color='%23a08040'/%3E%3Cstop offset='100%25' stop-color='%237a5a28'/%3E%3C/linearGradient%3E%3C/defs%3E%3Cpath d='M 72 51.5 L 74.2 36 A 69 69 0 0 1 158.5 136.6 L 178.9 149.3 L 129.7 147.5 L 116.1 110.1 L 136.5 122.8 A 43 43 0 0 0 83.9 60.1 Z' fill='url(%23ga)'/%3E%3Cpath d='M 128 148.5 L 125.8 164 A 69 69 0 0 1 41.5 63.4 L 21.1 50.7 L 70.3 52.5 L 83.9 89.9 L 63.5 77.2 A 43 43 0 0 0 116.1 139.9 Z' fill='url(%23gb)'/%3E%3Cpath d='M 166.6 82.1 A 69 69 0 0 1 158.5 136.6 L 136.5 122.8 A 43 43 0 0 0 141.5 88.9 Z' fill='url(%23ga)'/%3E%3Cpath d='M 158.5 136.6 L 178.9 149.3 L 129.7 147.5 L 116.1 110.1 L 136.5 122.8 Z' fill='url(%23ga)'/%3E%3C/svg%3E";

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

  const dark = resolved.dark;

  // Dark mode: left-aligned header matching Atmosphere AI Console
  const headerContent = dark
    ? createElement(
        'div',
        { style: headerStyle(resolved) },
        createElement('img', {
          src: ATMOSPHERE_LOGO_SVG,
          alt: 'Atmosphere',
          style: { width: 28, height: 28 },
        }),
        createElement(
          'div',
          { style: { display: 'flex', flexDirection: 'column' as const } },
          createElement('h1', { style: titleStyle(true) }, title),
          subtitle ? createElement('span', { style: subtitleStyle(true) }, subtitle) : null,
        ),
        headerExtra ?? null,
      )
    : createElement(
        'div',
        { style: headerStyle(resolved) },
        createElement('h1', { style: titleStyle(false) }, title),
        subtitle ? createElement('p', { style: subtitleStyle(false) }, subtitle) : null,
        headerExtra ?? null,
      );

  return createElement(
    'div',
    { 'data-testid': 'chat-layout', style: containerStyle(dark) },
    headerContent,
    state ? createElement(StatusBar, { state }) : null,
    children,
  );
}

function resolveTheme(t?: ChatTheme | string): ChatTheme {
  if (!t) return themes.default;
  if (typeof t === 'string') return themes[t] ?? themes.default;
  return t;
}
