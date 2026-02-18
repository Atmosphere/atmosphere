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
import org.atmosphere.cpr.Broadcaster;

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
     * Start a new streaming session that broadcasts to all resources on the given broadcaster.
     *
     * @param broadcaster the broadcaster to send tokens through
     * @param resourceUuid the UUID of the originating resource (for targeted delivery)
     * @return a new streaming session
     */
    public static StreamingSession start(Broadcaster broadcaster, String resourceUuid) {
        return new DefaultStreamingSession(UUID.randomUUID().toString(), broadcaster, resourceUuid);
    }

    /**
     * Start a new streaming session for the given resource.
     *
     * @param resource the atmosphere resource
     * @return a new streaming session
     */
    public static StreamingSession start(AtmosphereResource resource) {
        return start(resource.getBroadcaster(), resource.uuid());
    }

    /**
     * Start a new streaming session with a specific session ID.
     *
     * @param sessionId    a caller-provided session ID (for correlation)
     * @param broadcaster  the broadcaster
     * @param resourceUuid the UUID of the originating resource
     * @return a new streaming session
     */
    public static StreamingSession start(String sessionId, Broadcaster broadcaster, String resourceUuid) {
        return new DefaultStreamingSession(sessionId, broadcaster, resourceUuid);
    }
}
