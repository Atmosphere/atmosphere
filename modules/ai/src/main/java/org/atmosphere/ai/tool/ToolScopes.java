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
package org.atmosphere.ai.tool;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Shared identity derivation for built-in tool executors that key state per
 * conversation ({@code write_todos}, the file tools). Mirrors the exact
 * resolution {@code AiStreamingSession.stream} performs for
 * {@code AiRequest.conversationId()}: the {@code ai.conversationId} request
 * attribute wins, then the resource uuid, then the streaming session's id —
 * so tool-persisted state and conversation memory share one scope.
 */
public final class ToolScopes {

    private static final Logger logger = LoggerFactory.getLogger(ToolScopes.class);

    /** Fallback scope when no resource or session is in the injectables. */
    public static final String DEFAULT_SCOPE = "default";

    private ToolScopes() {
    }

    /**
     * Explicit conversation identity for resource-free dispatch paths.
     * Channel bridges and AG-UI build a fresh collector session per message —
     * its {@code sessionId()} is a new UUID every turn, so scoping by it
     * resets plans and workspace files each turn and steadily fills the plan
     * store's file cap (Invariants #7/#3). The pipeline stamps this record
     * with its stable {@code clientId} (channel client / AG-UI thread id) so
     * tool-persisted state survives turns with the same identity conversation
     * memory uses.
     *
     * @param id the stable conversation identity, never {@code null} or blank
     */
    public record ConversationScope(String id) {
        public ConversationScope {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("conversation id must not be blank");
            }
        }
    }

    /**
     * Derive the conversation id from a tool invocation's injectables scope.
     *
     * @param injectables the dispatch-time injectables map (may be {@code null})
     * @return the conversation id, never {@code null} or blank
     */
    public static String conversationId(Map<Class<?>, Object> injectables) {
        if (injectables == null) {
            return DEFAULT_SCOPE;
        }
        if (injectables.get(ConversationScope.class) instanceof ConversationScope scope) {
            return scope.id();
        }
        if (injectables.get(AtmosphereResource.class) instanceof AtmosphereResource resource) {
            var attr = attribute(resource, "ai.conversationId");
            if (attr != null) {
                return attr;
            }
            var uuid = resource.uuid();
            if (uuid != null && !uuid.isBlank()) {
                return uuid;
            }
        }
        var session = session(injectables);
        if (session != null && session.sessionId() != null && !session.sessionId().isBlank()) {
            return session.sessionId();
        }
        return DEFAULT_SCOPE;
    }

    /**
     * Resolve the live {@link StreamingSession} from a tool invocation's
     * injectables scope. Tries the interface key first, then an
     * assignable-type scan (concrete session types are sometimes keyed by
     * their own class).
     *
     * @param injectables the dispatch-time injectables map (may be {@code null})
     * @return the session, or {@code null} when none is in scope
     */
    public static StreamingSession session(Map<Class<?>, Object> injectables) {
        if (injectables == null) {
            return null;
        }
        if (injectables.get(StreamingSession.class) instanceof StreamingSession session) {
            return session;
        }
        for (var entry : injectables.entrySet()) {
            if (StreamingSession.class.isAssignableFrom(entry.getKey())
                    && entry.getValue() instanceof StreamingSession session) {
                return session;
            }
        }
        return null;
    }

    /**
     * Derive the agent id from a tool invocation's injectables scope,
     * falling back to the supplied registration-time default.
     *
     * @param injectables    the dispatch-time injectables map (may be {@code null})
     * @param defaultAgentId the registration-time owner (agent / endpoint name)
     * @return the agent id, never {@code null} or blank
     */
    public static String agentId(Map<Class<?>, Object> injectables, String defaultAgentId) {
        if (injectables != null
                && injectables.get(AtmosphereResource.class) instanceof AtmosphereResource r) {
            var attr = attribute(r, "ai.agentId");
            if (attr != null) {
                return attr;
            }
        }
        return defaultAgentId == null || defaultAgentId.isBlank()
                ? DEFAULT_SCOPE : defaultAgentId;
    }

    private static String attribute(AtmosphereResource resource, String name) {
        try {
            var request = resource.getRequest();
            if (request != null && request.getAttribute(name) instanceof String s
                    && !s.isBlank()) {
                return s;
            }
        } catch (RuntimeException e) {
            // A recycled resource can throw on attribute access — treat as absent.
            logger.trace("Failed to read request attribute {}: {}", name, e.toString());
        }
        return null;
    }
}
