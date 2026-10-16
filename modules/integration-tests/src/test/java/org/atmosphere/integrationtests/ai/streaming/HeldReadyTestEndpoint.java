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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * An endpoint whose {@code @Ready} is held until the test releases it, the way
 * a slow {@code @Ready} (a database lookup) holds a connection's set-up; it then
 * sets {@code ai.userId} from the {@code token} query parameter. Each prompt is
 * answered with a reply naming the prompt and the {@code ai.userId} it ran
 * under.
 */
@AiEndpoint(path = HeldReadyTestEndpoint.PATH)
public class HeldReadyTestEndpoint {

    public static final String PATH = "/atmosphere/held-ready";

    /** Counted down when {@code @Ready} starts. */
    public static volatile CountDownLatch ENTERED = new CountDownLatch(1);

    /** Released to let {@code @Ready} finish. */
    public static volatile CountDownLatch RELEASE = new CountDownLatch(1);

    @Ready
    public void onReady(AtmosphereResource resource) {
        ENTERED.countDown();
        try {
            RELEASE.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        var token = resource.getRequest().getParameter("token");
        if (token != null && !token.isBlank()) {
            resource.getRequest().setAttribute("ai.userId", token);
        }
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        session.send("reply-to:" + message + "|" + resource.getRequest().getAttribute("ai.userId"));
        session.complete();
    }
}
