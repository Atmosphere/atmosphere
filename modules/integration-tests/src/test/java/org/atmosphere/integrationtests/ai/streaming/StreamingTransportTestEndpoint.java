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
package org.atmosphere.integrationtests.ai.streaming;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Counts {@code @Ready} per tracking id and sets {@code ai.userId} from the
 * {@code token} query parameter there; each prompt is answered with a reply
 * that names the prompt and the {@code ai.userId} it ran under. A prompt
 * starting with {@code hold:} sends its first part, then waits for
 * {@link #RELEASE} before sending the second and completing.
 */
@AiEndpoint(path = StreamingTransportTestEndpoint.PATH)
public class StreamingTransportTestEndpoint {

    public static final String PATH = "/atmosphere/streaming-transport";

    /** Tracking id -> how many times {@code @Ready} ran for it. */
    public static final Map<String, Integer> READY = new ConcurrentHashMap<>();

    /** Prompt -> the id of the run it started. */
    public static final Map<String, String> RUN_IDS = new ConcurrentHashMap<>();

    /** Released to let a {@code hold:} prompt finish. */
    public static volatile CountDownLatch RELEASE = new CountDownLatch(1);

    @Ready
    public void onReady(AtmosphereResource resource) {
        READY.merge(resource.uuid(), 1, Integer::sum);
        var request = resource.getRequest();
        var token = request.getParameter("token");
        if (request.getAttribute("ai.userId") == null && token != null && !token.isBlank()) {
            request.setAttribute("ai.userId", token);
        }
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        session.runId().ifPresent(runId -> RUN_IDS.put(message, runId));
        if (message.startsWith("hold:")) {
            session.send("part-1:" + message);
            try {
                RELEASE.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            session.send("part-2:" + message);
            session.complete();
            return;
        }
        session.send("reply-to:" + message + "|" + resource.getRequest().getAttribute("ai.userId"));
        session.complete();
    }
}
