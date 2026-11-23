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
package org.atmosphere.ai.spring;

import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.decision.DecisionModelResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Registers the application's Spring AI 2.1 decision model, wrapped in a
 * {@link SpringAiDecisionModel}, with {@link DecisionModelResolver} for the
 * context's {@link AiEnvironment}, once every singleton exists (so whichever
 * auto-configuration defines the model has run), and removes it when the
 * context closes. The model stays the context's: neither this class nor the
 * resolver closes it.
 *
 * <p>Nothing is registered when the context holds no Spring AI decision model,
 * when it holds several and none is {@code @Primary}, or when it has no
 * {@link AiEnvironment} (no Atmosphere framework resolves decision models for
 * it).</p>
 */
final class SpringAiDecisionModelRegistrar implements SmartInitializingSingleton, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiDecisionModelRegistrar.class);

    private final ObjectProvider<org.springframework.ai.decision.DecisionModel> models;
    private final ObjectProvider<AiEnvironment> environments;
    private volatile DecisionModelResolver.Registration registration;

    /**
     * @param models       the context's Spring AI decision models
     * @param environments the context's {@link AiEnvironment}
     */
    SpringAiDecisionModelRegistrar(ObjectProvider<org.springframework.ai.decision.DecisionModel> models,
                                          ObjectProvider<AiEnvironment> environments) {
        this.models = models;
        this.environments = environments;
    }

    @Override
    public void afterSingletonsInstantiated() {
        var count = models.stream().count();
        if (count == 0) {
            return;
        }
        var model = models.getIfUnique();
        if (model == null) {
            logger.info("{} Spring AI decision models and none is @Primary: none is registered with "
                    + "Atmosphere; mark one @Primary to use it", count);
            return;
        }
        var environment = environments.getIfAvailable();
        if (environment == null) {
            logger.info("Spring AI decision model {} is not registered with Atmosphere: the context has no "
                    + "AiEnvironment", model.getClass().getName());
            return;
        }
        registration = DecisionModelResolver.register(environment,
                new SpringAiDecisionModel(model, "spring-ai:" + model.getClass().getSimpleName()));
    }

    @Override
    public void destroy() {
        var current = registration;
        registration = null;
        if (current != null) {
            current.close();
        }
    }

    /** Whether a model is registered: test hook. */
    boolean registered() {
        return registration != null;
    }
}
