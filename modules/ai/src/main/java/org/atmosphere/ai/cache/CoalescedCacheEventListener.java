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
 * Listener for coalesced cache events.
 *
 * <p>Unlike the per-token {@link org.atmosphere.cpr.BroadcasterCacheListener},
 * this listener fires once per session when it completes or errors, providing
 * aggregate metrics.</p>
 *
 * @see CoalescedCacheEvent
 * @see AiResponseCacheListener#addCoalescedListener(CoalescedCacheEventListener)
 */
@FunctionalInterface
public interface CoalescedCacheEventListener {

    /**
     * Called when a streaming session's cache lifecycle completes.
     *
     * @param event the coalesced event with aggregate session data
     */
    void onCoalescedEvent(CoalescedCacheEvent event);
}
