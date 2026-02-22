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
package org.atmosphere.metrics;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.util.ExecutorsFactory;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AtmosphereHealthTest {

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;

    @BeforeEach
    public void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
    }

    @AfterEach
    public void tearDown() throws Exception {
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    public void testHealthyFramework() {
        var health = new AtmosphereHealth(config.framework());
        Map<String, Object> status = health.check();

        assertEquals("UP", status.get("status"));
        assertNotNull(status.get("version"));
        assertEquals(0, status.get("connections"));
        assertTrue((int) status.get("broadcasters") >= 0);
        assertTrue(health.isHealthy());
    }

    @Test
    public void testHealthWithBroadcasters() throws Exception {
        factory.get(DefaultBroadcaster.class, "room1");
        factory.get(DefaultBroadcaster.class, "room2");

        var health = new AtmosphereHealth(config.framework());
        Map<String, Object> status = health.check();

        assertEquals("UP", status.get("status"));
        assertTrue((int) status.get("broadcasters") >= 2);
    }

    @Test
    public void testDestroyedFramework() {
        config.framework().destroy();

        var health = new AtmosphereHealth(config.framework());
        Map<String, Object> status = health.check();

        assertEquals("DOWN", status.get("status"));
        assertFalse(health.isHealthy());
        // No connection/broadcaster details when DOWN
        assertNull(status.get("connections"));
    }
}
