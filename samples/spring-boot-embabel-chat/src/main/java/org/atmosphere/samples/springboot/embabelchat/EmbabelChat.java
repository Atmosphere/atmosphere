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
package org.atmosphere.samples.springboot.embabelchat;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI chat endpoint powered by Embabel Agent Platform (auto-detected via classpath).
 *
 * <p>The {@code @AiEndpoint} annotation + {@code session.stream(message)} pattern
 * means this code is transport-agnostic AND AI-framework-agnostic. Embabel is
 * auto-detected because {@code atmosphere-embabel} is on the classpath, which
 * registers {@link org.atmosphere.ai.embabel.EmbabelAiSupport} via ServiceLoader.</p>
 *
 * <p>In demo mode (no {@code OPENAI_API_KEY}), falls back to simulated streaming.</p>
 */
@AiEndpoint(path = "/atmosphere/embabel-chat",
        systemPrompt = "You are a helpful assistant.")
public class EmbabelChat {

    private static final Logger logger = LoggerFactory.getLogger(EmbabelChat.class);

    private static final boolean DEMO_MODE;

    static {
        var key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) {
            key = System.getenv("LLM_API_KEY");
        }
        DEMO_MODE = (key == null || key.isBlank());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("Received prompt (demo={}): {}", DEMO_MODE, message);

        if (DEMO_MODE) {
            DemoResponseProducer.stream(message, session);
            return;
        }

        session.stream(message);
    }
}
