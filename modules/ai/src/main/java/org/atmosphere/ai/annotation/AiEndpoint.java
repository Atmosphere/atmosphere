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

import org.atmosphere.ai.AiCapability;
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
 * {@link org.atmosphere.ai.StreamingSession} for streaming texts back.</p>
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

    /**
     * Whether to enable automatic conversation memory for this endpoint.
     * When {@code true}, the framework accumulates user/assistant turns per
     * {@link org.atmosphere.cpr.AtmosphereResource} and injects the history
     * into every {@link org.atmosphere.ai.AiRequest} so all adapters get
     * multi-turn context for free.
     *
     * <p>Memory is cleared automatically when the client disconnects.</p>
     */
    boolean conversationMemory() default false;

    /**
     * Maximum number of messages to retain in conversation memory per client.
     * Only relevant when {@link #conversationMemory()} is {@code true}.
     * Defaults to 20 messages (10 turns).
     */
    int maxHistoryMessages() default 20;

    /**
     * Tool provider classes to expose at this endpoint. Each class should
     * contain methods annotated with {@link AiTool}. Tools from these classes
     * are registered globally and made available to the AI model at this endpoint.
     *
     * <p>Default: empty (all globally registered tools are available).</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * @AiEndpoint(path = "/chat", tools = {WeatherTools.class, CalendarTools.class})
     * }</pre>
     */
    Class<?>[] tools() default {};

    /**
     * Tool provider classes to exclude from this endpoint. Only relevant when
     * {@link #tools()} is empty (i.e., all tools are available by default).
     *
     * <p>Example:</p>
     * <pre>{@code
     * @AiEndpoint(path = "/public-chat", excludeTools = {AdminTools.class})
     * }</pre>
     */
    Class<?>[] excludeTools() default {};

    /**
     * Fallback strategy for model routing. When set to anything other than
     * {@link org.atmosphere.ai.ModelRouter.FallbackStrategy#NONE}, the framework
     * will try alternative AI backends if the primary one fails.
     */
    String fallbackStrategy() default "NONE";

    /**
     * {@link AiGuardrail} classes to apply to this endpoint. Guardrails run
     * before the LLM call (inspecting the request) and after (inspecting
     * the response).
     *
     * <p>Execution order: guardrails → rate limit → RAG → [LLM] → guardrails → metrics</p>
     */
    Class<? extends org.atmosphere.ai.AiGuardrail>[] guardrails() default {};

    /**
     * {@link ContextProvider} classes to use for RAG context augmentation
     * at this endpoint.
     */
    Class<? extends org.atmosphere.ai.ContextProvider>[] contextProviders() default {};

    /**
     * Whether to auto-discover {@link org.atmosphere.ai.ContextProvider} implementations
     * via {@link java.util.ServiceLoader}. When {@code true}, discovered providers are
     * merged with those declared in {@link #contextProviders()}, avoiding duplicates.
     *
     * <p>Defaults to {@code false} so that endpoints without RAG are not accidentally
     * augmented just because {@code atmosphere-rag} is on the classpath.</p>
     */
    boolean autoDiscoverContextProviders() default false;

    /**
     * Override the model name for this specific endpoint. When non-empty,
     * this value is used instead of the global {@code AiConfig.get().model()}.
     *
     * <p>Example: {@code @AiEndpoint(path = "/premium", model = "gpt-4o")}</p>
     */
    String model() default "";

    /**
     * Capabilities that the selected {@link org.atmosphere.ai.AiSupport} backend
     * must satisfy for this endpoint. The framework validates at startup and
     * fails fast with a clear error if the backend cannot provide all required
     * capabilities.
     *
     * <p>Example:</p>
     * <pre>{@code
     * @AiEndpoint(path = "/tools-chat",
     *             requires = {AiCapability.TOOL_CALLING, AiCapability.CONVERSATION_MEMORY})
     * }</pre>
     *
     * <p>Default: empty (no capability requirements enforced).</p>
     */
    AiCapability[] requires() default {};

    /**
     * {@link org.atmosphere.cpr.BroadcastFilter} classes to auto-register on the
     * broadcaster for this endpoint. Filters are instantiated via the framework's
     * {@link org.atmosphere.cpr.AtmosphereFramework#newClassInstance} for DI support.
     *
     * <p>Example:</p>
     * <pre>{@code
     * @AiEndpoint(path = "/chat",
     *             filters = {CostMeteringFilter.class, PiiRedactionFilter.class})
     * }</pre>
     */
    Class<? extends org.atmosphere.cpr.BroadcastFilter>[] filters() default {};

    /**
     * Target Java type for structured output. When set to anything other than
     * {@code Void.class}, the framework instructs the LLM to produce JSON
     * conforming to this type's schema and parses the output into a typed entity.
     *
     * <p>The framework will:</p>
     * <ol>
     *   <li>Append JSON schema instructions to the system prompt via
     *       {@link org.atmosphere.ai.StructuredOutputParser#schemaInstructions}</li>
     *   <li>Emit {@link org.atmosphere.ai.AiEvent.EntityStart} when streaming begins</li>
     *   <li>Emit {@link org.atmosphere.ai.AiEvent.StructuredField} events as fields are parsed</li>
     *   <li>Emit {@link org.atmosphere.ai.AiEvent.EntityComplete} with the fully-parsed entity</li>
     * </ol>
     *
     * <p>Example: {@code @AiEndpoint(path = "/extract", responseAs = MovieReview.class)}</p>
     */
    Class<?> responseAs() default Void.class;

    /**
     * Prompt caching policy for this endpoint. When set to anything other
     * than {@link org.atmosphere.ai.llm.CacheHint.CachePolicy#NONE}, the
     * pipeline seeds every request's {@link org.atmosphere.ai.llm.CacheHint}
     * with this policy before dispatching to the runtime. Runtimes that
     * support provider-side caching (Spring AI / LC4j / Built-in OpenAI
     * path) emit {@code prompt_cache_key}; the pipeline-level response
     * cache also honors the hint regardless of runtime.
     *
     * <p>Example: {@code @AiEndpoint(path = "/chat", promptCache = CachePolicy.CONSERVATIVE)}</p>
     */
    org.atmosphere.ai.llm.CacheHint.CachePolicy promptCache()
            default org.atmosphere.ai.llm.CacheHint.CachePolicy.NONE;

    /**
     * Per-request retry policy override. When set to anything other than
     * the {@link Retry} default ({@code maxRetries = -1} sentinel), the
     * pipeline threads a fresh {@link org.atmosphere.ai.RetryPolicy} into
     * every request's context, overriding the client-level default. Useful
     * for endpoints that need tighter or looser retry semantics than the
     * global default.
     *
     * <p>Example: {@code @AiEndpoint(path = "/strict", retry = @Retry(maxRetries = 0))}</p>
     */
    Retry retry() default @Retry;

    /**
     * Inline retry-policy configuration for {@link AiEndpoint#retry()}.
     * Default values match {@link org.atmosphere.ai.RetryPolicy#DEFAULT}
     * except for the sentinel {@code maxRetries = -1} which signals "use
     * the client-level default" at the pipeline layer.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Retry {
        /** Sentinel {@code -1} means "inherit client default". */
        int maxRetries() default -1;
        long initialDelayMs() default 1000;
        long maxDelayMs() default 30_000;
        double backoffMultiplier() default 2.0;
    }
}
