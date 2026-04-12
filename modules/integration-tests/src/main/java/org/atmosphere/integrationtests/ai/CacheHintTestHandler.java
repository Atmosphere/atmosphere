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
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.llm.CacheHint;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Test handler for CacheHint metadata wire protocol (Wave 4).
 *
 * <p>Prompt "conservative" → CacheHint.conservative("test-key"), emits policy + resolved key.</p>
 * <p>Prompt "aggressive" → CacheHint.aggressive("agg-key").</p>
 * <p>Prompt "none" → CacheHint.none().</p>
 * <p>Prompt "fallback" → CacheHint.conservative() (no explicit key, falls back to sessionId).</p>
 */
public class CacheHintTestHandler implements AtmosphereHandler {

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("cache-hint-test").start(() ->
                    handlePrompt(trimmed, resource));
        }
    }

    private void handlePrompt(String prompt, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);

        var hint = switch (prompt) {
            case "conservative" -> CacheHint.conservative("test-key");
            case "aggressive" -> CacheHint.aggressive("agg-key");
            case "none" -> CacheHint.none();
            case "fallback" -> CacheHint.conservative();
            default -> CacheHint.none();
        };

        var context = new AgentExecutionContext(
                prompt, null, "test-model", "agent-1",
                session.sessionId(), "user-1", "conv-1",
                List.of(), null, null, List.of(),
                Map.of(CacheHint.METADATA_KEY, hint),
                List.of(), null, null);

        var extracted = CacheHint.from(context);
        session.sendMetadata("cacheHint.policy", extracted.policy().name());
        session.sendMetadata("cacheHint.enabled", extracted.enabled());

        var resolvedKey = extracted.resolvedKey(context);
        session.sendMetadata("cacheHint.key", resolvedKey.orElse("none"));

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
