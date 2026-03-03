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

/**
 * Insert missing newlines before block-level markdown elements.
 * Some streaming providers (e.g. embacle) send complete lines as individual
 * SSE chunks without trailing newlines, producing text like:
 *   "paragraph text.### Header- bullet 1- bullet 2"
 * This normalizes it to proper line-separated markdown before rendering.
 */
export function normalizeBlockElements(text: string): string {
  let s = text;
  // Insert newline before/after code fence markers (```) when adjacent to text
  s = s.replace(/([^\n])(```)/g, '$1\n$2');
  s = s.replace(/(```[^\n]*?)([^\n`])/g, '$1\n$2');
  // Insert newline before headers (# ## ###) when preceded by non-whitespace
  s = s.replace(/([^\n])(\#{1,3}\s)/g, '$1\n$2');
  // Insert newline before bullet points: only match "- " (hyphen-space) to avoid
  // false positives with italic closing markers like "*text* — continuation"
  s = s.replace(/([^\n-])(- )/g, '$1\n$2');
  // Insert newline before/after horizontal rules (---) when adjacent to text
  s = s.replace(/([^\n])(---)/g, '$1\n$2');
  s = s.replace(/(---)([^\n-])/g, '$1\n$2');
  return s;
}
