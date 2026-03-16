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

import type { StreamingMessage, StreamingMessageType } from './types';
import { logger } from '../utils/logger';

/**
 * Parses a raw message string into a {@link StreamingMessage}, or returns
 * `null` if the message is not a valid streaming protocol message.
 *
 * Supports both legacy format ({@code type: "streaming-text"}) and the
 * new AiEvent format ({@code event: "text-delta"}).
 */
export function parseStreamingMessage(raw: string): StreamingMessage | null {
  if (!raw || raw.charAt(0) !== '{') return null;
  try {
    const parsed = JSON.parse(raw);
    // Legacy format: has "type" field
    if (isStreamingType(parsed.type) && typeof parsed.sessionId === 'string') {
      return parsed as StreamingMessage;
    }
    // AiEvent format: has "event" field
    if (typeof parsed.event === 'string' && typeof parsed.sessionId === 'string') {
      return parsed as StreamingMessage;
    }
    return null;
  } catch {
    logger.debug('Failed to parse streaming message', raw);
    return null;
  }
}

const STREAMING_TYPES = new Set<string>(['streaming-text', 'complete', 'error', 'progress', 'metadata']);

function isStreamingType(type: unknown): type is StreamingMessageType {
  return typeof type === 'string' && STREAMING_TYPES.has(type);
}
