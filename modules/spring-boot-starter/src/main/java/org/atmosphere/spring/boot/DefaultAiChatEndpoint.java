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
import org.atmosphere.ai.annotation.Prompt;

/**
 * Default AI chat endpoint used when no user-defined {@code @AiEndpoint} is detected.
 * Delegates entirely to the auto-resolved {@link org.atmosphere.ai.AgentRuntime} backend
 * via {@link StreamingSession#stream(String)}.
 */
final class DefaultAiChatEndpoint {

    @Prompt
    void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
