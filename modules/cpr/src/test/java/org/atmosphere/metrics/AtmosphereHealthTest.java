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

import static org.testng.Assert.*;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.util.ExecutorsFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Map;

public class AtmosphereHealthTest {

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;

    @BeforeMethod
    public void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
    }

    @AfterMethod
    public void tearDown() throws Exception {
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    public void testHealthyFramework() {
        var health = new AtmosphereHealth(config.framework());
        Map<String, Object> status = health.check();

        assertEquals(status.get("status"), "UP");
        assertNotNull(status.get("version"));
        assertEquals(status.get("connections"), 0);
        assertTrue((int) status.get("broadcasters") >= 0);
        assertTrue(health.isHealthy());
    }

    @Test
    public void testHealthWithBroadcasters() throws Exception {
        factory.get(DefaultBroadcaster.class, "room1");
        factory.get(DefaultBroadcaster.class, "room2");

        var health = new AtmosphereHealth(config.framework());
        Map<String, Object> status = health.check();

        assertEquals(status.get("status"), "UP");
        assertTrue((int) status.get("broadcasters") >= 2);
    }

    @Test
    public void testDestroyedFramework() {
        config.framework().destroy();

        var health = new AtmosphereHealth(config.framework());
        Map<String, Object> status = health.check();

        assertEquals(status.get("status"), "DOWN");
        assertFalse(health.isHealthy());
        // No connection/broadcaster details when DOWN
        assertNull(status.get("connections"));
    }
}
