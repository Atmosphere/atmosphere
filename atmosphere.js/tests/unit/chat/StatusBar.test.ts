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
import { StatusBar } from '../../../src/chat/StatusBar';

describe('StatusBar', () => {
  it('should show "Connected" for connected state', () => {
    const html = renderToString(createElement(StatusBar, { state: 'connected' }));
    expect(html).toContain('Connected');
    expect(html).toContain('#28a745');
  });

  it('should show "Reconnecting…" for reconnecting state', () => {
    const html = renderToString(createElement(StatusBar, { state: 'reconnecting' }));
    expect(html).toContain('Reconnecting');
    expect(html).toContain('#ffc107');
  });

  it('should show "Connecting…" for connecting state', () => {
    const html = renderToString(createElement(StatusBar, { state: 'connecting' }));
    expect(html).toContain('Connecting');
  });

  it('should show "Disconnected" for disconnected state', () => {
    const html = renderToString(createElement(StatusBar, { state: 'disconnected' }));
    expect(html).toContain('Disconnected');
  });

  it('should show "Disconnected" for closed state', () => {
    const html = renderToString(createElement(StatusBar, { state: 'closed' }));
    expect(html).toContain('Disconnected');
  });

  it('should show "Connection error" for error state', () => {
    const html = renderToString(createElement(StatusBar, { state: 'error' }));
    expect(html).toContain('Connection error');
    expect(html).toContain('#dc3545');
  });
});
