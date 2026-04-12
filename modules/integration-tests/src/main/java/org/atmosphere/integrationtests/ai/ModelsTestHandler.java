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

import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test handler for models() runtime truth (Wave 1).
 *
 * <p>Prompt "models" → emit model names as metadata.</p>
 * <p>Prompt "capabilities" → emit capability set as metadata.</p>
 */
public class ModelsTestHandler implements AtmosphereHandler {

    private static final List<String> TEST_MODELS =
            List.of("gpt-4o", "gemini-2.5-flash", "test-model");

    private static final Set<AiCapability> TEST_CAPABILITIES =
            Set.of(AiCapability.TEXT_STREAMING, AiCapability.TOOL_CALLING,
                    AiCapability.TOOL_APPROVAL, AiCapability.SYSTEM_PROMPT);

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("models-test").start(() ->
                    handlePrompt(trimmed, resource));
        }
    }

    private void handlePrompt(String prompt, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);

        switch (prompt) {
            case "models" -> {
                session.sendMetadata("model.count", TEST_MODELS.size());
                for (int i = 0; i < TEST_MODELS.size(); i++) {
                    session.sendMetadata("model." + i, TEST_MODELS.get(i));
                }
            }
            case "capabilities" -> {
                session.sendMetadata("capability.count", TEST_CAPABILITIES.size());
                int i = 0;
                for (var cap : TEST_CAPABILITIES) {
                    session.sendMetadata("capability." + i++, cap.name());
                }
            }
            default -> {
                session.sendMetadata("model.count", TEST_MODELS.size());
                session.sendMetadata("capability.count", TEST_CAPABILITIES.size());
            }
        }

        session.emit(new AiEvent.Complete(null, Map.of()));
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
    }

    @Override
    public void destroy() {
    }
}
