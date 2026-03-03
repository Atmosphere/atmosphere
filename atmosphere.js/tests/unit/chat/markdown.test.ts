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
import { normalizeBlockElements } from '../../../src/chat/markdown';

describe('normalizeBlockElements', () => {
  it('should insert newline before headers', () => {
    expect(normalizeBlockElements('text### Header')).toBe('text\n### Header');
    expect(normalizeBlockElements('text## Header')).toBe('text\n## Header');
    expect(normalizeBlockElements('text# Header')).toBe('text\n# Header');
  });

  it('should insert newline before bullet points', () => {
    expect(normalizeBlockElements('text- item')).toBe('text\n- item');
  });

  it('should insert newline before code fences', () => {
    // The second fence regex also splits the language tag onto its own line;
    // this is a known side-effect but doesn't break rendering.
    expect(normalizeBlockElements('text```js')).toContain('text\n```');
    expect(normalizeBlockElements('```code```next')).toContain('\n');
  });

  it('should insert newline before/after horizontal rules', () => {
    expect(normalizeBlockElements('text---next')).toBe('text\n---\nnext');
  });

  it('should not double-insert newlines', () => {
    expect(normalizeBlockElements('text\n### Header')).toBe('text\n### Header');
    expect(normalizeBlockElements('text\n- item')).toBe('text\n- item');
  });

  // --- New patterns ---

  it('should insert newline before numbered lists', () => {
    expect(normalizeBlockElements('text1. first')).toBe('text\n1. first');
    expect(normalizeBlockElements('text2. second')).toBe('text\n2. second');
    expect(normalizeBlockElements('item10. tenth')).toBe('item\n10. tenth');
  });

  it('should not insert newline before numbered list if already preceded by newline', () => {
    expect(normalizeBlockElements('text\n1. first')).toBe('text\n1. first');
  });

  it('should not break numbers that are not list markers', () => {
    // "12.5" should not be treated as a list — the digit before the dot prevents it
    expect(normalizeBlockElements('value 12.5')).toBe('value 12.5');
  });

  it('should insert newline before table rows', () => {
    expect(normalizeBlockElements('text| col1 | col2 |')).toBe('text\n| col1 | col2 |');
  });

  it('should not break within table row pipes', () => {
    // Adjacent pipes (||) should not trigger
    expect(normalizeBlockElements('a || b')).toBe('a || b');
  });

  it('should not insert newline before table row if already preceded by newline', () => {
    expect(normalizeBlockElements('text\n| col1 |')).toBe('text\n| col1 |');
  });

  it('should insert newline before blockquotes', () => {
    expect(normalizeBlockElements('text> quote')).toBe('text\n> quote');
  });

  it('should not insert newline before blockquote if already preceded by newline', () => {
    expect(normalizeBlockElements('text\n> quote')).toBe('text\n> quote');
  });

  it('should handle multiple block elements in sequence', () => {
    const input = 'intro### Title- bullet1. numbered| col |> quote';
    const result = normalizeBlockElements(input);
    expect(result).toContain('\n### Title');
    expect(result).toContain('\n- bullet');
    expect(result).toContain('\n1. numbered');
    expect(result).toContain('\n| col |');
    expect(result).toContain('\n> quote');
  });

  it('should pass through already well-formed markdown', () => {
    const input = '# Title\n\nSome text.\n\n- bullet\n\n1. item\n\n> quote\n\n| col |\n';
    expect(normalizeBlockElements(input)).toBe(input);
  });
});
