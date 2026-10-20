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
package org.atmosphere.ai.processor;

import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every connection to an AI endpoint registers the AI cache inspector with its
 * broadcaster's cache. Registered once per connection, the cache's inspector
 * list grew with every connect and reload, and a registration landing while a
 * broadcast was inspected failed that broadcast for every member.
 */
class AiEndpointHandlerCacheInspectorTest {

    @Test
    void theInspectorIsRegisteredOncePerCacheHoweverManyConnectionsJoin() {
        var cache = new UUIDBroadcasterCache();
        var broadcaster = mock(Broadcaster.class);
        var config = mock(BroadcasterConfig.class);
        when(broadcaster.getBroadcasterConfig()).thenReturn(config);
        when(config.getBroadcasterCache()).thenReturn(cache);

        for (var i = 0; i < 5; i++) {
            AiEndpointHandler.registerCacheInspector(broadcaster);
        }

        assertEquals(1, cache.inspectors().size());
    }
}
