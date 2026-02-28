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
package org.atmosphere.samples.springboot.langchain4jchat;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI chat endpoint powered by LangChain4j (auto-detected via classpath).
 *
 * <p>The {@code @AiEndpoint} annotation + {@code session.stream(message)} pattern
 * means this code is transport-agnostic AND AI-framework-agnostic. LangChain4j is
 * auto-detected because {@code atmosphere-langchain4j} is on the classpath, which
 * registers {@link org.atmosphere.ai.langchain4j.LangChain4jAiSupport} via ServiceLoader.</p>
 */
@AiEndpoint(path = "/atmosphere/langchain4j-chat",
        systemPromptResource = "prompts/system-prompt.md")
public class LangChain4jChat {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jChat.class);

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
