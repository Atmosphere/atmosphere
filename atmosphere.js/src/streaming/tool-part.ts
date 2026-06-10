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

import type { ToolPart } from './types';

/**
 * Parse a server AiEvent into a typed {@link ToolPart}, or return null when the
 * event is not a tool lifecycle event.
 *
 * Wire contract (from {@code AiEvent.java} serialized by
 * {@code DefaultStreamingSession.buildEventMessage}):
 * - {@code tool-start}  -> data: { toolName, arguments }
 * - {@code tool-result} -> data: { toolName, result }
 * - {@code tool-error}  -> data: { toolName, error }
 *
 * Kept as a single parser (mirroring {@code parsePolicyDenial}) so wire shape
 * changes are one edit here, not scattered across dispatch sites.
 */
export function parseToolPart(
  event: string | undefined | null,
  data: Record<string, unknown>,
): ToolPart | null {
  if (!event) return null;
  const toolName = typeof data.toolName === 'string' ? data.toolName : '';
  switch (event) {
    case 'tool-start':
      return {
        type: 'tool-call',
        toolName,
        arguments:
          typeof data.arguments === 'object' && data.arguments !== null
            ? (data.arguments as Record<string, unknown>)
            : {},
        state: 'started',
      };
    case 'tool-result':
      return { type: 'tool-result', toolName, result: data.result, state: 'completed' };
    case 'tool-error':
      return {
        type: 'tool-error',
        toolName,
        error: typeof data.error === 'string' ? data.error : String(data.error ?? ''),
        state: 'error',
      };
    default:
      return null;
  }
}
