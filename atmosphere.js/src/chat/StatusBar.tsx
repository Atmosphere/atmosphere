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
import type { ConnectionState } from '../types';
import { statusBarStyle, statusDotStyle } from './styles';

const LABELS: Record<string, string> = {
  connected: 'Connected',
  reconnecting: 'Reconnecting…',
  connecting: 'Connecting…',
  disconnected: 'Disconnected',
  closed: 'Disconnected',
  error: 'Connection error',
};

export interface StatusBarProps {
  state: ConnectionState;
}

/**
 * Small status indicator showing connection state as a colored dot + label.
 */
export function StatusBar({ state }: StatusBarProps) {
  return createElement(
    'div',
    { style: statusBarStyle() },
    createElement('span', { style: statusDotStyle(state) }),
    createElement('span', null, LABELS[state] ?? state),
  );
}
