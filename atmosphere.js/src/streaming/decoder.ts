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
 * The wire protocol is a JSON object with at least `type`, `sessionId`,
 * and `seq` fields.
 */
export function parseStreamingMessage(raw: string): StreamingMessage | null {
  if (!raw || raw.charAt(0) !== '{') return null;
  try {
    const parsed = JSON.parse(raw);
    if (!isStreamingType(parsed.type) || typeof parsed.sessionId !== 'string') {
      return null;
    }
    return parsed as StreamingMessage;
  } catch {
    logger.debug('Failed to parse streaming message', raw);
    return null;
  }
}

const STREAMING_TYPES = new Set<string>(['token', 'complete', 'error', 'progress', 'metadata']);

function isStreamingType(type: unknown): type is StreamingMessageType {
  return typeof type === 'string' && STREAMING_TYPES.has(type);
}
