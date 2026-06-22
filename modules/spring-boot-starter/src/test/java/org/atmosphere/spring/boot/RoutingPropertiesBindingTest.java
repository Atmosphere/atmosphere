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

import java.util.HashMap;
import java.util.Map;

import org.atmosphere.ai.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins relaxed (kebab → camel) binding of the cost-, latency-, and model-rule
 * families added to {@link AtmosphereProperties.RoutingProperties}, the
 * empty-not-null default contract, and {@link
 * AtmosphereProperties.ModelOptionProperties#toModelOption}'s null-numeric
 * defaults ({@code capability=0}, {@code costPerStreamingText=0.0},
 * {@code averageLatencyMs=0}).
 */
class RoutingPropertiesBindingTest {

    private static AtmosphereProperties bind(Map<String, String> props) {
        var source = new MapConfigurationPropertySource(props);
        var binder = new Binder(source);
        return binder.bind("atmosphere", AtmosphereProperties.class)
                .orElseGet(AtmosphereProperties::new);
    }

    @Test
    void costLatencyAndModelRulesBindViaRelaxedKebabToCamel() {
        var props = new HashMap<String, String>();
        props.put("atmosphere.ai.routing.enabled", "true");
        // model rule — kebab model-pattern → camel modelPattern
        props.put("atmosphere.ai.routing.model-rules[0].model-pattern", "gpt-4o");
        props.put("atmosphere.ai.routing.model-rules[0].base-url", "https://api.openai.com/v1");
        props.put("atmosphere.ai.routing.model-rules[0].api-key", "sk-model");
        // cost rule — kebab max-cost + nested model option kebab fields
        props.put("atmosphere.ai.routing.cost-rules[0].max-cost", "5.0");
        props.put("atmosphere.ai.routing.cost-rules[0].models[0].model", "gpt-4o");
        props.put("atmosphere.ai.routing.cost-rules[0].models[0].cost-per-streaming-text", "0.01");
        props.put("atmosphere.ai.routing.cost-rules[0].models[0].average-latency-ms", "120");
        props.put("atmosphere.ai.routing.cost-rules[0].models[0].capability", "10");
        props.put("atmosphere.ai.routing.cost-rules[0].models[0].base-url", "https://api.openai.com/v1");
        props.put("atmosphere.ai.routing.cost-rules[0].models[0].api-key", "sk-cost");
        // latency rule — kebab max-latency-ms + nested option
        props.put("atmosphere.ai.routing.latency-rules[0].max-latency-ms", "100");
        props.put("atmosphere.ai.routing.latency-rules[0].models[0].model", "gemini-2.5-flash");
        props.put("atmosphere.ai.routing.latency-rules[0].models[0].average-latency-ms", "50");
        props.put("atmosphere.ai.routing.latency-rules[0].models[0].capability", "8");

        var routing = bind(props).getAi().getRouting();

        assertThat(routing.isEnabled()).isTrue();

        assertThat(routing.getModelRules()).hasSize(1);
        var modelRule = routing.getModelRules().get(0);
        assertThat(modelRule.getModelPattern()).isEqualTo("gpt-4o");
        assertThat(modelRule.getBaseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(modelRule.getApiKey()).isEqualTo("sk-model");

        assertThat(routing.getCostRules()).hasSize(1);
        var costRule = routing.getCostRules().get(0);
        assertThat(costRule.getMaxCost()).isEqualTo(5.0);
        assertThat(costRule.getModels()).hasSize(1);
        var costOpt = costRule.getModels().get(0);
        assertThat(costOpt.getModel()).isEqualTo("gpt-4o");
        assertThat(costOpt.getCostPerStreamingText()).isEqualTo(0.01);
        assertThat(costOpt.getAverageLatencyMs()).isEqualTo(120L);
        assertThat(costOpt.getCapability()).isEqualTo(10);
        assertThat(costOpt.getBaseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(costOpt.getApiKey()).isEqualTo("sk-cost");

        assertThat(routing.getLatencyRules()).hasSize(1);
        var latencyRule = routing.getLatencyRules().get(0);
        assertThat(latencyRule.getMaxLatencyMs()).isEqualTo(100L);
        assertThat(latencyRule.getModels()).hasSize(1);
        var latencyOpt = latencyRule.getModels().get(0);
        assertThat(latencyOpt.getModel()).isEqualTo("gemini-2.5-flash");
        assertThat(latencyOpt.getAverageLatencyMs()).isEqualTo(50L);
        assertThat(latencyOpt.getCapability()).isEqualTo(8);
    }

    @Test
    void noRulesBindToEmptyListsNotNull() {
        var props = new HashMap<String, String>();
        props.put("atmosphere.ai.routing.enabled", "true");

        var routing = bind(props).getAi().getRouting();

        assertThat(routing.getContentRules()).isNotNull().isEmpty();
        assertThat(routing.getModelRules()).isNotNull().isEmpty();
        assertThat(routing.getCostRules()).isNotNull().isEmpty();
        assertThat(routing.getLatencyRules()).isNotNull().isEmpty();
    }

    @Test
    void toModelOptionAppliesNullNumericDefaults() {
        // A model option with only a name set — every numeric field null.
        var option = new AtmosphereProperties.ModelOptionProperties();
        option.setModel("free-model");

        LlmClient marker = (request, session) -> { };
        var resolved = option.toModelOption((baseUrl, apiKey) -> marker);

        assertThat(resolved.model()).isEqualTo("free-model");
        assertThat(resolved.capability()).isZero();
        assertThat(resolved.costPerStreamingText()).isZero();
        assertThat(resolved.averageLatencyMs()).isZero();
        // The resolver supplies the client; here the marker proves wiring.
        assertThat(resolved.client()).isSameAs(marker);
    }

    @Test
    void toModelOptionPassesBaseUrlAndApiKeyToResolver() {
        var option = new AtmosphereProperties.ModelOptionProperties();
        option.setModel("dedicated");
        option.setBaseUrl("https://example.test/v1");
        option.setApiKey("sk-dedicated");

        var seenBaseUrl = new String[1];
        var seenApiKey = new String[1];
        LlmClient marker = (request, session) -> { };
        var resolved = option.toModelOption((baseUrl, apiKey) -> {
            seenBaseUrl[0] = baseUrl;
            seenApiKey[0] = apiKey;
            return marker;
        });

        assertThat(seenBaseUrl[0]).isEqualTo("https://example.test/v1");
        assertThat(seenApiKey[0]).isEqualTo("sk-dedicated");
        assertThat(resolved.client()).isSameAs(marker);
    }
}
