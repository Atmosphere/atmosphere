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
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.langchain4j.LangChain4jAgentRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands the Quarkus LangChain4j-supplied {@link StreamingChatModel} CDI bean
 * to Atmosphere's {@link LangChain4jAgentRuntime} for this application: it
 * offers it to the application's {@link AiEnvironment}
 * ({@link LangChain4jAgentRuntime#offerModel(AiEnvironment, StreamingChatModel)}),
 * as {@code AtmosphereLangChain4jAutoConfiguration} does for a Spring context.
 * Offered, not bound: a model the application binds itself, with
 * {@code LangChain4jAgentRuntime.setModel(environment, model)} or the
 * process-wide {@code setModel(model)}, wins over it.
 *
 * <p>The Atmosphere Quarkus extension produces the application's
 * {@code AiEnvironment} as a CDI bean and fires it as an event when the
 * framework attaches it, before any {@code @AiEndpoint} registers and resolves
 * its runtime; that happens while the servlet (or the Vert.x route) initializes,
 * before {@code @Startup} beans are created, so this bean observes the event
 * to offer the model in time. Without the extension there is no application
 * environment, and the bridge binds the model through the deprecated
 * process-wide {@code LangChain4jAgentRuntime.setModel(model)}, as before.</p>
 *
 * <p>{@link Instance} keeps the lookup tolerant: if no {@link StreamingChatModel}
 * is on the bean graph (config missing, dev mode without an API key, etc.), we
 * log and stay quiet instead of failing app boot; the SPI-resolved runtime then
 * reports {@code isAvailable=false} on the admin endpoint, which is the same
 * fail-closed posture every other adapter uses.</p>
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
     * The application's environment, produced by the Atmosphere Quarkus
     * extension; unsatisfied when that extension is absent.
     */
    @Inject
    Instance<AiEnvironment> environments;

    // The environment the model was offered to, so each is offered (and logged) once.
    private volatile AiEnvironment offeredTo;
    // The model bean, looked up once (and its absence logged once).
    private volatile boolean looked;
    private volatile StreamingChatModel lookedUp;

    /**
     * Offers the model to the application's environment when the framework
     * attaches it, before its {@code @AiEndpoint} classes register.
     *
     * @param environment the application's environment
     */
    void onAiEnvironment(@Observes AiEnvironment environment) {
        offerModel(environment);
    }

    /**
     * {@code @Startup} creates this bean at application start, so the model is
     * offered to the application's environment even when no framework fires
     * it, or bound process-wide when there is no environment bean.
     */
    @PostConstruct
    void wireModel() {
        if (environments.isResolvable()) {
            offerModel(environments.get());
            return;
        }
        var model = model();
        if (model == null) {
            return;
        }
        bindProcessWide(model);
        logger.info("Auto-wired Quarkus LangChain4j StreamingChatModel ({}) into LangChain4jAgentRuntime "
                + "for the JVM: no application AiEnvironment bean (is atmosphere-quarkus-extension absent?)",
                model.getClass().getSimpleName());
    }

    private void offerModel(AiEnvironment environment) {
        if (offeredTo == environment) {
            return;
        }
        var model = model();
        if (model == null) {
            return;
        }
        LangChain4jAgentRuntime.offerModel(environment, model);
        offeredTo = environment;
        logger.info("Auto-wired Quarkus LangChain4j StreamingChatModel ({}) into LangChain4jAgentRuntime "
                + "for the application's AI environment {}", model.getClass().getSimpleName(), environment.name());
    }

    private StreamingChatModel model() {
        if (!looked) {
            lookedUp = lookUpModel();
            looked = true;
        }
        return lookedUp;
    }

    private StreamingChatModel lookUpModel() {
        if (handles.isUnsatisfied()) {
            logger.info("No StreamingChatModel bean found — Quarkus LangChain4j config missing? "
                    + "LangChain4jAgentRuntime will report isAvailable=false until a model is supplied.");
            return null;
        }
        if (handles.isAmbiguous()) {
            logger.info("Multiple StreamingChatModel beans found ({}). "
                    + "Skipping auto-wiring — bind the model you want Atmosphere to dispatch through with "
                    + "LangChain4jAgentRuntime.setModel(environment, model) from a bean that observes or "
                    + "injects the application's AiEnvironment.",
                    handles.stream().count());
            return null;
        }
        return handles.get();
    }

    // Without the Atmosphere Quarkus extension there is no application
    // AiEnvironment, so the process-wide binder is the only one.
    @SuppressWarnings("deprecation")
    private static void bindProcessWide(StreamingChatModel model) {
        LangChain4jAgentRuntime.setModel(model);
    }
}
