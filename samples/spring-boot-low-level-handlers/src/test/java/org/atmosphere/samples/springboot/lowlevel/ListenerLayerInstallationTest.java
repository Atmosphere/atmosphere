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
package org.atmosphere.samples.springboot.lowlevel;

import org.atmosphere.config.service.AsyncSupportListenerService;
import org.atmosphere.config.service.AtmosphereFrameworkListenerService;
import org.atmosphere.config.service.AtmosphereResourceListenerService;
import org.atmosphere.cpr.AsyncSupportListener;
import org.atmosphere.cpr.AtmosphereFrameworkListener;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three observability layers this sample exists to contrast. Each needs both the service
 * annotation (so the framework installs it) and the matching interface (so it has something
 * to install) — one without the other is dead code that looks wired.
 */
class ListenerLayerInstallationTest {

    @Test
    void resourceLayerIsInstalled() {
        assertTrue(ConnectionHealth.class.isAnnotationPresent(AtmosphereResourceListenerService.class));
        assertTrue(AtmosphereResourceEventListener.class.isAssignableFrom(ConnectionHealth.class));
    }

    @Test
    void transportLayerIsInstalled() {
        assertTrue(TransportHealth.class.isAnnotationPresent(AsyncSupportListenerService.class));
        assertTrue(AsyncSupportListener.class.isAssignableFrom(TransportHealth.class));
    }

    @Test
    void frameworkLayerIsInstalled() {
        assertTrue(FrameworkUptime.class.isAnnotationPresent(AtmosphereFrameworkListenerService.class));
        assertTrue(AtmosphereFrameworkListener.class.isAssignableFrom(FrameworkUptime.class));
    }

    @Test
    void uptimeReportsConfirmedStateNotIntent() {
        // Nothing has initialised the framework in a unit test, so startedAt must still be
        // null and uptime zero. A version that stamped the clock in a constructor or in
        // onPreInit would report "ready" before init completed.
        assertNull(FrameworkUptime.startedAt(),
                "uptime must start when onPostInit runs, never at class-load or onPreInit");
        assertTrue(FrameworkUptime.uptime().isZero());
    }

    @Test
    void theRawHandlerDoesNotAlsoCarryListenerAnnotations() {
        // The listeners are installed globally by their own annotations; duplicating them on
        // the handler would double-count every event.
        assertFalse(OpsFeedHandler.class.isAnnotationPresent(AtmosphereResourceListenerService.class));
    }
}
