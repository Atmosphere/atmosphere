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
package org.atmosphere.quarkus.deployment;

import io.quarkus.test.QuarkusExtensionTest;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the cache {@code @BuildStep} (Spring Boot parity for
 * {@code AtmosphereCacheAutoConfiguration}). When
 * {@code quarkus.atmosphere.cache-enabled=true} the deployment processor
 * threads the {@code broadcasterCacheClass} init param onto the
 * {@link io.quarkus.undertow.deployment.ServletBuildItem} and registers
 * {@code BoundedMemoryCache} + {@code MessageAckInterceptor} for native
 * image reflection. The test inspects the running framework to confirm the
 * cache and the ack interceptor are actually installed — would FAIL if the
 * build step did not produce the init params, since Atmosphere would fall
 * back to the no-op default cache and never instantiate the interceptor.
 */
public class AtmosphereCacheBuildStepTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(AtmosphereCacheBuildStepTest.class))
            .overrideConfigKey("quarkus.atmosphere.packages",
                    "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.atmosphere.cache-enabled", "true")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @Test
    public void cacheBoundEndToEnd() {
        AtmosphereFramework framework = LazyAtmosphereConfigurator.getFramework();
        assertNotNull(framework, "framework should be initialized");

        // (1) cache class wired
        String cacheClass = framework.getAtmosphereConfig().getInitParameter(
                ApplicationConfig.BROADCASTER_CACHE);
        assertEquals("org.atmosphere.cache.BoundedMemoryCache", cacheClass,
                "broadcasterCacheClass init param must be set to BoundedMemoryCache");

        // (2) MessageAckInterceptor installed
        boolean ackPresent = framework.interceptors().stream()
                .anyMatch(i -> i.getClass().getName()
                        .equals("org.atmosphere.interceptor.MessageAckInterceptor"));
        assertTrue(ackPresent,
                "MessageAckInterceptor must be installed when cache is enabled "
                        + "(present interceptors=" + framework.interceptors() + ")");
    }
}
