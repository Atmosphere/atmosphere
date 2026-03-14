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

import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;

/**
 * Test handler for /ai/error-recovery endpoint.
 * <p>
 * Prompt format:
 * <ul>
 *   <li>{@code "error:N"} — emit N streaming texts, then error</li>
 *   <li>{@code "normal"} — emit 5 streaming texts, then complete</li>
 * </ul>
 */
public class ErrorRecoveryTestHandler implements AtmosphereHandler {

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("error-recovery-handler").start(() -> {
                var session = StreamingSessions.start(resource);
                var client = selectClient(trimmed);
                var request = ChatCompletionRequest.of(client.modelName(), trimmed);
                client.streamChatCompletion(request, session);
            });
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()) {
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw) {
            var inner = raw.message();
            if (inner instanceof String json) {
                event.getResource().getResponse().write(json);
                event.getResource().getResponse().flushBuffer();
            }
        }
    }

    @Override
    public void destroy() {
    }

    private FakeLlmClient selectClient(String prompt) {
        if (prompt.startsWith("error:")) {
            int afterText = Integer.parseInt(prompt.substring("error:".length()));
            return FakeLlmClient.erroring("error-model", afterText,
                    "Hello", " from", " the", " AI", " assistant");
        }
        return FakeLlmClient.withTexts("default-model",
                "Hello", " from", " the", " AI", ".");
    }
}
