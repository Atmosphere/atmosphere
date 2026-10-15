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
package org.atmosphere.integrationtests.ai.longpolling;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Counts {@code @Ready} per tracking id and, as the personal-assistant sample
 * does, sets {@code ai.userId} from the {@code token} query parameter there;
 * each prompt records the {@code ai.userId} it runs under and is answered with a
 * reply that names it.
 */
@AiEndpoint(path = LongPollingConnectionTestEndpoint.PATH)
public class LongPollingConnectionTestEndpoint {

    public static final String PATH = "/atmosphere/long-polling-connection";

    /** Tracking id -> how many times {@code @Ready} ran for it. */
    public static final Map<String, Integer> READY = new ConcurrentHashMap<>();

    /** {@code prompt + "|" + ai.userId} for every {@code @Prompt} invocation. */
    public static final List<String> PROMPTS = new CopyOnWriteArrayList<>();

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
        PROMPTS.add(message + "|" + resource.getRequest().getAttribute("ai.userId"));
        session.send("reply-to:" + message);
        session.complete();
    }
}
