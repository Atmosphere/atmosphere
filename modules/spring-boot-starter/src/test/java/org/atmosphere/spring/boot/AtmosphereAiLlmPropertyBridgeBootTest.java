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
package org.atmosphere.spring.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots a real {@link SpringApplication} and asserts the bridge's effect, so
 * the test holds whichever key and type the running Spring Boot line loads
 * post-processors through: Spring Boot 4.2 reads only
 * {@code org.springframework.boot.EnvironmentPostProcessor}, and a bridge
 * registered under the removed {@code org.springframework.boot.env} key never
 * ran there.
 */
class AtmosphereAiLlmPropertyBridgeBootTest {

    @Configuration(proxyBeanMethods = false)
    static class NoBeans {
    }

    @Test
    void springBootRunsTheBridgeAndAtmosphereAiSettingsBecomeLlmSettings() {
        var app = new SpringApplication(NoBeans.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setEnvironment(withoutProcessEnvironment());
        try (var context = app.run("--atmosphere.ai.model=boot-bridge-model",
                "--atmosphere.ai.base-url=https://llm.example.test/v1")) {
            var environment = context.getEnvironment();
            assertThat(environment.getPropertySources().contains("atmosphere-ai-llm-bridge"))
                    .as("the atmosphere-ai-llm-bridge property source").isTrue();
            assertThat(environment.getProperty("llm.model")).isEqualTo("boot-bridge-model");
            assertThat(environment.getProperty("llm.base-url")).isEqualTo("https://llm.example.test/v1");
        }
    }

    @Test
    void aSecondRunLeavesTheFirstRunsPropertySourceAlone() {
        // The first run's source is found by name: the second neither replaces it
        // nor maps atmosphere.ai.model, still unmapped here.
        var environment = withoutProcessEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("application",
                new HashMap<>(Map.of("atmosphere.ai.model", "second-run-model"))));
        var first = new MapPropertySource("atmosphere-ai-llm-bridge", new HashMap<>(Map.of("llm.mode", "local")));
        environment.getPropertySources().addFirst(first);
        new AtmosphereAiLlmPropertyBridge().postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getPropertySources().get("atmosphere-ai-llm-bridge")).isSameAs(first);
        assertThat(environment.getProperty("llm.model")).isNull();
    }

    /** The shell running the build may export {@code LLM_*}; it must not decide the outcome. */
    private static StandardEnvironment withoutProcessEnvironment() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return environment;
    }
}
