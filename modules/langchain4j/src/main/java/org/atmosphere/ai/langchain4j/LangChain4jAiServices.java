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
package org.atmosphere.ai.langchain4j;

import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.atmosphere.ai.AgentExecutionContext;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Helper for routing an Atmosphere prompt through a LangChain4j {@link AiServices}-backed
 * interface instead of the runtime's default {@code ChatRequest} builder.
 *
 * <p>{@link AiServices} is LangChain4j's declarative API: a Java interface
 * annotated with {@link dev.langchain4j.service.SystemMessage} and
 * {@link dev.langchain4j.service.UserMessage} that LC4j proxies into a fully-wired
 * call (system prompt, conversation memory, tools, RAG, output parsing) when the
 * caller invokes a method on the proxy. Without this bridge an Atmosphere caller
 * had to choose: drive everything through Atmosphere's pipeline, or build their
 * own {@code AiServices} call and skip Atmosphere streaming. This bridge lets
 * the user keep their {@code AiServices} interface as the canonical model and
 * still flow tokens through {@link org.atmosphere.ai.StreamingSession}.</p>
 *
 * <h2>Why a metadata sidecar</h2>
 *
 * <p>The bridge object rides on {@link AgentExecutionContext#metadata()} under
 * {@link #METADATA_KEY} — same convention as
 * {@link org.atmosphere.ai.llm.CacheHint} and
 * {@code org.atmosphere.ai.spring.SpringAiAdvisors}. Keeps {@code modules/ai}
 * free of any LC4j dependency; the {@link TokenStream} type is only resolved
 * inside {@code modules/langchain4j} where it's already provided.</p>
 *
 * <h2>What the runtime does when a bridge is present</h2>
 *
 * <p>{@link LangChain4jAgentRuntime} reads the bridge via {@link #from} at the
 * top of {@code doExecuteWithHandle(...)}. When non-null, the runtime <em>bypasses</em>
 * its own prompt assembly: no {@code ChatRequest.builder()}, no system-prompt
 * threading, no tool-spec wiring, no history replay. Those concerns belong to
 * the user's {@link AiServices} interface — that's the entire point of choosing
 * AiServices. The runtime just calls {@link Invoker#invoke(String)} with
 * {@code context.message()}, gets a {@link TokenStream}, and bridges its
 * callbacks into the {@link org.atmosphere.ai.StreamingSession}:</p>
 * <ul>
 *   <li>{@code onPartialResponse(String)} → {@code session.send(String)}</li>
 *   <li>{@code onCompleteResponse(...)} → {@code session.complete()} + token usage</li>
 *   <li>{@code onError(Throwable)} → {@code session.error(Throwable)}</li>
 * </ul>
 *
 * <p>Gateway admission, outer retry, and lifecycle listeners still wrap the
 * call — the bridge replaces only the dispatch primitive, not the surrounding
 * Atmosphere safety rail.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // 1. Define your AiServices interface (LC4j idiomatic):
 * public interface MovieAssistant {
 *     @SystemMessage("You are a movie expert. Reply concisely.")
 *     TokenStream chat(@UserMessage String message);
 * }
 *
 * // 2. Build it once and stash on a Spring bean:
 * @Bean
 * MovieAssistant assistant(StreamingChatModel model) {
 *     return AiServices.create(MovieAssistant.class, model);
 * }
 *
 * // 3. Attach via an interceptor so every Atmosphere prompt routes through it:
 * @Component
 * class AssistantInterceptor implements AiInterceptor {
 *     private final MovieAssistant assistant;
 *     AssistantInterceptor(MovieAssistant assistant) { this.assistant = assistant; }
 *
 *     @Override
 *     public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
 *         var bridge = LangChain4jAiServices.of(assistant::chat);
 *         return request.withMetadata(Map.of(LangChain4jAiServices.METADATA_KEY, bridge));
 *     }
 * }
 * }</pre>
 */
public final class LangChain4jAiServices {

    /**
     * Canonical metadata slot. The runtime reads from this key only.
     */
    public static final String METADATA_KEY = "langchain4j.aiservice";

    private LangChain4jAiServices() {
    }

    /**
     * Functional adapter the runtime calls to invoke the user's
     * {@link AiServices} method. The single-method shape ({@code String message
     * → TokenStream}) keeps the bridge surface small while letting callers
     * pick which AiService method handles the prompt — useful when the
     * interface exposes several methods (e.g. {@code chat}, {@code summarize},
     * {@code translate}).
     */
    @FunctionalInterface
    public interface Invoker {
        /**
         * Invoke the wrapped {@link AiServices} method with {@code message}.
         * Implementations typically just call the proxied method:
         * {@code assistant::chat}.
         */
        TokenStream invoke(String message);
    }

    /**
     * Build an {@link Invoker} from a {@link Function}. Provided so callers can
     * write {@code LangChain4jAiServices.of(assistant::chat)} without an
     * explicit lambda annotation. Exists purely as a syntactic convenience —
     * passing the method reference directly to {@link #attach} is also fine.
     */
    public static Invoker of(Function<String, TokenStream> fn) {
        if (fn == null) {
            throw new IllegalArgumentException("invoker function is required");
        }
        return fn::apply;
    }

    /**
     * Read the bridge {@link Invoker} out of {@code context.metadata()}.
     * Returns {@code null} when no slot is present (the runtime then takes
     * its default ChatRequest path). Element-type errors throw — silent drops
     * on a misconfigured bridge would mask the AiService never firing.
     */
    public static Invoker from(AgentExecutionContext context) {
        if (context == null || context.metadata() == null) {
            return null;
        }
        var slot = context.metadata().get(METADATA_KEY);
        if (slot == null) {
            return null;
        }
        if (!(slot instanceof Invoker invoker)) {
            throw new IllegalArgumentException(
                    METADATA_KEY + " must be a "
                            + Invoker.class.getName() + ", got "
                            + slot.getClass().getName());
        }
        return invoker;
    }

    /**
     * Return a new context with the bridge attached under {@link #METADATA_KEY}.
     * Replaces any previously attached invoker — unlike
     * {@code SpringAiAdvisors} which appends, an {@link AiServices} bridge is
     * exclusive: the runtime hands the prompt to exactly one invoker per
     * request, and merging two invokers has no defined semantics.
     */
    public static AgentExecutionContext attach(AgentExecutionContext context, Invoker invoker) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        if (invoker == null) {
            throw new IllegalArgumentException("invoker is required");
        }
        Map<String, Object> nextMetadata = new HashMap<>(context.metadata());
        nextMetadata.put(METADATA_KEY, invoker);
        return context.withMetadata(Map.copyOf(nextMetadata));
    }
}
