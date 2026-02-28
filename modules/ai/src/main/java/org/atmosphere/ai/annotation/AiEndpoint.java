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

import org.atmosphere.ai.AiInterceptor;

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
     * Optional system prompt passed as context. When non-empty, the value is set
     * as a request attribute under the key
     * {@link org.atmosphere.ai.processor.AiEndpointHandler#SYSTEM_PROMPT_ATTRIBUTE}
     * so the {@code @Prompt} method can retrieve it via
     * {@code resource.getRequest().getAttribute(AiEndpointHandler.SYSTEM_PROMPT_ATTRIBUTE)}.
     *
     * <p>If {@link #systemPromptResource()} is also set, the resource file takes precedence.</p>
     */
    String systemPrompt() default "";

    /**
     * Classpath resource path to a file (typically {@code .md}) containing the system prompt.
     * When non-empty, the file is loaded once at startup via
     * {@link org.atmosphere.ai.PromptLoader#load(String)} and takes precedence
     * over {@link #systemPrompt()}.
     *
     * <p>Example: {@code systemPromptResource = "prompts/system-prompt.md"}</p>
     */
    String systemPromptResource() default "";

    /**
     * {@link AiInterceptor} classes to apply to every prompt handled by this endpoint.
     * Interceptors are executed in declaration order for {@code preProcess} (FIFO)
     * and in reverse order for {@code postProcess} (LIFO).
     *
     * <p>Example:</p>
     * <pre>{@code
     * @AiEndpoint(path = "/ai-chat",
     *             interceptors = {RagInterceptor.class, LoggingInterceptor.class})
     * }</pre>
     */
    Class<? extends AiInterceptor>[] interceptors() default {};
}
