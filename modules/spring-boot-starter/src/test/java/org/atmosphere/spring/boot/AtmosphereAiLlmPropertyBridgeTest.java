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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code atmosphere.ai.mode}, {@code model}, {@code api-key} and {@code base-url}
 * reach the runtime adapters through {@code llm.*}, below {@code llm.*} and
 * {@code LLM_*}. The process environment is replaced by a controlled one, so a
 * host that exports {@code LLM_*} does not change the outcome.
 */
class AtmosphereAiLlmPropertyBridgeTest {

    /** An environment with these application properties and these environment variables only. */
    private static StandardEnvironment environment(Map<String, Object> properties, Map<String, Object> variables) {
        var environment = new StandardEnvironment();
        var sources = environment.getPropertySources();
        sources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new HashMap<>(variables)));
        sources.replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of()));
        sources.addLast(new MapPropertySource("application", new HashMap<>(properties)));
        return environment;
    }

    private static StandardEnvironment bridged(Map<String, Object> properties, Map<String, Object> variables) {
        var environment = environment(properties, variables);
        new AtmosphereAiLlmPropertyBridge().postProcessEnvironment(environment, null);
        return environment;
    }

    @Test
    void atmosphereAiBaseUrlBecomesLlmBaseUrlWhenUnset() {
        var environment = bridged(Map.of("atmosphere.ai.base-url", "http://gpu-box:8000/v1"), Map.of());
        assertThat(environment.getProperty("llm.base-url")).isEqualTo("http://gpu-box:8000/v1");
    }

    @Test
    void everySettingIsBridged() {
        var environment = bridged(Map.of(
                "atmosphere.ai.mode", "local",
                "atmosphere.ai.model", "qwen2.5:7b",
                "atmosphere.ai.api-key", "sk-bridged",
                "atmosphere.ai.base-url", "http://gpu-box:8000/v1"), Map.of());
        assertThat(environment.getProperty("llm.mode")).isEqualTo("local");
        assertThat(environment.getProperty("llm.model")).isEqualTo("qwen2.5:7b");
        assertThat(environment.getProperty("llm.api-key")).isEqualTo("sk-bridged");
        assertThat(environment.getProperty("llm.base-url")).isEqualTo("http://gpu-box:8000/v1");
    }

    @Test
    void llmPropertyWinsOverAtmosphereAi() {
        var environment = bridged(Map.of(
                "atmosphere.ai.base-url", "http://gpu-box:8000/v1",
                "atmosphere.ai.model", "qwen2.5:7b",
                "llm.base-url", "http://vllm:9000/v1",
                "llm.model", "llama3.2"), Map.of());
        assertThat(environment.getProperty("llm.base-url")).isEqualTo("http://vllm:9000/v1");
        assertThat(environment.getProperty("llm.model")).isEqualTo("llama3.2");
    }

    @Test
    void llmEnvironmentVariableWinsOverAtmosphereAi() {
        var environment = bridged(Map.of(
                        "atmosphere.ai.base-url", "http://gpu-box:8000/v1",
                        "atmosphere.ai.mode", "local"),
                Map.of("LLM_BASE_URL", "http://from-env:8000/v1", "LLM_MODE", "remote"));
        assertThat(environment.getProperty("llm.base-url")).isEqualTo("http://from-env:8000/v1");
        assertThat(environment.getProperty("llm.mode")).isEqualTo("remote");
    }

    /** {@code llm.base-url: ${LLM_BASE_URL:}} with the variable unset is blank, which every reader treats as unset. */
    @Test
    void aBlankLlmPropertyDoesNotHideAtmosphereAi() {
        var environment = bridged(Map.of(
                "atmosphere.ai.base-url", "http://gpu-box:8000/v1",
                "llm.base-url", ""), Map.of());
        assertThat(environment.getProperty("llm.base-url")).isEqualTo("http://gpu-box:8000/v1");
    }

    /** Bound as {@code AtmosphereProperties} binds it, so the relaxed spellings reach the adapters too. */
    @Test
    void relaxedAtmosphereAiNamesAreBridged() {
        var environment = bridged(Map.of(), Map.of("ATMOSPHERE_AI_BASEURL", "http://gpu-box:8000/v1",
                "ATMOSPHERE_AI_APIKEY", "sk-env"));
        assertThat(environment.getProperty("llm.base-url")).isEqualTo("http://gpu-box:8000/v1");
        assertThat(environment.getProperty("llm.api-key")).isEqualTo("sk-env");
    }

    @Test
    void nothingSetLeavesTheEnvironmentUnchanged() {
        var environment = bridged(Map.of("atmosphere.ai.base-url", "  "), Map.of());
        assertThat(environment.getPropertySources().contains(AtmosphereAiLlmPropertyBridge.SOURCE_NAME)).isFalse();
        for (var setting : AtmosphereAiLlmPropertyBridge.SETTINGS) {
            assertThat(environment.getProperty("llm." + setting)).as(setting).isNull();
        }
    }

    /**
     * Through a real {@code SpringApplication}: the bridge is registered and runs
     * after {@code application.yml} is loaded and before a post-processor that,
     * like the Spring AI, Spring AI Alibaba and Embabel ones, declares no order
     * and reads {@code llm.*}; then {@code atmosphere.ai.mode=local} with no key
     * passes the keyless gate of the LangChain4j, Semantic Kernel and AgentScope
     * auto-configurations and their {@code @Value("${llm.*}")} reads see the
     * bridged values.
     */
    @Test
    void anAdapterSeesAtmosphereAiThroughSpringApplication() {
        var hermetic = environment(Map.of(), Map.of());
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AdapterProbe.class)
                .web(WebApplicationType.NONE)
                .environment(hermetic)
                .properties(
                        "spring.config.location=classpath:/bridge/llm-bridge.yml",
                        "atmosphere.ai.mode=local",
                        LlmReadingPostProcessorStandIn.ENABLED + "=true")
                .run()) {
            var environment = context.getEnvironment();
            assertThat(environment.getProperty(LlmReadingPostProcessorStandIn.SAW_PREFIX + "base-url"))
                    .isEqualTo("http://gpu-box:8000/v1");
            assertThat(environment.getProperty(LlmReadingPostProcessorStandIn.SAW_PREFIX + "mode"))
                    .isEqualTo("local");
            assertThat(environment.getProperty(LlmReadingPostProcessorStandIn.SAW_PREFIX + "api-key"))
                    .isEqualTo("<unset>");

            var model = context.getBean(AdapterModel.class);
            assertThat(model.baseUrl()).isEqualTo("http://gpu-box:8000/v1");
            assertThat(model.model()).isEqualTo("qwen2.5:7b");
            assertThat(model.mode()).isEqualTo("local");
            assertThat(model.apiKey()).isEmpty();
        }
    }

    @Test
    void withNothingSetTheKeylessGateStaysClosed() {
        var hermetic = environment(Map.of(), Map.of());
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AdapterProbe.class)
                .web(WebApplicationType.NONE)
                .environment(hermetic)
                .properties("spring.config.location=optional:classpath:/bridge/none.yml")
                .run()) {
            assertThat(context.getBeansOfType(AdapterModel.class)).isEmpty();
            assertThat(context.getEnvironment().getPropertySources()
                    .contains(AtmosphereAiLlmPropertyBridge.SOURCE_NAME)).isFalse();
        }
    }

    record AdapterModel(String apiKey, String baseUrl, String mode, String model) {
    }

    /** The gate and the reads of the LangChain4j and Semantic Kernel model beans. */
    @Configuration(proxyBeanMethods = false)
    static class AdapterProbe {

        @Bean
        @ConditionalOnExpression(
                "'${llm.api-key:}' != '' or '${llm.mode:}'.trim().equalsIgnoreCase('local') "
                + "or '${LLM_MODE:}'.trim().equalsIgnoreCase('local')")
        AdapterModel adapterModel(
                @Value("${llm.api-key:}") String apiKey,
                @Value("${llm.base-url:}") String baseUrl,
                @Value("${llm.mode:}") String mode,
                @Value("${llm.model:gpt-4o-mini}") String model) {
            return new AdapterModel(apiKey, baseUrl, mode, model);
        }
    }
}
