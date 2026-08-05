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
 * Delegates entirely to the auto-resolved {@link org.atmosphere.agent.AgentRuntime} backend
 * via {@link StreamingSession#stream(String)}.
 */
final class DefaultAiChatEndpoint {

    /**
     * The {@code @Prompt} method, resolved once by name.
     *
     * <p>The registrar previously reached for {@code getDeclaredMethods()[0]}.
     * That is unspecified even on the JVM — the order of the returned array
     * carries no guarantee — and it broke outright under GraalVM, where the
     * array is empty unless the methods are registered for reflection. Binding
     * the method here keeps the lookup next to the declaration it depends on,
     * so adding a second method to this class cannot silently change which one
     * the registrar picks.</p>
     */
    static final java.lang.reflect.Method PROMPT_METHOD = promptMethod();

    private static java.lang.reflect.Method promptMethod() {
        try {
            return DefaultAiChatEndpoint.class.getDeclaredMethod(
                    "onPrompt", String.class, StreamingSession.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "DefaultAiChatEndpoint.onPrompt(String, StreamingSession) is missing; "
                            + "the default AI endpoint cannot be registered without it", e);
        }
    }

    @Prompt
    void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
