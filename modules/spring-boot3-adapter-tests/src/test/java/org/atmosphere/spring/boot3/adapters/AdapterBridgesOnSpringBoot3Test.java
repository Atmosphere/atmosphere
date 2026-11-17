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
package org.atmosphere.spring.boot3.adapters;

import org.atmosphere.ai.embabel.AtmosphereEmbabelBoot3EnvironmentListener;
import org.atmosphere.ai.spring.AtmosphereSpringAiBoot3EnvironmentListener;
import org.atmosphere.ai.spring.alibaba.AtmosphereSpringAiAlibabaBoot3EnvironmentListener;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Spring AI, Spring AI Alibaba and Embabel adapters are built against
 * Spring Boot 4, where their {@code llm.*} bridges are
 * {@code org.springframework.boot.EnvironmentPostProcessor}s. Spring Boot 3 has
 * neither that type nor its {@code spring.factories} key; there each adapter
 * runs the same bridge from an application listener. This module puts the
 * adapter jars on a Spring Boot 3.5 classpath and checks the bridges run.
 */
class AdapterBridgesOnSpringBoot3Test {

    @Configuration(proxyBeanMethods = false)
    static class NoBeans {
    }

    @Test
    void thisClasspathIsSpringBoot3WithoutSpringBoot4sPostProcessorType() {
        assertThat(SpringBootVersion.getVersion()).startsWith("3.");
        assertThat(ClassUtils.isPresent("org.springframework.boot.EnvironmentPostProcessor",
                getClass().getClassLoader())).isFalse();
    }

    @Test
    void springBoot3RunsTheBridgesAfterTheStarterMapsAtmosphereAiSettings() {
        // atmosphere.ai.model reaches llm.model through the Spring Boot 3 starter's
        // post-processor, then each adapter's listener maps llm.* onto its own
        // properties: the listeners run after Spring Boot's post-processors.
        var app = new SpringApplication(NoBeans.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setEnvironment(withoutProcessEnvironment());
        try (var context = app.run("--atmosphere.ai.model=boot3-model", "--llm.api-key=sk-boot3-test")) {
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("llm.model")).isEqualTo("boot3-model");
            // Spring AI and Spring AI Alibaba map the same properties; whichever runs
            // first adds its source and the other finds the properties set.
            assertThat(environment.getProperty("spring.ai.openai.chat.options.model")).isEqualTo("boot3-model");
            assertThat(environment.getProperty("spring.ai.openai.api-key")).isEqualTo("sk-boot3-test");
            assertThat(environment.getPropertySources().contains("atmosphere-spring-ai-llm-bridge")
                    || environment.getPropertySources().contains("atmosphere-spring-ai-alibaba-llm-bridge"))
                    .as("a Spring AI bridge property source").isTrue();
            assertThat(environment.getPropertySources().contains("atmosphere-embabel-llm-bridge")).isTrue();
            assertThat(environment.getProperty("embabel.models.default-llm")).isEqualTo("boot3-model");
            assertThat(environment.getProperty("embabel.agent.platform.models.openai.api-key"))
                    .isEqualTo("sk-boot3-test");
        }
    }

    @Test
    void springBoot3LoadsEachAdaptersListenerAndNoAtmospherePostProcessor() throws IOException {
        // Each adapter must reach Spring Boot 3 on its own: an application with one
        // adapter has no other to fill the same properties.
        var loader = SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader());
        var listeners = loader.load(ApplicationListener.class).stream()
                .map(listener -> listener.getClass().getName())
                .toList();
        assertThat(listeners).contains(AtmosphereSpringAiBoot3EnvironmentListener.class.getName(),
                AtmosphereSpringAiAlibabaBoot3EnvironmentListener.class.getName(),
                AtmosphereEmbabelBoot3EnvironmentListener.class.getName());
        // Spring Boot 3 reads this key and would fail to start on an Atmosphere adapter
        // entry, whose class no longer implements the type the key names. Read by name:
        // Spring Boot's own post-processors take constructor arguments.
        var postProcessors = new ArrayList<String>();
        var files = getClass().getClassLoader().getResources("META-INF/spring.factories");
        while (files.hasMoreElements()) {
            var factories = PropertiesLoaderUtils.loadProperties(new UrlResource(files.nextElement()));
            postProcessors.add(factories.getProperty("org.springframework.boot.env.EnvironmentPostProcessor", ""));
        }
        assertThat(postProcessors).noneMatch(entries -> entries.contains("org.atmosphere.ai."));
    }

    @Test
    void eachAdaptersListenerMapsOnSpringBoot3() {
        assertMaps(new AtmosphereSpringAiBoot3EnvironmentListener(), "atmosphere-spring-ai-llm-bridge",
                "spring.ai.openai.api-key");
        assertMaps(new AtmosphereSpringAiAlibabaBoot3EnvironmentListener(),
                "atmosphere-spring-ai-alibaba-llm-bridge", "spring.ai.openai.api-key");
        assertMaps(new AtmosphereEmbabelBoot3EnvironmentListener(), "atmosphere-embabel-llm-bridge",
                "embabel.agent.platform.models.openai.api-key");
    }

    private static void assertMaps(ApplicationListener<ApplicationEnvironmentPreparedEvent> listener,
                                   String sourceName, String mappedProperty) {
        var environment = withoutProcessEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("test",
                new HashMap<>(Map.of("llm.api-key", "sk-boot3-test"))));
        listener.onApplicationEvent(new ApplicationEnvironmentPreparedEvent(new DefaultBootstrapContext(),
                new SpringApplication(NoBeans.class), new String[0], environment));
        assertThat(environment.getPropertySources().contains(sourceName)).as(sourceName).isTrue();
        assertThat(environment.getProperty(mappedProperty)).as(mappedProperty).isEqualTo("sk-boot3-test");
    }

    /** The shell running the build may export {@code LLM_*}; it must not decide the outcome. */
    private static StandardEnvironment withoutProcessEnvironment() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return environment;
    }
}
