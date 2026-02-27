/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.ai.cache;

/**
 * A coalesced cache event summarizing an entire AI streaming session.
 *
 * <p>Instead of firing per-token, this event fires once when the session
 * completes or errors, carrying aggregate information about the session.</p>
 *
 * @param sessionId     the unique session identifier
 * @param broadcasterId the broadcaster that cached the messages
 * @param totalTokens   total number of token messages cached for this session
 * @param status        terminal status: "complete" or "error"
 * @param elapsedMs     elapsed time from first token to terminal event, in milliseconds
 */
public record CoalescedCacheEvent(
        String sessionId,
        String broadcasterId,
        int totalTokens,
        String status,
        long elapsedMs
) {
}
