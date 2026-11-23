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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Spring AI decision model defined as a bean reaches Atmosphere's
 * {@link DecisionModelResolver} for the context's {@link AiEnvironment}, and
 * leaves it when the context closes.
 */
class SpringAiDecisionModelRegistrarTest {

    private AiEnvironment environment;

    @BeforeEach
    void setUp() {
        DecisionModelResolver.reset();
        environment = AiEnvironment.create(key -> null);
        Beans.environment = environment;
    }

    @AfterEach
    void tearDown() {
        environment.close();
        DecisionModelResolver.reset();
        Beans.environment = null;
    }

    @Configuration(proxyBeanMethods = false)
    static class Beans {
        static AiEnvironment environment;

        // The test owns the environment: the context must not close it, or the
        // registration would end with the environment instead of the context.
        @Bean(destroyMethod = "")
        AiEnvironment aiEnvironment() {
            return environment;
        }

        @Bean
        org.springframework.ai.decision.DecisionModel decisionModel() {
            return prompt -> DecisionResponse.builder(prompt).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoModels {
        @Bean
        org.springframework.ai.decision.DecisionModel first() {
            return prompt -> DecisionResponse.builder(prompt).build();
        }

        @Bean
        org.springframework.ai.decision.DecisionModel second() {
            return prompt -> DecisionResponse.builder(prompt).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrimaryModel {
        @Bean
        @Primary
        org.springframework.ai.decision.DecisionModel primary() {
            return prompt -> DecisionResponse.builder(prompt).build();
        }
    }

    @Test
    void theContextsDecisionModelIsResolvedUntilTheContextCloses() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Beans.class, AtmosphereSpringAiAutoConfiguration.DecisionModelConfiguration.class);
            context.refresh();

            assertInstanceOf(SpringAiDecisionModel.class,
                    DecisionModelResolver.resolve(environment).orElseThrow(),
                    "the bean answers this application's decisions");
        }
        assertFalse(DecisionModelResolver.resolve(environment).orElse(null) instanceof SpringAiDecisionModel,
                "the registration leaves with the context");
    }

    @Test
    void severalModelsAndNoneIsPrimaryRegistersNothing() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Beans.class, TwoModels.class,
                    AtmosphereSpringAiAutoConfiguration.DecisionModelConfiguration.class);
            context.refresh();

            assertFalse(context.getBean(SpringAiDecisionModelRegistrar.class).registered());
        }
    }

    @Test
    void aPrimaryModelIsTheOneRegistered() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Beans.class, PrimaryModel.class,
                    AtmosphereSpringAiAutoConfiguration.DecisionModelConfiguration.class);
            context.refresh();

            assertTrue(context.getBean(SpringAiDecisionModelRegistrar.class).registered());
        }
    }
}
