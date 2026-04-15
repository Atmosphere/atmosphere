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
package org.atmosphere.cpr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link BroadcasterConfig} focusing on executor management,
 * filter operations, and cache configuration.
 */
class BroadcasterConfigTest {

    private AtmosphereFramework framework;
    private AtmosphereConfig config;
    private BroadcasterConfig broadcasterConfig;

    @BeforeEach
    void setUp() throws Exception {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(new AsynchronousProcessor(framework.getAtmosphereConfig()) {
            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res) {
                return Action.CONTINUE;
            }
        });
        framework.init();
        config = framework.getAtmosphereConfig();
        broadcasterConfig = new BroadcasterConfig(List.of(), config, "test-broadcaster");
        broadcasterConfig.init();
    }

    @AfterEach
    void tearDown() {
        broadcasterConfig.forceDestroy();
        framework.destroy();
    }

    @Test
    void hasFiltersShouldReturnFalseWhenNoFiltersAdded() {
        assertFalse(broadcasterConfig.hasFilters());
    }

    @Test
    void addFilterShouldRegisterFilter() {
        BroadcastFilter filter = (broadcasterId, originalMessage, message) ->
                new BroadcastFilter.BroadcastAction(message);
        boolean added = broadcasterConfig.addFilter(filter);
        assertTrue(added);
        assertTrue(broadcasterConfig.hasFilters());
        assertEquals(1, broadcasterConfig.filters().size());
    }

    @Test
    void addDuplicateFilterShouldNotAddTwice() {
        BroadcastFilter filter = (broadcasterId, originalMessage, message) ->
                new BroadcastFilter.BroadcastAction(message);
        broadcasterConfig.addFilter(filter);
        boolean addedAgain = broadcasterConfig.addFilter(filter);
        assertFalse(addedAgain);
        assertEquals(1, broadcasterConfig.filters().size());
    }

    @Test
    void removeFilterShouldRemoveRegisteredFilter() {
        BroadcastFilter filter = (broadcasterId, originalMessage, message) ->
                new BroadcastFilter.BroadcastAction(message);
        broadcasterConfig.addFilter(filter);
        assertTrue(broadcasterConfig.hasFilters());

        boolean removed = broadcasterConfig.removeFilter(filter);
        assertTrue(removed);
        assertFalse(broadcasterConfig.hasFilters());
    }

    @Test
    void removeAllFiltersShouldClearAllFilters() {
        BroadcastFilter filter1 = (broadcasterId, originalMessage, message) ->
                new BroadcastFilter.BroadcastAction(message);
        BroadcastFilter filter2 = (broadcasterId, originalMessage, message) ->
                new BroadcastFilter.BroadcastAction(message);
        broadcasterConfig.addFilter(filter1);
        broadcasterConfig.addFilter(filter2);
        assertEquals(2, broadcasterConfig.filters().size());

        broadcasterConfig.removeAllFilters();
        assertFalse(broadcasterConfig.hasFilters());
    }

    @Test
    void setExecutorServiceShouldReplaceExecutor() {
        ExecutorService custom = Executors.newSingleThreadExecutor();
        try {
            broadcasterConfig.setExecutorService(custom);
            assertSame(custom, broadcasterConfig.getExecutorService());
        } finally {
            custom.shutdownNow();
        }
    }

    @Test
    void setAsyncWriteServiceShouldReplaceExecutor() {
        ExecutorService custom = Executors.newSingleThreadExecutor();
        try {
            broadcasterConfig.setAsyncWriteService(custom);
            assertSame(custom, broadcasterConfig.getAsyncWriteService());
        } finally {
            custom.shutdownNow();
        }
    }

    @Test
    void setScheduledExecutorServiceShouldReplaceScheduler() {
        ScheduledExecutorService custom = Executors.newSingleThreadScheduledExecutor();
        try {
            broadcasterConfig.setScheduledExecutorService(custom);
            assertSame(custom, broadcasterConfig.getScheduledExecutorService());
        } finally {
            custom.shutdownNow();
        }
    }

    @Test
    void getBroadcasterCacheShouldReturnDefaultCache() {
        assertNotNull(broadcasterConfig.getBroadcasterCache());
        assertSame(BroadcasterCache.DEFAULT, broadcasterConfig.getBroadcasterCache());
    }

    @Test
    void setBroadcasterCacheShouldReplaceCache() {
        BroadcasterCache customCache = mock(BroadcasterCache.class);
        broadcasterConfig.setBroadcasterCache(customCache);
        assertSame(customCache, broadcasterConfig.getBroadcasterCache());
    }

    @Test
    void hasPerRequestFiltersShouldReturnFalseWhenNoPerRequestFilter() {
        BroadcastFilter filter = (broadcasterId, originalMessage, message) ->
                new BroadcastFilter.BroadcastAction(message);
        broadcasterConfig.addFilter(filter);
        assertFalse(broadcasterConfig.hasPerRequestFilters());
    }

    @Test
    void getAtmosphereConfigShouldReturnConfig() {
        assertSame(config, broadcasterConfig.getAtmosphereConfig());
    }

    @Test
    void handleExecutorsShouldReflectSharedState() {
        // When framework shares executor services, handleExecutors becomes false after init
        assertFalse(broadcasterConfig.handleExecutors());
    }
}
