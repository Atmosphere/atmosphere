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
package org.atmosphere.samples.springboot.reattach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;

import org.atmosphere.spring.boot.AtmosphereProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/**
 * The harness serves two {@code @AiEndpoint}s, and the Console must talk to
 * {@link SlowEmitterChat} on every starter the sample builds against (the
 * default Spring Boot 4 one and the {@code spring-boot3} profile's). Neither
 * starter orders the handler map, so the pin has to be an {@code atmosphere.*}
 * key the starter in use actually binds: a key it does not know is dropped
 * without a word, and the Console then lands on whichever endpoint the map
 * happens to iterate first.
 */
class HarnessConsoleEndpointConfigTest {

    /**
     * Sample-level switch, not a starter property: neither starter binds it,
     * and the harness defines no {@code TokenValidator}, so the starters'
     * auth auto-configuration stays off whatever it says.
     */
    private static final ConfigurationPropertyName SAMPLE_OWNED =
            ConfigurationPropertyName.of("atmosphere.auth");

    @Test
    void everyStarterKeyBindsAndPinsTheConsoleToTheEmitterEndpoint() throws Exception {
        // The sample's own file only: no system property or environment
        // variable of the host can add or hide a key.
        var sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        var starterKeys = new ArrayList<ConfigurationPropertySource>();
        for (var source : ConfigurationPropertySources.from(sources)) {
            if (source instanceof IterableConfigurationPropertySource iterable) {
                starterKeys.add(iterable.filter(name -> !SAMPLE_OWNED.isAncestorOf(name)));
            }
        }
        assertFalse(starterKeys.isEmpty(), "application.yml must load as an enumerable source");
        var binder = new Binder(starterKeys);

        // Fails, naming the key, when application.yml carries an atmosphere.*
        // property this build's starter has no field for.
        var properties = binder.bind("atmosphere", Bindable.of(AtmosphereProperties.class),
                new NoUnboundElementsBindHandler(BindHandler.DEFAULT)).get();

        assertEquals("/atmosphere/agent/harness", properties.getAi().getPath(),
                "the Console endpoint must be pinned through atmosphere.ai.path, "
                        + "which both starters' Console detection honours");
    }
}
