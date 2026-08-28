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
package org.atmosphere.samples.springboot.teamrooms;

import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.config.service.BroadcasterCacheService;
import org.atmosphere.cpr.AtmosphereConfig;

/**
 * Replay-on-reconnect, bounded for a chat workload.
 *
 * <p>{@code @BroadcasterCacheService} makes this the cache for every Broadcaster, so a
 * client that drops and comes back is served what it missed instead of a gap. It extends
 * {@link UUIDBroadcasterCache} rather than reimplementing {@code BroadcasterCache} — the
 * per-client cursor bookkeeping is the hard part and it is already correct.</p>
 *
 * <p><strong>Why the bounds are set in {@code configure()} and not in a constructor.</strong>
 * {@link UUIDBroadcasterCache#configure(AtmosphereConfig)} assigns every one of these
 * fields from init parameters, overwriting whatever a subclass constructor set. Tightening
 * them here — after {@code super.configure} — is the only placement that survives startup.
 * A constructor-based version of this class compiles, reads correctly, and does nothing.</p>
 */
@BroadcasterCacheService
public class ReplayCache extends UUIDBroadcasterCache {

    /** A reconnecting client wants the recent backlog, not the whole day. */
    static final int MAX_PER_CLIENT = 50;
    /** Ceiling across all rooms — the cache is fed by client traffic, so it needs a cap. */
    static final int MAX_TOTAL = 5_000;
    /** Nothing older than this is worth replaying into a chat window. */
    static final long MESSAGE_TTL_MS = 60_000L;

    @Override
    public void configure(AtmosphereConfig config) {
        super.configure(config);
        setMaxPerClient(MAX_PER_CLIENT);
        setMaxTotal(MAX_TOTAL);
        setMessageTTL(MESSAGE_TTL_MS);
    }
}
