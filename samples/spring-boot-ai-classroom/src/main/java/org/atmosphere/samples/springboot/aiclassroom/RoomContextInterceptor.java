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

import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.atmosphere.ai.processor.AiEndpointHandler;
import org.atmosphere.cpr.AtmosphereResource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sets the AI system prompt AND installs a per-request {@link ScopeConfig}
 * based on the {@code {room}} path parameter. Each room gets a different
 * persona AND a different scope so that:
 *
 * <ul>
 *   <li>the <b>math</b> room admits algebra, calculus, geometry and rejects
 *       "write me python code" as off-topic;</li>
 *   <li>the <b>code</b> room admits programming questions and rejects
 *       "solve this integral" as off-topic;</li>
 *   <li>the <b>science</b> room admits physics/chemistry/biology questions;</li>
 *   <li>the <b>general</b> room admits a broader educational band.</li>
 * </ul>
 *
 * <p>Room scopes are loaded from
 * {@code atmosphere-classroom-scopes.yaml} via {@link RoomScopesConfig}.
 * Operators edit the YAML and restart the sample to tune room behavior
 * without a recompile. The inline {@code FALLBACK_ROOMS} below is the
 * last-resort default used when the framework-less test path or a missing
 * YAML loader produces an empty registry.</p>
 *
 * <p>The per-room scope is installed by placing a {@link ScopeConfig} on
 * {@link AiRequest#metadata()} under {@link ScopePolicy#REQUEST_SCOPE_METADATA_KEY}.
 * {@code AiStreamingSession.stream} pops that key, builds a transient
 * {@link ScopePolicy}, runs pre-admission + system-prompt hardening, and
 * wraps the streamed response with the matching post-response check. This
 * is the framework-level alternative to a per-endpoint class per room.</p>
 */
public class RoomContextInterceptor implements AiInterceptor {

    /**
     * Registry published by {@link RoomScopesConfig}. Held statically
     * because {@code AiInterceptor} instances are constructed
     * via {@code @AiEndpoint(interceptors = ...)} reflection, not Spring
     * bean wiring, so we can't field-inject. Spring Boot sets this once
     * via {@link #installRooms(RoomScopesConfig.Rooms)} at context start.
     */
    private static final AtomicReference<RoomScopesConfig.Rooms> ROOMS =
            new AtomicReference<>(fallbackRooms());

    /** Called from {@link RoomScopesConfig} after Spring loads the YAML. */
    public static void installRooms(RoomScopesConfig.Rooms rooms) {
        if (rooms == null || rooms.byKey().isEmpty()) {
            return;
        }
        ROOMS.set(rooms);
    }

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        var rooms = ROOMS.get();
        var room = AiEndpointHandler.pathParam(resource, "room");
        if (room == null || room.isBlank()) {
            room = rooms.defaultKey();
        }
        resource.getRequest().setAttribute("classroom.room", room);
        var selected = rooms.byKey().getOrDefault(room,
                rooms.byKey().get(rooms.defaultKey()));
        return request
                .withSystemPrompt(selected.systemPrompt())
                .withMetadata(Map.of(ScopePolicy.REQUEST_SCOPE_METADATA_KEY, selected.scope()));
    }

    /**
     * The {@link ScopeConfig} for a given room, exposed so the demo-fallback
     * {@code @Prompt} path (which bypasses {@code AiPipeline}) can carry the
     * same per-request scope into {@code PolicyAdmissionGate.admit}. Falls
     * back to the default room for unknown keys.
     */
    public static ScopeConfig scopeFor(String room) {
        var rooms = ROOMS.get();
        var key = (room == null || room.isBlank()) ? rooms.defaultKey() : room;
        var selected = rooms.byKey().getOrDefault(key,
                rooms.byKey().get(rooms.defaultKey()));
        return selected.scope();
    }

    /** Last-resort rooms when no YAML is on the classpath. */
    private static RoomScopesConfig.Rooms fallbackRooms() {
        var general = new RoomScopesConfig.Room(
                "You are a helpful classroom assistant. "
                        + "Answer questions clearly and concisely. Keep responses under 300 words.",
                new ScopeConfig(
                        "General educational assistance — broad-band tutoring across "
                                + "academic subjects",
                        List.of("medical diagnosis", "legal advice", "financial advice"),
                        AgentScope.Breach.POLITE_REDIRECT,
                        "I can only help with general educational topics.",
                        AgentScope.Tier.RULE_BASED, 0.45,
                        false, false, ""));
        return new RoomScopesConfig.Rooms("general", Map.of("general", general));
    }
}
