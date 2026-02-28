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

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.Gemini;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.AiSupport;
import org.atmosphere.ai.StreamingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AiSupport} implementation backed by Google ADK's {@link Runner}.
 *
 * <p>Auto-detected when {@code google-adk} is on the classpath.
 * The runner must be configured via {@link #setRunner} — typically done
 * by application configuration or Spring auto-configuration.</p>
 */
public class AdkAiSupport implements AiSupport {

    private static final Logger logger = LoggerFactory.getLogger(AdkAiSupport.class);

    private static volatile Runner runner;
    private static volatile String defaultUserId = "atmosphere-user";
    private static volatile String defaultSessionId = "atmosphere-session";

    @Override
    public String name() {
        return "google-adk";
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("com.google.adk.runner.Runner");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        if (runner != null) {
            return;
        }

        var apiKey = settings.client().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }

        if (settings.model() != null && !settings.model().startsWith("gemini")) {
            logger.warn("ADK only supports Gemini models natively. '{}' may not work. "
                    + "Consider atmosphere-spring-ai or atmosphere-langchain4j for other providers.",
                    settings.model());
        }

        var gemini = new Gemini(settings.model(), apiKey);
        var agent = LlmAgent.builder()
                .name("atmosphere-agent")
                .model(gemini)
                .instruction("You are a helpful assistant.")
                .build();
        setRunner(new InMemoryRunner(agent, "atmosphere"));
        logger.info("ADK auto-configured: model={}", settings.model());
    }

    /**
     * Set the {@link Runner} to use for streaming.
     */
    public static void setRunner(Runner adkRunner) {
        runner = adkRunner;
    }

    /**
     * Set default user and session IDs for ADK invocations.
     */
    public static void setDefaults(String userId, String sessionId) {
        defaultUserId = userId;
        defaultSessionId = sessionId;
    }

    @Override
    public void stream(AiRequest request, StreamingSession session) {
        var adkRunner = runner;
        if (adkRunner == null) {
            var settings = AiConfig.get();
            if (settings == null) {
                settings = AiConfig.fromEnvironment();
            }
            configure(settings);
            adkRunner = runner;
        }
        if (adkRunner == null) {
            throw new IllegalStateException(
                    "AdkAiSupport: Runner not configured. "
                            + "Call AdkAiSupport.setRunner() or use Spring auto-configuration.");
        }

        session.progress("Starting ADK agent...");

        var userId = request.hints().containsKey("userId")
                ? request.hints().get("userId").toString() : defaultUserId;
        var sessionId = request.hints().containsKey("sessionId")
                ? request.hints().get("sessionId").toString() : defaultSessionId;

        var events = adkRunner.runAsync(
                userId,
                sessionId,
                Content.fromParts(Part.fromText(request.message()))
        );
        AdkEventAdapter.bridge(events, session);
    }
}
