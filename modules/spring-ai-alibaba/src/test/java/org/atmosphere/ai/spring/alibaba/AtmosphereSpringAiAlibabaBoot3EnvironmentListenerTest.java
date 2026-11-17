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
package org.atmosphere.ai.spring.alibaba;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Spring Boot 3 path of the {@code llm.*} &rarr; {@code spring.ai.openai.*}
 * bridge: an application listener that maps only where Spring Boot 4's
 * post-processor type is absent, and a mapping that runs at most once per
 * environment when both paths fire.
 */
class AtmosphereSpringAiAlibabaBoot3EnvironmentListenerTest {

    @Configuration(proxyBeanMethods = false)
    static class NoBeans {
    }

    @Test
    void theListenerMapsOnlyWithoutSpringBoot4sPostProcessorType() {
        var withType = environment(Map.of("llm.api-key", "sk-test"));
        new AtmosphereSpringAiAlibabaBoot3EnvironmentListener(true).onApplicationEvent(prepared(withType));
        assertFalse(withType.getPropertySources().contains(SpringAiAlibabaLlmBridge.SOURCE_NAME),
                "with Spring Boot 4's post-processor type present the post-processor maps, not the listener");

        var withoutType = environment(Map.of("llm.api-key", "sk-test"));
        new AtmosphereSpringAiAlibabaBoot3EnvironmentListener(false).onApplicationEvent(prepared(withoutType));
        assertEquals("sk-test", withoutType.getProperty("spring.ai.openai.api-key"));
    }

    @Test
    void theListenerDetectsSpringBoot4sPostProcessorTypeOnThisClasspath() {
        // This module is built on Spring Boot 4: the listener Spring Boot creates must stay inert.
        var env = environment(Map.of("llm.api-key", "sk-test"));
        new AtmosphereSpringAiAlibabaBoot3EnvironmentListener().onApplicationEvent(prepared(env));
        assertNull(env.getProperty("spring.ai.openai.api-key"));
    }

    @Test
    void bothPathsOnOneApplicationMapOnceAndKeepWhatTheUserSet() {
        var app = new SpringApplication(NoBeans.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        var start = new StandardEnvironment();
        start.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        app.setEnvironment(start);
        // The post-processor runs from spring.factories; this listener runs as on Spring Boot 3.
        app.addListeners(new AtmosphereSpringAiAlibabaBoot3EnvironmentListener(false));
        try (var context = app.run("--llm.api-key=sk-boot-test", "--llm.model=gpt-4o-mini",
                "--spring.ai.openai.chat.options.model=user-model")) {
            var env = context.getEnvironment();
            // The second run adds nothing: the source holds only what was unset.
            var mapped = (MapPropertySource) env.getPropertySources().get(SpringAiAlibabaLlmBridge.SOURCE_NAME);
            assertEquals(Map.of("spring.ai.openai.api-key", "sk-boot-test"), mapped.getSource());
            assertEquals("user-model", env.getProperty("spring.ai.openai.chat.options.model"));
        }
    }

    @Test
    void aSecondRunLeavesTheFirstRunsPropertySourceAlone() {
        // The first run's source is found by name: the second neither replaces it
        // nor maps llm.model, still unmapped here.
        var env = environment(Map.of("llm.api-key", "sk-test", "llm.model", "gpt-4o-mini"));
        var first = new MapPropertySource(SpringAiAlibabaLlmBridge.SOURCE_NAME,
                new HashMap<>(Map.of("spring.ai.openai.api-key", "sk-test")));
        env.getPropertySources().addLast(first);
        SpringAiAlibabaLlmBridge.apply(env);
        assertSame(first, env.getPropertySources().get(SpringAiAlibabaLlmBridge.SOURCE_NAME));
        assertNull(env.getProperty("spring.ai.openai.chat.options.model"));
        assertTrue(env.getPropertySources().contains(SpringAiAlibabaLlmBridge.SOURCE_NAME));
    }

    private static StandardEnvironment environment(Map<String, Object> properties) {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addLast(new MapPropertySource("test", new HashMap<>(properties)));
        return env;
    }

    private static ApplicationEnvironmentPreparedEvent prepared(StandardEnvironment env) {
        var event = mock(ApplicationEnvironmentPreparedEvent.class);
        when(event.getEnvironment()).thenReturn(env);
        return event;
    }
}
