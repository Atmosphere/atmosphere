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
import org.atmosphere.config.service.BroadcasterCacheInspectorService;
import org.atmosphere.config.service.BroadcasterCacheListenerService;
import org.atmosphere.config.service.BroadcasterCacheService;
import org.atmosphere.config.service.BroadcasterFilterService;
import org.atmosphere.config.service.BroadcasterListenerService;
import org.atmosphere.config.service.AtmosphereInterceptorService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service annotations are what install this samples machinery — without them the
 * classes are dead code the framework never sees. Each assertion here corresponds to one
 * capability the README claims.
 *
 * <p>{@link #replayCacheTightensBoundsWhereItActuallyTakesEffect()} is the subtle one.
 * {@link UUIDBroadcasterCache#configure} assigns maxPerClient / maxTotal / messageTTL from
 * init parameters, so a subclass that sets them in its constructor is silently overwritten
 * at startup. The bounds must be applied in an overridden {@code configure}, and this test
 * fails if someone moves them.</p>
 */
class ServiceAnnotationInstallationTest {

    @Test
    void filterIsInstalledOnEveryBroadcaster() {
        assertTrue(RedactingFilter.class.isAnnotationPresent(BroadcasterFilterService.class));
    }

    @Test
    void presenceListenerIsInstalled() {
        assertTrue(PresenceRegistry.class.isAnnotationPresent(BroadcasterListenerService.class));
    }

    @Test
    void rateLimiterIsInstalledAsAnInterceptor() {
        assertTrue(RateLimitInterceptor.class.isAnnotationPresent(AtmosphereInterceptorService.class));
    }

    @Test
    void replayCacheInspectorAndCacheListenerAreInstalled() {
        assertTrue(ReplayCache.class.isAnnotationPresent(BroadcasterCacheService.class));
        assertTrue(RecentOnlyInspector.class.isAnnotationPresent(BroadcasterCacheInspectorService.class));
        assertTrue(CacheAuditListener.class.isAnnotationPresent(BroadcasterCacheListenerService.class));
    }

    @Test
    void replayCacheTightensBoundsWhereItActuallyTakesEffect() throws NoSuchMethodException {
        Method configure = ReplayCache.class.getDeclaredMethod(
                "configure", org.atmosphere.cpr.AtmosphereConfig.class);
        assertEquals(ReplayCache.class, configure.getDeclaringClass(),
                "bounds set anywhere but an overridden configure() are wiped by "
                        + "UUIDBroadcasterCache.configure reading init parameters");

        assertTrue(ReplayCache.MAX_PER_CLIENT > 0 && ReplayCache.MAX_PER_CLIENT < 1000,
                "must tighten the 1000 default, was " + ReplayCache.MAX_PER_CLIENT);
        assertTrue(ReplayCache.MAX_TOTAL > 0 && ReplayCache.MAX_TOTAL < 100_000,
                "must tighten the 100000 default, was " + ReplayCache.MAX_TOTAL);
        assertNotEquals(0L, ReplayCache.MESSAGE_TTL_MS, "an unbounded TTL defeats the cap");
    }

    @Test
    void rateLimiterDeclaresABoundedTrackingTable() {
        assertTrue(RateLimitInterceptor.MAX_TRACKED > 0,
                "a map keyed by client id with no cap is a memory-exhaustion vector");
        assertTrue(RateLimitInterceptor.LIMIT > 0 && RateLimitInterceptor.WINDOW_MS > 0);
    }
}
