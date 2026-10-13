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

import type { AtmosphereRequest } from '../types';
import { logger } from './logger';

/** Base delay when neither `retryStrategy.initialDelay` nor `reconnectInterval` is set. */
export const DEFAULT_RECONNECT_INTERVAL_MS = 1000;

/** Reconnect attempts when neither `retryStrategy.maxAttempts` nor `maxReconnectOnClose` is set. */
export const DEFAULT_MAX_RECONNECT_ATTEMPTS = 5;

/** Largest exponent of the exponential backoff when no `maxDelay` is set (a 32x ceiling). */
const MAX_BACKOFF_EXPONENT = 5;

/** `setTimeout` treats anything above a signed 32-bit int as 0, so never schedule past it. */
const MAX_TIMEOUT_MS = 2_147_483_647;

/**
 * Number of reconnect attempts a transport makes before giving up and firing
 * `failureToReconnect`: `retryStrategy.maxAttempts` when set, otherwise
 * `maxReconnectOnClose`, otherwise {@link DEFAULT_MAX_RECONNECT_ATTEMPTS}.
 */
export function maxReconnectAttempts(
  request: Pick<AtmosphereRequest, 'maxReconnectOnClose' | 'retryStrategy'>,
): number {
  return request.retryStrategy?.maxAttempts
    ?? request.maxReconnectOnClose
    ?? DEFAULT_MAX_RECONNECT_ATTEMPTS;
}

/**
 * Delay in milliseconds before reconnect attempt number `attempt` (1 for the
 * first reconnect after a drop, 2 for the next, ...).
 *
 * Without a `retryStrategy` this is the historical backoff, unchanged:
 * `reconnectInterval * 2^min(attempt - 1, 5)` scaled by a random jitter in
 * `[0.5, 1)`. With a `retryStrategy` the delay is deterministic (no jitter)
 * and follows the rules documented on {@link RetryStrategy}.
 *
 * The result is always a finite number in `[0, 2^31 - 1]`.
 */
export function computeReconnectDelay(
  request: Pick<AtmosphereRequest, 'reconnectInterval' | 'retryStrategy'>,
  attempt: number,
  random: () => number = Math.random,
): number {
  const strategy = request.retryStrategy;
  if (!strategy) {
    const baseDelay = request.reconnectInterval ?? DEFAULT_RECONNECT_INTERVAL_MS;
    const exponentialDelay = baseDelay * Math.pow(2, Math.min(attempt - 1, MAX_BACKOFF_EXPONENT));
    const jitter = 0.5 + random() * 0.5;
    return clampDelay(exponentialDelay * jitter);
  }

  const initialDelay = strategy.initialDelay ?? request.reconnectInterval ?? DEFAULT_RECONNECT_INTERVAL_MS;
  let delay: number;
  switch (strategy.strategy) {
    case 'linear':
      delay = initialDelay * attempt;
      break;
    case 'exponential':
      delay = initialDelay * Math.pow(2, attempt - 1);
      if (strategy.maxDelay === undefined) {
        delay = Math.min(delay, initialDelay * Math.pow(2, MAX_BACKOFF_EXPONENT));
      }
      break;
    case 'custom':
      if (strategy.customCalculate) {
        delay = strategy.customCalculate(attempt);
      } else {
        logger.warn('retryStrategy "custom" has no customCalculate; using initialDelay');
        delay = initialDelay;
      }
      break;
    default:
      logger.warn(`Unknown retryStrategy "${String(strategy.strategy)}"; using initialDelay`);
      delay = initialDelay;
  }
  if (strategy.maxDelay !== undefined) {
    delay = Math.min(delay, strategy.maxDelay);
  }
  return clampDelay(delay);
}

function clampDelay(delay: number): number {
  if (Number.isNaN(delay)) {
    logger.warn('Reconnect delay is NaN; reconnecting immediately');
    return 0;
  }
  return Math.min(Math.max(delay, 0), MAX_TIMEOUT_MS);
}
