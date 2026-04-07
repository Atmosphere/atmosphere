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
package org.atmosphere.admin.framework;

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read operations for the core framework: broadcasters, resources, handlers,
 * and interceptors.
 *
 * @since 4.0
 */
public final class FrameworkController {

    private final AtmosphereFramework framework;

    public FrameworkController(AtmosphereFramework framework) {
        this.framework = framework;
    }

    /**
     * List all active broadcasters with summary information.
     */
    public List<Map<String, Object>> listBroadcasters() {
        BroadcasterFactory factory = framework.getBroadcasterFactory();
        if (factory == null) {
            return List.of();
        }
        Collection<Broadcaster> broadcasters = factory.lookupAll();
        var result = new ArrayList<Map<String, Object>>(broadcasters.size());
        for (Broadcaster b : broadcasters) {
            var info = new LinkedHashMap<String, Object>();
            info.put("id", b.getID());
            info.put("className", b.getClass().getSimpleName());
            info.put("resourceCount", b.getAtmosphereResources().size());
            info.put("isDestroyed", b.isDestroyed());
            result.add(info);
        }
        return result;
    }

    /**
     * Get detailed information about a specific broadcaster.
     */
    public Optional<Map<String, Object>> getBroadcaster(String id) {
        BroadcasterFactory factory = framework.getBroadcasterFactory();
        if (factory == null) {
            return Optional.empty();
        }
        Broadcaster b = factory.lookup(id, false);
        if (b == null) {
            return Optional.empty();
        }
        var info = new LinkedHashMap<String, Object>();
        info.put("id", b.getID());
        info.put("className", b.getClass().getName());
        info.put("resourceCount", b.getAtmosphereResources().size());
        info.put("isDestroyed", b.isDestroyed());
        info.put("scope", b.getScope().name());

        var resources = new ArrayList<Map<String, Object>>();
        for (AtmosphereResource r : b.getAtmosphereResources()) {
            resources.add(resourceSummary(r));
        }
        info.put("resources", resources);
        return Optional.of(info);
    }

    /**
     * List all connected resources across all broadcasters.
     */
    public List<Map<String, Object>> listResources() {
        BroadcasterFactory factory = framework.getBroadcasterFactory();
        if (factory == null) {
            return List.of();
        }
        var result = new ArrayList<Map<String, Object>>();
        for (Broadcaster b : factory.lookupAll()) {
            for (AtmosphereResource r : b.getAtmosphereResources()) {
                var info = resourceSummary(r);
                info.put("broadcaster", b.getID());
                result.add(info);
            }
        }
        return result;
    }

    /**
     * List all registered handlers with their paths.
     */
    public List<Map<String, Object>> listHandlers() {
        var result = new ArrayList<Map<String, Object>>();
        for (var entry : framework.getAtmosphereHandlers().entrySet()) {
            var info = new LinkedHashMap<String, Object>();
            info.put("path", entry.getKey());
            info.put("className", entry.getValue().atmosphereHandler().getClass().getSimpleName());
            result.add(info);
        }
        return result;
    }

    /**
     * List all interceptors in the processing chain.
     */
    public List<Map<String, Object>> listInterceptors() {
        var result = new ArrayList<Map<String, Object>>();
        for (var interceptor : framework.interceptors()) {
            var info = new LinkedHashMap<String, Object>();
            info.put("className", interceptor.getClass().getSimpleName());
            result.add(info);
        }
        return result;
    }

    private Map<String, Object> resourceSummary(AtmosphereResource r) {
        var info = new LinkedHashMap<String, Object>();
        info.put("uuid", r.uuid());
        info.put("transport", r.transport().name());
        info.put("isSuspended", r.isSuspended());
        info.put("isResumed", r.isResumed());
        return info;
    }
}
