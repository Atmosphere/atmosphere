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
package org.atmosphere.samples.springboot.aichat;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI chat endpoint using the built-in OpenAI-compatible client.
 * When no API key is configured, falls back to demo mode with
 * simulated streaming responses.
 *
 * <p>The {@link AiEndpoint} annotation replaces the boilerplate of
 * {@code @ManagedService} + {@code @Ready} + {@code @Disconnect} + {@code @Message}.
 * The resolved {@link org.atmosphere.ai.AiSupport} (auto-detected from the classpath)
 * handles the actual LLM communication.</p>
 */
@AiEndpoint(path = "/atmosphere/ai-chat",
        systemPromptResource = "prompts/system-prompt.md")
public class AiChat {

    private static final Logger logger = LoggerFactory.getLogger(AiChat.class);

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("Received prompt: {}", message);

        var settings = AiConfig.get();
        if (settings == null || settings.client().apiKey() == null || settings.client().apiKey().isBlank()) {
            DemoResponseProducer.stream(message, session);
            return;
        }

        session.stream(message);
    }
}
