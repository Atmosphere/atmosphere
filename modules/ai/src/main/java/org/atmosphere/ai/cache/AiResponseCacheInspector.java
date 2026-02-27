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

import org.atmosphere.cache.BroadcastMessage;
import org.atmosphere.cache.BroadcasterCacheInspector;
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link BroadcasterCacheInspector} that understands the AI streaming wire protocol
 * and controls which AI messages are eligible for caching.
 *
 * <p>By default, all AI messages are cached except ephemeral "progress" messages
 * (e.g., "Thinking...", "Searching documents...") which are transient status updates
 * that are meaningless on replay.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * broadcaster.getBroadcasterConfig()
 *     .getBroadcasterCache()
 *     .inspector(new AiResponseCacheInspector());
 * }</pre>
 *
 * @see org.atmosphere.cpr.BroadcasterCache#inspector(BroadcasterCacheInspector)
 */
public class AiResponseCacheInspector implements BroadcasterCacheInspector {

    private static final Logger logger = LoggerFactory.getLogger(AiResponseCacheInspector.class);

    private final boolean cacheProgressMessages;

    /**
     * Create an inspector that skips progress messages.
     */
    public AiResponseCacheInspector() {
        this(false);
    }

    /**
     * Create an inspector with configurable progress message caching.
     *
     * @param cacheProgressMessages if {@code true}, progress messages are cached;
     *                              if {@code false} (default), they are skipped
     */
    public AiResponseCacheInspector(boolean cacheProgressMessages) {
        this.cacheProgressMessages = cacheProgressMessages;
    }

    @Override
    public boolean inspect(BroadcastMessage message) {
        var payload = message.message();

        if (!(payload instanceof RawMessage raw)) {
            return true;
        }

        var inner = raw.message();
        if (!(inner instanceof String json)) {
            return true;
        }

        // Quick check without full JSON parsing — look for "type":"progress"
        if (!cacheProgressMessages && json.contains("\"type\":\"progress\"")) {
            logger.debug("Skipping progress message from cache");
            return false;
        }

        return true;
    }
}
