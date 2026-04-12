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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.RetryPolicy;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Test handler for per-request RetryPolicy wire protocol (Wave 6).
 *
 * <p>Prompt "default" → context with RetryPolicy.DEFAULT, echo fields.</p>
 * <p>Prompt "custom" → context with RetryPolicy.of(5, 2s), echo fields.</p>
 * <p>Prompt "none" → context with RetryPolicy.NONE, echo fields.</p>
 */
public class RetryPolicyTestHandler implements AtmosphereHandler {

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("retry-policy-test").start(() ->
                    handlePrompt(trimmed, resource));
        }
    }

    private void handlePrompt(String prompt, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);

        var policy = switch (prompt) {
            case "custom" -> RetryPolicy.of(5, Duration.ofSeconds(2));
            case "none" -> RetryPolicy.NONE;
            default -> RetryPolicy.DEFAULT;
        };

        var context = new AgentExecutionContext(
                prompt, null, "test-model", "agent-1",
                session.sessionId(), "user-1", "conv-1",
                List.of(), null, null, List.of(),
                Map.of(), List.of(), null, null)
                .withRetryPolicy(policy);

        var rp = context.retryPolicy();
        session.sendMetadata("retry.maxRetries", rp.maxRetries());
        session.sendMetadata("retry.initialDelay", rp.initialDelay().toMillis());
        session.sendMetadata("retry.backoffMultiplier", rp.backoffMultiplier());
        session.sendMetadata("retry.maxDelay", rp.maxDelay().toMillis());

        session.emit(new AiEvent.Complete(null, Map.of()));
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
