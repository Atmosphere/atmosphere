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

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Framework-level health check for Atmosphere.
 *
 * <p>Provides a snapshot of the framework's health status including
 * active connections, broadcasters, and framework lifecycle state.
 * Can be used by any health check system (Spring Actuator, MicroProfile Health, etc.).</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * AtmosphereHealth health = new AtmosphereHealth(framework);
 * Map<String, Object> status = health.check();
 * // { "status": "UP", "version": "4.0.0-SNAPSHOT", "connections": 42, ... }
 * }</pre>
 *
 * @since 4.0
 */
public class AtmosphereHealth {

    private final AtmosphereFramework framework;

    public AtmosphereHealth(AtmosphereFramework framework) {
        this.framework = framework;
    }

    /**
     * Perform a health check and return status details.
     *
     * @return a map of health details
     */
    public Map<String, Object> check() {
        var result = new LinkedHashMap<String, Object>();

        boolean isUp = !framework.isDestroyed();
        result.put("status", isUp ? "UP" : "DOWN");
        result.put("version", org.atmosphere.util.Version.getRawVersion());

        if (isUp) {
            // Count active connections across all broadcasters
            BroadcasterFactory factory = framework.getBroadcasterFactory();
            int connections = 0;
            int broadcasterCount = 0;

            if (factory != null) {
                Collection<Broadcaster> broadcasters = factory.lookupAll();
                broadcasterCount = broadcasters.size();
                for (Broadcaster b : broadcasters) {
                    connections += b.getAtmosphereResources().size();
                }
            }

            result.put("connections", connections);
            result.put("broadcasters", broadcasterCount);
            result.put("handlers", framework.getAtmosphereHandlers().size());
            result.put("interceptors", framework.interceptors().size());
        }

        return result;
    }

    /**
     * @return true if the framework is running and healthy
     */
    public boolean isHealthy() {
        return !framework.isDestroyed();
    }
}
