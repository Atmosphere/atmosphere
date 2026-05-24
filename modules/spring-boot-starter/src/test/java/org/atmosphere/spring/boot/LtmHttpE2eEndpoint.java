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
package org.atmosphere.spring.boot;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.memory.LongTermMemoryInterceptor;
import org.atmosphere.spring.boot.LongTermMemoryHttpE2eTest.DisconnectRecorder;

/**
 * Top-level {@code @AiEndpoint} for
 * {@link LongTermMemoryHttpE2eTest}. Atmosphere's annotation scanner
 * does not reliably find nested {@code @AiEndpoint} classes — keep
 * this as a top-level type so the registrar's
 * {@code hasUserDefinedAiEndpoint} check sees it before the default
 * endpoint is registered.
 */
@AiEndpoint(path = "/atmosphere/ltm-e2e",
        systemPrompt = "You are a helpful test assistant.",
        interceptors = {LongTermMemoryInterceptor.class, DisconnectRecorder.class},
        conversationMemory = true)
public class LtmHttpE2eEndpoint {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        LongTermMemoryHttpE2eTest.LAST_PROMPT_MESSAGE.set(message);
        session.send("ack: " + message);
        session.complete();
    }
}
