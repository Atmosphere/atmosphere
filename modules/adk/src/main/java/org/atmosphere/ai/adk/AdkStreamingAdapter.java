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
package org.atmosphere.ai.adk;

import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.atmosphere.ai.AiStreamingAdapter;
import org.atmosphere.ai.StreamingSession;

/**
 * Google ADK adapter that bridges a {@link Runner}'s event stream
 * to an Atmosphere {@link StreamingSession}.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * var session = StreamingSessions.start(resource);
 * adapter.stream(new AdkRequest(runner, userId, sessionId, userMessage), session);
 * }</pre>
 *
 * @see AdkEventAdapter
 */
public class AdkStreamingAdapter implements AiStreamingAdapter<AdkStreamingAdapter.AdkRequest> {

    @Override
    public String name() {
        return "google-adk";
    }

    @Override
    public void stream(AdkRequest request, StreamingSession session) {
        session.progress("Starting ADK agent...");
        Flowable<Event> events = request.runner().runAsync(
                request.userId(),
                request.sessionId(),
                Content.fromParts(Part.fromText(request.userMessage()))
        );
        AdkEventAdapter.bridge(events, session);
    }

    /**
     * Request record wrapping ADK Runner invocation parameters.
     *
     * @param runner      the ADK runner
     * @param userId      user identifier for the ADK session
     * @param sessionId   session identifier for the ADK session
     * @param userMessage the user's message text
     */
    public record AdkRequest(Runner runner, String userId, String sessionId, String userMessage) {
    }
}
