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
import { themes } from '../../../src/chat/types';
import type { ChatTheme } from '../../../src/chat/types';

describe('Chat themes', () => {
  it('should export exactly 5 preset themes', () => {
    expect(Object.keys(themes)).toHaveLength(5);
    expect(Object.keys(themes).sort()).toEqual(
      ['ai', 'default', 'embabel', 'langchain4j', 'mcp'],
    );
  });

  it.each(Object.entries(themes))('theme "%s" should have valid shape', (_name, theme) => {
    expect(theme.gradient).toHaveLength(2);
    expect(theme.gradient[0]).toMatch(/^#[0-9a-f]{6}$/i);
    expect(theme.gradient[1]).toMatch(/^#[0-9a-f]{6}$/i);
    expect(theme.accent).toMatch(/^#[0-9a-f]{6}$/i);
    expect(typeof theme.dark === 'boolean' || theme.dark === undefined).toBe(true);
  });

  it('default and mcp themes should be light', () => {
    expect(themes.default.dark).toBeUndefined();
    expect(themes.mcp.dark).toBeUndefined();
  });

  it('ai, langchain4j, and embabel themes should be dark', () => {
    expect(themes.ai.dark).toBe(true);
    expect(themes.langchain4j.dark).toBe(true);
    expect(themes.embabel.dark).toBe(true);
  });

  it('ChatTheme interface should accept custom themes', () => {
    const custom: ChatTheme = {
      gradient: ['#ff0000', '#00ff00'],
      accent: '#0000ff',
      dark: true,
    };
    expect(custom.gradient).toHaveLength(2);
    expect(custom.accent).toBe('#0000ff');
    expect(custom.dark).toBe(true);
  });
});
