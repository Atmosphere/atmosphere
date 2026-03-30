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

/**
 * Verifies the error serialization patterns used in the hooks' catch blocks.
 *
 * Pattern 1 (streaming hooks): err instanceof Error ? err.message : String(err)
 *   → Always returns a string, extracts .message from Error instances
 *
 * Pattern 2 (core/room hooks): err instanceof Error ? err : new Error(String(err))
 *   → Always returns an Error, preserves Error instances
 *
 * These patterns prevent the [object Object] display bug by ensuring
 * Error instances have their .message extracted before reaching the UI.
 */
describe('Error serialization patterns', () => {
  // Pattern 1: used in useStreaming (React, Vue, Svelte, React Native)
  function toErrorString(err: unknown): string {
    return err instanceof Error ? err.message : String(err);
  }

  // Pattern 2: used in useAtmosphereCore, useRoom
  function toError(err: unknown): Error {
    return err instanceof Error ? err : new Error(String(err));
  }

  describe('toErrorString (streaming hooks)', () => {
    it('extracts message from Error instance', () => {
      expect(toErrorString(new Error('API returned 400: API key not valid.')))
        .toBe('API returned 400: API key not valid.');
    });

    it('extracts message from TypeError', () => {
      expect(toErrorString(new TypeError('fetch failed'))).toBe('fetch failed');
    });

    it('passes through string error', () => {
      expect(toErrorString('connection timeout')).toBe('connection timeout');
    });

    it('converts number to string', () => {
      expect(toErrorString(404)).toBe('404');
    });

    it('handles null safely', () => {
      expect(toErrorString(null)).toBe('null');
    });

    it('handles undefined safely', () => {
      expect(toErrorString(undefined)).toBe('undefined');
    });
  });

  describe('toError (core/room hooks)', () => {
    it('preserves Error instance', () => {
      const original = new Error('connection refused');
      const result = toError(original);
      expect(result).toBe(original);
      expect(result.message).toBe('connection refused');
    });

    it('wraps string in Error', () => {
      const result = toError('timeout');
      expect(result).toBeInstanceOf(Error);
      expect(result.message).toBe('timeout');
    });

    it('wraps null in Error without crashing', () => {
      const result = toError(null);
      expect(result).toBeInstanceOf(Error);
      expect(result.message).toBe('null');
    });

    it('wraps undefined in Error without crashing', () => {
      const result = toError(undefined);
      expect(result).toBeInstanceOf(Error);
      expect(result.message).toBe('undefined');
    });
  });
});
