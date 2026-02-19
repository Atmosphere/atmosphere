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
package org.atmosphere.ai.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an AI chat endpoint. Eliminates the boilerplate of
 * {@code @ManagedService} + {@code @Ready} + {@code @Disconnect} + {@code @Message}
 * for AI streaming use cases.
 *
 * <p>The annotated class must have exactly one method annotated with {@link Prompt}.
 * That method receives the user's message and a
 * {@link org.atmosphere.ai.StreamingSession} for streaming tokens back.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @AiEndpoint(path = "/atmosphere/ai-chat",
 *             systemPrompt = "You are a helpful assistant.")
 * public class MyAiChat {
 *
 *     @Prompt
 *     public void onPrompt(String message, StreamingSession session) {
 *         var settings = AiConfig.get();
 *         var request = ChatCompletionRequest.builder(settings.model())
 *                 .system(systemPrompt)
 *                 .user(message)
 *                 .build();
 *         settings.client().streamChatCompletion(request, session);
 *     }
 * }
 * }</pre>
 *
 * <p>The framework automatically:</p>
 * <ul>
 *   <li>Configures broadcaster cache and inactive timeout</li>
 *   <li>Logs connect/disconnect events</li>
 *   <li>Creates a {@link org.atmosphere.ai.StreamingSession} per message</li>
 *   <li>Invokes the {@code @Prompt} method on a virtual thread</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AiEndpoint {

    /**
     * The URL path for this AI endpoint.
     */
    String path();

    /**
     * Maximum inactive timeout in milliseconds before the connection is closed.
     * Defaults to 120 seconds.
     */
    long timeout() default 120_000L;

    /**
     * Optional system prompt passed as context. The {@code @Prompt} method
     * can retrieve this via {@code AiEndpoint} annotation on its declaring class.
     */
    String systemPrompt() default "";
}
