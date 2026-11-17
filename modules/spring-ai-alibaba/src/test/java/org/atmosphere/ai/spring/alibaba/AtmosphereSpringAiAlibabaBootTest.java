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
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots a real {@link SpringApplication} and asserts the {@code llm.*} →
 * {@code spring.ai.openai.*} bridge ran, whichever key and type the running
 * Spring Boot line loads it through: Spring Boot 4.2 reads only
 * {@code org.springframework.boot.EnvironmentPostProcessor}, and on Spring
 * Boot 3 the bridge runs as an application listener. No post-processor type is
 * named here, so the same source compiles and runs on every line.
 */
class AtmosphereSpringAiAlibabaBootTest {

    @Configuration(proxyBeanMethods = false)
    static class NoBeans {
    }

    @Test
    void springBootRunsTheBridgeAndLlmSettingsReachSpringAi() {
        // Spring AI 1.x appends /v1 itself, so the bridge drops it from the base URL.
        var app = new SpringApplication(NoBeans.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setEnvironment(withoutProcessEnvironment());
        try (var context = app.run("--llm.api-key=sk-boot-test", "--llm.model=gpt-4o-mini",
                "--llm.base-url=https://llm.example.test/v1")) {
            var environment = context.getEnvironment();
            assertTrue(environment.getPropertySources().contains("atmosphere-spring-ai-alibaba-llm-bridge"),
                    "the atmosphere-spring-ai-alibaba-llm-bridge property source is missing");
            assertEquals("sk-boot-test", environment.getProperty("spring.ai.openai.api-key"));
            assertEquals("https://llm.example.test", environment.getProperty("spring.ai.openai.base-url"));
            assertEquals("gpt-4o-mini", environment.getProperty("spring.ai.openai.chat.options.model"));
        }
    }

    /** The shell running the build may export {@code LLM_*}; it must not decide the outcome. */
    private static StandardEnvironment withoutProcessEnvironment() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return environment;
    }
}
