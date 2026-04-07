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
package org.atmosphere.admin.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Queries the Micrometer {@link MeterRegistry} for Atmosphere metrics
 * and returns structured snapshots for the admin dashboard.
 *
 * @since 4.0
 */
public final class MetricsController {

    private static final String ATMOSPHERE_PREFIX = "atmosphere.";

    private final MeterRegistry registry;

    public MetricsController(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Return a structured snapshot of all Atmosphere metrics grouped
     * by category.
     */
    public Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>();

        // Connections
        var connections = new LinkedHashMap<String, Object>();
        connections.put("active", gaugeValue("atmosphere.connections.active"));
        connections.put("total", counterValue("atmosphere.connections.total"));
        connections.put("disconnects", counterValue("atmosphere.connections.disconnects"));
        result.put("connections", connections);

        // Messages
        var messages = new LinkedHashMap<String, Object>();
        messages.put("broadcast", counterValue("atmosphere.messages.broadcast"));
        messages.put("delivered", counterValue("atmosphere.messages.delivered"));
        result.put("messages", messages);

        // Broadcast latency
        var latency = new LinkedHashMap<String, Object>();
        var timer = registry.find("atmosphere.broadcast.timer").timer();
        if (timer != null) {
            latency.put("count", timer.count());
            latency.put("meanMs", round(timer.mean(TimeUnit.MILLISECONDS)));
            latency.put("maxMs", round(timer.max(TimeUnit.MILLISECONDS)));
            latency.put("totalTimeMs", round(timer.totalTime(TimeUnit.MILLISECONDS)));
        }
        result.put("broadcastLatency", latency);

        // Broadcasters
        result.put("activeBroadcasters", gaugeValue("atmosphere.broadcasters.active"));

        // Rooms
        var rooms = new LinkedHashMap<String, Object>();
        rooms.put("active", gaugeValue("atmosphere.rooms.active"));
        result.put("rooms", rooms);

        // Cache
        var cache = new LinkedHashMap<String, Object>();
        cache.put("size", gaugeValue("atmosphere.cache.size"));
        cache.put("hits", functionCounterValue("atmosphere.cache.hits"));
        cache.put("misses", functionCounterValue("atmosphere.cache.misses"));
        cache.put("evictions", functionCounterValue("atmosphere.cache.evictions"));
        result.put("cache", cache);

        // Backpressure
        var backpressure = new LinkedHashMap<String, Object>();
        backpressure.put("drops", functionCounterValue("atmosphere.backpressure.drops"));
        backpressure.put("disconnects", functionCounterValue("atmosphere.backpressure.disconnects"));
        result.put("backpressure", backpressure);

        // AI metrics
        var ai = new LinkedHashMap<String, Object>();
        ai.put("activeSessions", gaugeValue("atmosphere.ai.active_sessions"));
        ai.put("promptsTotal", counterValue("atmosphere.ai.prompts.total"));
        ai.put("streamingTextsTotal", counterValue("atmosphere.ai.streaming_texts.total"));
        ai.put("errorsTotal", counterValue("atmosphere.ai.errors.total"));
        var promptTimer = registry.find("atmosphere.ai.prompt.duration").timer();
        if (promptTimer != null) {
            ai.put("promptLatencyMeanMs", round(promptTimer.mean(TimeUnit.MILLISECONDS)));
        }
        var responseTimer = registry.find("atmosphere.ai.response.duration").timer();
        if (responseTimer != null) {
            ai.put("responseLatencyMeanMs", round(responseTimer.mean(TimeUnit.MILLISECONDS)));
        }
        result.put("ai", ai);

        return result;
    }

    /**
     * List all Atmosphere meters with their current values.
     */
    public List<Map<String, Object>> listMeters() {
        var result = new ArrayList<Map<String, Object>>();
        for (Meter meter : registry.getMeters()) {
            if (!meter.getId().getName().startsWith(ATMOSPHERE_PREFIX)) {
                continue;
            }
            var info = new LinkedHashMap<String, Object>();
            info.put("name", meter.getId().getName());
            info.put("type", meter.getId().getType().name());
            info.put("description", meter.getId().getDescription());

            // Extract tags
            var tags = new LinkedHashMap<String, String>();
            meter.getId().getTags().forEach(t -> tags.put(t.getKey(), t.getValue()));
            if (!tags.isEmpty()) {
                info.put("tags", tags);
            }

            // Extract value based on type
            switch (meter) {
                case Gauge g -> info.put("value", round(g.value()));
                case Counter c -> info.put("value", round(c.count()));
                case FunctionCounter fc -> info.put("value", round(fc.count()));
                case Timer t -> {
                    info.put("count", t.count());
                    info.put("meanMs", round(t.mean(TimeUnit.MILLISECONDS)));
                    info.put("maxMs", round(t.max(TimeUnit.MILLISECONDS)));
                    info.put("totalTimeMs", round(t.totalTime(TimeUnit.MILLISECONDS)));
                }
                default -> info.put("value", "unsupported");
            }

            result.add(info);
        }
        return result;
    }

    private double gaugeValue(String name) {
        var gauge = registry.find(name).gauge();
        return gauge != null ? round(gauge.value()) : 0;
    }

    private double counterValue(String name) {
        var counter = registry.find(name).counter();
        return counter != null ? round(counter.count()) : 0;
    }

    private double functionCounterValue(String name) {
        var counter = registry.find(name).functionCounter();
        return counter != null ? round(counter.count()) : 0;
    }

    private static double round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0;
        }
        return Math.round(value * 100.0) / 100.0;
    }
}
