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
package org.atmosphere.spring.boot;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.BroadcasterLifeCyclePolicy;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.interactions.InteractionIds;
import org.atmosphere.interactions.InteractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.security.Principal;

/**
 * Atmosphere handler that streams a single background interaction live to a
 * subscribed browser. The client connects to {@code /atmosphere/interactions-stream?id=<id>}
 * over WebSocket/SSE; on connect this handler:
 *
 * <ol>
 *   <li>resolves the caller and ownership-checks the interaction (reads are
 *       scoped to the owner, same as {@code GET /api/interactions/{id}});</li>
 *   <li>binds the resource to the per-interaction broadcaster (subscribe to
 *       <em>live</em> frames first, so none are missed);</li>
 *   <li>replays the durable steps captured so far so a late joiner catches up
 *       (the browser dedupes by step sequence).</li>
 * </ol>
 *
 * <p>Subsequent steps are broadcast by {@link InteractionStreamBroadcast} as the
 * run produces them, and {@link #onStateChange} writes each to the client.</p>
 */
final class InteractionStreamHandler extends AbstractReflectorAtmosphereHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionStreamHandler.class);

    private final InteractionService service;

    InteractionStreamHandler(InteractionService service) {
        this.service = service;
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        var id = resource.getRequest().getParameter("id");
        if (!InteractionIds.isValid(id)) {
            resource.write(errorFrame("invalid or missing interaction id"));
            return;
        }
        var principal = resolvePrincipal(resource);

        // Ownership: only the owner may stream the interaction (same scope as the
        // REST read surface). Unknown / not-owned closes with a clear frame.
        var existing = service.get(id, principal);
        if (existing.isEmpty()) {
            resource.write(errorFrame("interaction not found or not owned by caller"));
            return;
        }

        // Subscribe to the live channel BEFORE snapshotting so no step emitted
        // during catch-up is lost; duplicates (a step both broadcast and in the
        // snapshot) are deduped client-side by sequence.
        var factory = resource.getAtmosphereConfig().getBroadcasterFactory();
        var broadcaster = factory.lookup(InteractionStreamFrames.channelId(id), true);
        broadcaster.setBroadcasterLifeCyclePolicy(BroadcasterLifeCyclePolicy.EMPTY_DESTROY);
        resource.setBroadcaster(broadcaster);
        resource.suspend();

        var interaction = existing.get();
        for (var step : interaction.steps()) {
            resource.write(InteractionStreamFrames.stepFrame(step));
        }
        if (interaction.isTerminal()) {
            // Already finished before the client connected — send the terminal
            // frame so the browser stops waiting.
            resource.write(InteractionStreamFrames.terminalFrame(interaction));
        }
        LOGGER.debug("Streaming interaction {} to {}", id, resource.uuid());
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isClosedByClient() || event.isClosedByApplication()) {
            return;
        }
        var message = event.getMessage();
        if (message != null) {
            event.getResource().write(message.toString());
        }
    }

    private static String errorFrame(String message) {
        // Minimal hand-built frame — avoids a serializer dependency on the error path.
        return "{\"type\":\"interaction-terminal\",\"status\":\"FAILED\",\"errorMessage\":\""
                + message.replace("\"", "\\\"") + "\"}";
    }

    private static String resolvePrincipal(AtmosphereResource resource) {
        HttpServletRequest request = resource.getRequest();
        var principal = request.getUserPrincipal();
        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            return principal.getName();
        }
        if (request.getAttribute("org.atmosphere.auth.principal") instanceof Principal attr
                && attr.getName() != null && !attr.getName().isBlank()) {
            return attr.getName();
        }
        if (request.getAttribute("ai.userId") instanceof String s && !s.isBlank()) {
            return s;
        }
        return InteractionService.ANONYMOUS;
    }
}
