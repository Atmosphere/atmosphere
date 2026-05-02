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
package org.atmosphere.ai.quarkus.langchain4j;

import dev.langchain4j.model.chat.StreamingChatModel;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.atmosphere.ai.langchain4j.LangChain4jAgentRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pulls the Quarkus LangChain4j-supplied {@link StreamingChatModel} CDI bean
 * out of the Arc container at app startup and hands it to Atmosphere's
 * {@link LangChain4jAgentRuntime} via its static setter — the same hook
 * {@code AtmosphereLangChain4jAutoConfiguration} uses on Spring Boot.
 *
 * <p>Why this exists: {@link LangChain4jAgentRuntime} is discovered via
 * {@link java.util.ServiceLoader} and needs a model handed to it before
 * the first request lands; on Spring Boot the autoconfig wires that. On
 * Quarkus there is no Spring autoconfig, so this {@code @ApplicationScoped}
 * observer is the equivalent build-time-aware hook. {@link Instance} keeps
 * the lookup tolerant: if no {@link StreamingChatModel} is on the bean
 * graph (config missing, dev mode without an API key, etc.), we log and
 * stay quiet instead of failing app boot — the SPI-resolved runtime then
 * reports {@code isAvailable=false} on the admin endpoint, which is the
 * same fail-closed posture every other adapter uses.</p>
 *
 * <p>Native image: only requires {@code LangChain4jAgentRuntime} and its
 * SPI service file to be reachable. The deployment processor handles that.</p>
 */
@ApplicationScoped
@Startup(Interceptor.Priority.PLATFORM_BEFORE)
public class AtmosphereQuarkusLangChain4jBridge {

    private static final Logger logger =
            LoggerFactory.getLogger(AtmosphereQuarkusLangChain4jBridge.class);

    /**
     * Field-injected so the Quarkus build-time bean-removal pass sees a
     * hard dependency on {@link StreamingChatModel} — without it, packaged
     * (production-mode) jars prune the synthetic chat-model bean as
     * "unused" and the bridge then can't find anything to wire.
     * {@link Instance} keeps the dependency tolerant of "no model
     * configured" so app boot doesn't fail in that case.
     */
    @Inject
    Instance<StreamingChatModel> handles;

    /**
     * {@code @Startup} forces eager creation of this bean during Quarkus
     * application startup; combined with {@code Priority.PLATFORM_BEFORE} it
     * runs before the servlet container starts, so by the time
     * {@code QuarkusAtmosphereServlet.init()} fires its annotation scan and
     * {@code AiEndpointProcessor} resolves the {@code AgentRuntime},
     * {@code LangChain4jAgentRuntime.staticModel} is already populated and
     * the eager OpenAI HTTP client construction (which dereferences
     * {@code TlsConfigurationRegistry}, not yet ready at that point) is
     * skipped.
     */
    @PostConstruct
    void wireModel() {
        if (handles.isUnsatisfied()) {
            logger.info("No StreamingChatModel bean found — Quarkus LangChain4j config missing? "
                    + "LangChain4jAgentRuntime will report isAvailable=false until a model is supplied.");
            return;
        }
        if (handles.isAmbiguous()) {
            logger.info("Multiple StreamingChatModel beans found ({}). "
                    + "Skipping auto-wiring — call LangChain4jAgentRuntime.setModel(...) explicitly "
                    + "with the model you want Atmosphere to dispatch through.",
                    handles.stream().count());
            return;
        }
        StreamingChatModel model = handles.get();
        LangChain4jAgentRuntime.setModel(model);
        logger.info("Auto-wired Quarkus LangChain4j StreamingChatModel ({}) into LangChain4jAgentRuntime",
                model.getClass().getSimpleName());
    }
}
