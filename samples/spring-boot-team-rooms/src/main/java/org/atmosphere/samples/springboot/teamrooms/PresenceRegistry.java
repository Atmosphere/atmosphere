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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.atmosphere.config.service.BroadcasterListenerService;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterListener;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Deliver;

/**
 * Live room occupancy, maintained by the framework rather than by the endpoints.
 *
 * <p>{@code @BroadcasterListenerService} installs this on every Broadcaster — including
 * ones created after startup, because the processor replays {@code onPostCreate} over
 * broadcasters that already exist. Counting in {@code @Ready}/{@code @Disconnect} instead
 * would miss every resource removed by a transport-level failure that never reaches the
 * annotated endpoint.</p>
 */
@BroadcasterListenerService
public class PresenceRegistry implements BroadcasterListener {

    private static final Map<String, AtomicInteger> OCCUPANCY = new ConcurrentHashMap<>();

    /** Occupancy per broadcaster id, for {@link PresenceController}. */
    public static Map<String, Integer> snapshot() {
        return OCCUPANCY.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, e -> e.getValue().get()));
    }

    @Override
    public void onPostCreate(Broadcaster b) {
        OCCUPANCY.putIfAbsent(b.getID(), new AtomicInteger());
    }

    @Override
    public void onComplete(Broadcaster b) {
        // Nothing to do — occupancy changes on add/remove, not on broadcast completion.
    }

    @Override
    public void onPreDestroy(Broadcaster b) {
        // Symmetric with onPostCreate: the counter must not outlive the broadcaster,
        // or the map grows without bound as rooms come and go.
        OCCUPANCY.remove(b.getID());
    }

    @Override
    public void onAddAtmosphereResource(Broadcaster b, AtmosphereResource r) {
        OCCUPANCY.computeIfAbsent(b.getID(), id -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public void onRemoveAtmosphereResource(Broadcaster b, AtmosphereResource r) {
        OCCUPANCY.computeIfAbsent(b.getID(), id -> new AtomicInteger()).decrementAndGet();
    }

    @Override
    public void onMessage(Broadcaster b, Deliver deliver) {
        // Presence is membership, not traffic.
    }
}
