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
 * `Error.name` of the error an HTTP transport reports to the `error` handler
 * for a message the server did not take: that message was not delivered, but
 * the connection itself is still up.
 */
export const SEND_ERROR_NAME = 'AtmosphereSendError';

/** Whether `error` reports an undelivered message rather than a broken connection. */
export function isSendError(error: unknown): boolean {
  return (error as { name?: unknown } | null)?.name === SEND_ERROR_NAME;
}
