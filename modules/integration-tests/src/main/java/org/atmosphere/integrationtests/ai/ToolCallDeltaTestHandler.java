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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.util.Map;

/**
 * Test handler for incremental tool-call argument streaming (Wave 3).
 *
 * <p>Any prompt → emits 3 toolCallDelta chunks building up
 * {@code {"city":"Montreal"}}, then consolidated ToolStart + ToolResult + Complete.</p>
 */
public class ToolCallDeltaTestHandler implements AtmosphereHandler {

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            Thread.ofVirtual().name("tool-delta-test").start(() ->
                    handlePrompt(resource));
        }
    }

    private void handlePrompt(AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);
        try {
            session.toolCallDelta("tc_001", "{\"city\":");
            Thread.sleep(10);
            session.toolCallDelta("tc_001", "\"Montreal\"");
            Thread.sleep(10);
            session.toolCallDelta("tc_001", "}");
            Thread.sleep(10);

            session.emit(new AiEvent.ToolStart("get_weather",
                    Map.of("city", "Montreal")));
            Thread.sleep(10);
            session.emit(new AiEvent.ToolResult("get_weather",
                    Map.of("temp", 22)));
            session.emit(new AiEvent.Complete(null, Map.of()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error(e);
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()
                || event.isClosedByClient() || event.isClosedByApplication()) {
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw && raw.message() instanceof String json) {
            event.getResource().getResponse().write(json);
            event.getResource().getResponse().flushBuffer();
        }
    }

    @Override
    public void destroy() {
    }
}
