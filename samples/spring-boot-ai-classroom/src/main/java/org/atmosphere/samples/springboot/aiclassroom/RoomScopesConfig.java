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
package org.atmosphere.samples.springboot.aiclassroom;

import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-room scope configuration loaded from
 * {@code atmosphere-classroom-scopes.yaml}. Operators edit YAML, restart
 * the sample, every room's scope changes without a recompile.
 *
 * <p>This is the YAML-backed twin of the Java-defined ROOMS map the
 * {@link RoomContextInterceptor} previously held inline. The
 * {@link Rooms} bean published here is what the interceptor now consumes.
 * Missing YAML on the classpath is tolerated — the interceptor falls
 * back to the hard-coded general room so the sample still runs.</p>
 */
@Configuration
public class RoomScopesConfig {

    private static final Logger logger = LoggerFactory.getLogger(RoomScopesConfig.class);
    private static final String ROOMS_YAML = "atmosphere-classroom-scopes.yaml";

    /** Result bean — published rooms keyed by the {@code {room}} path param. */
    public record Rooms(String defaultKey, Map<String, Room> byKey) { }

    /** Per-room config pair — the system prompt and the governance scope. */
    public record Room(String systemPrompt, ScopeConfig scope) { }

    @Bean
    public Rooms rooms() {
        var resource = new ClassPathResource(ROOMS_YAML);
        if (!resource.exists()) {
            logger.warn("No {} on classpath — classroom rooms will fall back to hard-coded defaults",
                    ROOMS_YAML);
            return new Rooms("general", Map.of());
        }
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        var yaml = new Yaml(new SafeConstructor(options));
        try (var in = resource.getInputStream()) {
            var document = yaml.load(in);
            if (!(document instanceof Map<?, ?> doc)) {
                throw new IllegalStateException(ROOMS_YAML + ": expected a YAML mapping at root");
            }
            @SuppressWarnings("unchecked")
            var raw = (Map<String, Object>) doc;
            var rooms = parse(raw);
            // Interceptors are built via reflection (@AiEndpoint loader)
            // rather than Spring, so we hand them the registry statically.
            RoomContextInterceptor.installRooms(rooms);
            return rooms;
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + ROOMS_YAML, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Rooms parse(Map<String, Object> root) {
        var defaultKey = asString(root.get("default"), "general");
        var rawRooms = root.get("rooms");
        if (!(rawRooms instanceof Map<?, ?> roomsMap)) {
            throw new IllegalStateException(ROOMS_YAML + ": 'rooms' must be a mapping");
        }
        var parsed = new LinkedHashMap<String, Room>();
        for (var entry : roomsMap.entrySet()) {
            var key = entry.getKey().toString();
            if (!(entry.getValue() instanceof Map<?, ?> cfg)) {
                throw new IllegalStateException(ROOMS_YAML
                        + ": rooms." + key + " must be a mapping");
            }
            parsed.put(key, parseRoom(key, (Map<String, Object>) cfg));
        }
        logger.info("Loaded {} classroom rooms from {}: {}",
                parsed.size(), ROOMS_YAML, parsed.keySet());
        return new Rooms(defaultKey, Map.copyOf(parsed));
    }

    private Room parseRoom(String key, Map<String, Object> cfg) {
        var systemPrompt = asString(cfg.get("systemPrompt"), "").trim();
        if (systemPrompt.isBlank()) {
            throw new IllegalStateException("room '" + key + "' missing systemPrompt");
        }
        var purpose = asString(cfg.get("purpose"), "").trim();
        var forbidden = asStringList(cfg.get("forbiddenTopics"));
        var onBreach = AgentScope.Breach.valueOf(
                asString(cfg.get("onBreach"), "POLITE_REDIRECT").toUpperCase(Locale.ROOT));
        var redirectMessage = asString(cfg.get("redirectMessage"),
                ScopeConfig.DEFAULT_REDIRECT_MESSAGE);
        var tier = AgentScope.Tier.valueOf(
                asString(cfg.get("tier"), "RULE_BASED").toUpperCase(Locale.ROOT));
        var threshold = asDouble(cfg.get("similarityThreshold"), 0.45);
        var postResponseCheck = asBoolean(cfg.get("postResponseCheck"), false);

        var scope = new ScopeConfig(purpose, forbidden, onBreach,
                redirectMessage, tier, threshold,
                postResponseCheck, false, "");
        return new Room(systemPrompt, scope);
    }

    private static String asString(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        throw new IllegalStateException("expected a YAML list, got: " + value.getClass());
    }

    private static double asDouble(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        if (value == null) return fallback;
        return Double.parseDouble(value.toString());
    }

    private static boolean asBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value == null) return fallback;
        return Boolean.parseBoolean(value.toString());
    }
}
