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
package org.atmosphere.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lazy {@code LLM_*} environment fallback the processors use when nothing
 * configured the LLM settings: it publishes at most once and never replaces
 * settings another caller configured, and the request-time variant does not
 * publish at all.
 */
class AiConfigEnvironmentFallbackTest {

    /** The fallback the endpoint, agent and coordinator processors call. */
    private static final Supplier<AiConfig.LlmSettings> FALLBACK = AiConfig::getOrConfigureFromEnvironment;

    @BeforeEach
    @AfterEach
    void unconfigure() {
        AiConfig.resetForTesting();
    }

    @Test
    void concurrentFallbacksPublishOneSettingsInstanceThatEveryCallerGets() throws Exception {
        var callers = 16;
        var barrier = new CyclicBarrier(callers);
        var results = new ArrayList<Future<AiConfig.LlmSettings>>();
        try (var executor = Executors.newFixedThreadPool(callers)) {
            for (var i = 0; i < callers; i++) {
                results.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return FALLBACK.get();
                }));
            }
            var distinct = new IdentityHashMap<AiConfig.LlmSettings, Boolean>();
            for (var result : results) {
                distinct.put(result.get(10, TimeUnit.SECONDS), Boolean.TRUE);
            }
            assertEquals(1, distinct.size(),
                    "every concurrent fallback must return the one published settings instance; a later "
                            + "publish replaced an earlier caller's settings");
            assertTrue(distinct.keySet().iterator().next() == AiConfig.get(),
                    "the instance every caller got is the one published");
        }
    }

    @Test
    void fallbackNeverReplacesConfiguredSettings() {
        // assertTrue on identity, not assertSame: a failure message must not
        // print settings whose apiKey may hold an ambient credential.
        var configured = AiConfig.configure("fake", "configured-model", null, null);
        assertTrue(configured == FALLBACK.get(), "the fallback returns the configured settings");
        assertTrue(configured == AiConfig.get(), "the fallback leaves the configured settings in place");
    }

    @Test
    void settingsFromEnvironmentDoesNotPublish() {
        assertNotNull(AiConfig.settingsFromEnvironment());
        assertTrue(AiConfig.get() == null,
                "building settings from the environment for one caller must not configure the JVM");
    }
}
