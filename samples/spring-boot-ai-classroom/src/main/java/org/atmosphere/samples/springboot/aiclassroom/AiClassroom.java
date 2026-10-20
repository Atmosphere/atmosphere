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

import org.atmosphere.ai.RoomMemberIds;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.processor.AiEndpointHandler;
import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collaborative AI classroom endpoint. All clients connected to the same
 * room share a broadcaster — when one student asks a question, every
 * student in that room sees the question, then the AI response stream
 * text-by-text.
 *
 * <p>Demonstrates standard Atmosphere annotations with {@code @AiEndpoint}:</p>
 * <ul>
 *   <li>{@code {room}} path variable — read per call from the resource with
 *       {@link AiEndpointHandler#pathParam(AtmosphereResource, String)}</li>
 *   <li>{@link Ready @Ready} — invoked when a client connects and is suspended</li>
 *   <li>{@link Disconnect @Disconnect} — invoked when a client disconnects</li>
 * </ul>
 *
 * <p>The {@link RoomContextInterceptor} reads the {@code {room}} path
 * parameter and sets a room-specific system prompt (math tutor, code mentor,
 * science educator).</p>
 *
 * <p>This sample intentionally leads with the lower-level {@code @AiEndpoint}
 * rather than {@code @Agent}: {@code @Agent} has no {@code interceptors}
 * element and {@code AgentProcessor} wires an empty {@code AiInterceptor} list,
 * so the per-room {@link RoomContextInterceptor} (persona + scope per
 * {@code {room}}) could not be installed.</p>
 *
 * <p>The room is deliberately <em>not</em> an {@code @PathParam} field: one
 * {@code AiClassroom} instance serves every room, so a field holds whichever
 * room the last student joined, and a math student's prompt would be logged
 * as the code room's. Each callback reads the room of the resource it was
 * given, as {@link RoomContextInterceptor} does.</p>
 */
@AiEndpoint(path = "/atmosphere/classroom/{room}",
        systemPromptResource = "skill:classroom",
        // The collaborative headline: one student asks, EVERYONE in the room
        // sees the reply stream. Without broadcastReply the streamed frames
        // go only to the asking resource (presence still fans out, which is
        // how the 4.0.60 release-gate caught the gap: "2 online" synced while
        // answers stayed private).
        broadcastReply = true,
        // A student whose connection drops (a phone backgrounded, a train
        // tunnel) misses what the room broadcasts meanwhile: the cache keeps
        // it for the student's tracking id, and the reconnect is written the
        // classmates' turns it missed, each frame once. Without it, a whole
        // turn asked while the student was away never reaches it.
        streamCache = UUIDBroadcasterCache.class,
        interceptors = { RoomContextInterceptor.class })
@AgentScope(unrestricted = true,
        justification = "Endpoint-level scope is intentionally unrestricted — the actual scope is "
                + "installed per request by RoomContextInterceptor via "
                + "ScopePolicy.REQUEST_SCOPE_METADATA_KEY. Each of the four rooms (math / code / "
                + "science / general) carries its own ScopeConfig, so a prompt in the math room is "
                + "classified against 'mathematics tutoring' while the same prompt in the code room "
                + "is classified against 'software engineering mentoring'. One static @AgentScope "
                + "can't express this variance; per-request install is the framework-level answer.")
public class AiClassroom {

    private static final Logger logger = LoggerFactory.getLogger(AiClassroom.class);

    /**
     * The {@code {room}} this resource connected to. {@code AiEndpointHandler}
     * records it on the resource's request before {@code @Ready}; a resource
     * whose request is gone (a recycled one at disconnect) has no room left.
     */
    static String roomOf(AtmosphereResource resource) {
        var request = resource.getRequest();
        var room = request == null ? null : AiEndpointHandler.pathParam(resource, "room");
        return room == null ? "unknown" : room;
    }

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Student {} joined room '{}' (broadcaster: {})",
                resource.uuid(), roomOf(resource), resource.getBroadcaster().getID());
        broadcastPresence(resource, "join");
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        var resource = event.getResource();
        if (resource == null) {
            logger.debug("Ignoring disconnect because resource is null");
            return;
        }
        logger.info("Student {} left room '{}'", resource.uuid(), roomOf(resource));
        broadcastPresence(resource, "leave");
    }

    /**
     * Emit a presence event to everyone in the room so the classroom UI
     * can render an "{N} online" chip. Format mirrors the Room Protocol
     * {@code presence} frame:
     *
     * <pre>{"type":"presence","action":"join","memberId":"&lt;room member id&gt;","count":N}</pre>
     *
     * The member is named by its {@link RoomMemberIds room member id}, never
     * its tracking id, which every other member would receive and could act on
     * that connection with.
     *
     * Count is computed at broadcast time so a reconnecting student sees
     * the correct snapshot from the first event after rejoin, without a
     * separate query endpoint.
     */
    private void broadcastPresence(AtmosphereResource resource, String action) {
        var broadcaster = resource.getBroadcaster();
        if (broadcaster == null) {
            logger.debug("Skipping presence {} for resource {} in room '{}' because broadcaster is null",
                    action, resource.uuid(), roomOf(resource));
            return;
        }
        int count = broadcaster.getAtmosphereResources().size();
        // @Disconnect fires before the resource is removed from the set
        // in some transports, so subtract one when the leaver is still
        // present. This keeps the wire payload monotonically sensible.
        if ("leave".equals(action) && broadcaster.getAtmosphereResources().contains(resource)) {
            count = Math.max(0, count - 1);
        }
        String json = String.format(
                "{\"type\":\"presence\",\"action\":\"%s\",\"memberId\":\"%s\",\"count\":%d}",
                action, RoomMemberIds.of(resource.uuid()), count);
        // Wrap in RawMessage, as the AI streaming frames are: AiEndpointHandler
        // .onStateChange unwraps it and writes the JSON out to the client
        // transports as is. A plain-String broadcast is written out too, and is
        // never run as a prompt: prompts are dispatched to their sender's
        // connection, never broadcast.
        broadcaster.broadcast(new RawMessage(json));
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        logger.info("Classroom prompt in room '{}': {}", roomOf(resource), message);
        // Always through the pipeline: DemoAgentRuntime takes over when no
        // LLM_API_KEY is configured, otherwise the real runtime streams. The
        // per-request ScopeConfig installed by RoomContextInterceptor rides
        // on AiRequest.metadata() and is picked up by AiPipeline /
        // AiStreamingSession, so the demo path honours per-room scope
        // without a sample-local PolicyAdmissionGate hop.
        session.stream(message);
    }
}
