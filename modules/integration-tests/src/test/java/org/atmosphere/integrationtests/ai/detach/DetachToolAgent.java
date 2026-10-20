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
package org.atmosphere.integrationtests.ai.detach;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.cpr.AtmosphereResource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * An {@code @Agent} (so an {@code AgentIdentity} is in its tools' scope) whose
 * model answers by calling {@link #lookup}, a tool that reads the connection's
 * request. Its {@code @Prompt} method waits for
 * {@link DetachTestEndpoint#RELEASE}, so the test reloads the page before the
 * run's first frame and the tool runs with the connection gone.
 */
@Agent(name = DetachToolAgent.NAME, description = "Calls a tool after the page reloaded")
public class DetachToolAgent {

    public static final String NAME = "detach-tool";

    /** What {@link #lookup} read of the request, one entry per call. */
    public static final List<String> LOOKUPS = new CopyOnWriteArrayList<>();

    /** The user a connection's {@code token} query parameter names, as an authenticating interceptor sets it. */
    @org.atmosphere.config.service.Ready
    public void onReady(AtmosphereResource resource) {
        var request = resource.getRequest();
        var token = request.getParameter("token");
        if (request.getAttribute("ai.userId") == null && token != null && !token.isBlank()) {
            request.setAttribute("ai.userId", token);
        }
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session) throws InterruptedException {
        if (!DetachTestEndpoint.RELEASE.await(30, TimeUnit.SECONDS)) {
            session.error(new IllegalStateException("never released"));
            return;
        }
        session.stream(message);
    }

    @AiTool(name = "lookup", description = "Looks a topic up")
    public String lookup(@Param(value = "topic", description = "The topic") String topic,
                         AtmosphereResource resource) {
        LOOKUPS.add(topic + "@" + resource.getRequest().getRequestURI());
        return "found " + topic;
    }
}
