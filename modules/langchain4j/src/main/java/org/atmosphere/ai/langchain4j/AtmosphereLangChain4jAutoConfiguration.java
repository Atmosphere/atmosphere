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

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that wires LangChain4j into Atmosphere transparently:
 *
 * <ol>
 *   <li>Builds a default {@link OpenAiStreamingChatModel} from {@code llm.*}
 *       properties when no {@link StreamingChatModel} bean exists (mirrors
 *       Koog/ADK/SK adapter footprints so {@code atmosphere-langchain4j}
 *       works as a transparent dependency swap on {@code spring-boot-ai-chat}).</li>
 *   <li>Bridges whichever {@link StreamingChatModel} bean wins (auto-built
 *       above or user-supplied) into {@link LangChain4jAgentRuntime} so
 *       {@code @AiEndpoint} methods can stream via
 *       {@code session.stream(message)}.</li>
 * </ol>
 *
 * <p>User beans take precedence via {@link ConditionalOnMissingBean} —
 * supplying a custom {@link StreamingChatModel} (Anthropic, Ollama, Bedrock,
 * etc.) overrides the default cleanly.</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = "dev.langchain4j.model.chat.StreamingChatModel")
public class AtmosphereLangChain4jAutoConfiguration {

    private static final Logger logger =
        LoggerFactory.getLogger(AtmosphereLangChain4jAutoConfiguration.class);

    /**
     * Build a model when there is something to talk to — a credentialed remote
     * endpoint, or a locally served one.
     *
     * <p>The condition used to be an API key alone. A local backend (Ollama,
     * vLLM, LM Studio) needs no credentials, so with {@code llm.mode=local} no
     * model was built and the runtime failed with "StreamingChatModel not
     * configured" the moment a prompt arrived. That stayed hidden because
     * {@code DemoAgentRuntime} out-prioritised every real runtime while the key
     * was blank, so the sample answered from the canned script and looked
     * healthy. A missing key says nothing about whether a model is reachable.</p>
     *
     * <p>{@code LLM_MODE} is checked alongside {@code llm.mode} because several
     * samples configure the backend entirely through the environment and declare
     * no {@code llm} block at all — keying only on the mapped property fixed the
     * samples that happened to declare it and left the rest broken.</p>
     *
     * <p>Both {@code LLM_MODE} readings go through the environment rather than
     * {@code System.getenv}, so the condition and the body cannot disagree about
     * what {@code local} means. The environment also sees system properties, so
     * with {@code System.getenv} in the body a {@code -DLLM_MODE=local} run
     * opened the gate and then resolved the <em>remote</em> endpoint. No test
     * pins that difference: the resolved base URL is not readable from a built
     * {@link OpenAiStreamingChatModel} without reflecting through its {@code
     * internal} client, which would break on every LangChain4j bump. Treat this
     * as one source of truth rather than as a fixed defect — the misconfiguration
     * surfaces as a 401 at request time, not at startup.</p>
     */
    @Bean
    @ConditionalOnMissingBean(StreamingChatModel.class)
    @ConditionalOnExpression(
            "'${llm.api-key:}' != '' or '${llm.mode:}' == 'local' or '${LLM_MODE:}' == 'local'")
    public StreamingChatModel atmosphereLangChain4jStreamingChatModel(
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.mode:}") String mode,
            @Value("${LLM_MODE:}") String environmentMode,
            @Value("${llm.model:gpt-4o-mini}") String model) {

        var local = "local".equalsIgnoreCase(mode)
                || "local".equalsIgnoreCase(environmentMode);

        // Spring's @Value default only kicks in when the property is absent;
        // an empty value still binds, which would NPE OpenAiStreamingChatModel
        // (baseUrl cannot be null or blank). Apply the fallback here — Ollama's
        // OpenAI-compatible endpoint for local mode, OpenAI otherwise.
        var resolvedBaseUrl = (baseUrl == null || baseUrl.isBlank())
                ? (local ? "http://localhost:11434/v1" : "https://api.openai.com/v1")
                : baseUrl;

        // A local server ignores the credential but the builder still rejects a
        // blank one, so send a placeholder rather than failing to construct.
        var resolvedApiKey = (apiKey == null || apiKey.isBlank())
                ? (local ? "not-needed-for-local" : apiKey)
                : apiKey;

        logger.info("Auto-building LC4j OpenAiStreamingChatModel model={} endpoint={} local={}",
                model, resolvedBaseUrl, local);
        return OpenAiStreamingChatModel.builder()
                .apiKey(resolvedApiKey)
                .baseUrl(resolvedBaseUrl)
                .modelName(model)
                .build();
    }

    @Bean
    @ConditionalOnBean(StreamingChatModel.class)
    LangChain4jAgentRuntime langChain4jAiSupportBridge(StreamingChatModel model) {
        // Offer, never bind: an explicit setModel(...) call from application
        // code owns the binding; the context bean is only the default.
        LangChain4jAgentRuntime.offerModel(model);
        return new LangChain4jAgentRuntime();
    }
}
