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
package org.atmosphere.ai;

import org.atmosphere.cpr.AtmosphereResource;

import java.util.UUID;

/**
 * Factory for creating {@link StreamingSession} instances.
 *
 * <pre>{@code
 * StreamingSession session = StreamingSessions.start(resource);
 * session.send("Hello");
 * session.send(" world");
 * session.complete();
 * }</pre>
 */
public final class StreamingSessions {

    private StreamingSessions() {
    }

    /**
     * Start a new streaming session for the given resource.
     * Messages are written directly to the resource, bypassing the broadcaster
     * to avoid re-triggering {@code @Message} handlers.
     *
     * @param resource the atmosphere resource
     * @return a new streaming session
     */
    public static StreamingSession start(AtmosphereResource resource) {
        return new DefaultStreamingSession(UUID.randomUUID().toString(), resource);
    }

    /**
     * Start a new streaming session with a specific session ID.
     *
     * @param sessionId a caller-provided session ID (for correlation)
     * @param resource  the atmosphere resource
     * @return a new streaming session
     */
    public static StreamingSession start(String sessionId, AtmosphereResource resource) {
        return new DefaultStreamingSession(sessionId, resource);
    }
}
