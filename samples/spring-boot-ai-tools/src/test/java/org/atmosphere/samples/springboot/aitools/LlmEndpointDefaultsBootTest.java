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
package org.atmosphere.samples.springboot.aitools;

import org.atmosphere.ai.AiConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots a real {@link SpringApplication} with the sample's own
 * {@code application.yml} and asserts the endpoint defaults are applied, on
 * whichever Spring Boot line the build targets: the sample is built on Spring
 * Boot 4 and, under {@code -Pspring-boot3}, on Spring Boot 3. Spring Boot 4.2
 * reads only {@code org.springframework.boot.EnvironmentPostProcessor}; on
 * Spring Boot 3 the defaults run as an application listener. No
 * post-processor type is named here, so the source compiles on every line.
 */
class LlmEndpointDefaultsBootTest {

    @Configuration(proxyBeanMethods = false)
    static class NoBeans {
    }

    @Test
    void springBootAppliesTheEndpointDefaults() {
        var app = new SpringApplication(NoBeans.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setEnvironment(withoutProcessEnvironment());
        // application.yml declares llm.mode/model/base-url as empty strings.
        try (var context = app.run("--llm.mode=remote", "--llm.model=gemini-2.5-flash")) {
            var environment = context.getEnvironment();
            assertTrue(environment.getPropertySources().contains("ai-tools-llm-endpoint-defaults"),
                    "the ai-tools-llm-endpoint-defaults property source is missing");
            assertEquals(AiConfig.GEMINI_ENDPOINT, environment.getProperty("llm.base-url"));
        }
    }

    @Test
    void atmosphereAiSettingsReachTheEndpointBeforeItIsDerived() {
        // LLM_MODE= and LLM_MODEL= exported blank, atmosphere.ai.mode=local: the
        // starter's AtmosphereAiLlmPropertyBridge maps atmosphere.ai.* onto the
        // blank llm.*, and the endpoint must be derived from that, not from the
        // blank llm.* (which resolves to Gemini: a local run sent to Google).
        // Spring Boot itself orders the starter's bridge and these defaults here,
        // as a post-processor pair on Spring Boot 4 and as the Spring Boot 3
        // starter's post-processor then this sample's listener on Spring Boot 3.
        var app = new SpringApplication(NoBeans.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setEnvironment(withoutProcessEnvironment());
        try (var context = app.run("--llm.mode=", "--llm.model=",
                "--atmosphere.ai.mode=local", "--atmosphere.ai.model=qwen2.5:7b")) {
            var baseUrl = context.getEnvironment().getProperty("llm.base-url");
            assertEquals(AiConfig.resolveBaseUrl("local", null, "qwen2.5:7b"), baseUrl);
            assertTrue(baseUrl.contains("11434"), baseUrl);
        }
    }

    /** The shell running the build may export {@code LLM_*}; it must not decide the outcome. */
    private static StandardEnvironment withoutProcessEnvironment() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return environment;
    }
}
