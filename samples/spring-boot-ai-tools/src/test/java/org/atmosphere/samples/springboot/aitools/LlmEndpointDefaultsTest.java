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
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The sample's sources must not depend on one AI framework ({@code atmosphere
 * new --template ai-tools --runtime <X> --force} strips LangChain4j from the
 * pom), and a blank {@code llm.base-url} must still reach the endpoint
 * {@link AiConfig} resolves rather than the adapters' OpenAI fallback.
 */
class LlmEndpointDefaultsTest {

    private static StandardEnvironment environment(Map<String, Object> yml) {
        var env = new StandardEnvironment();
        // An LLM_* variable exported by the shell running the build must not decide the outcome.
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addLast(new MapPropertySource("application.yml", new HashMap<>(yml)));
        LlmEndpointDefaultsSupport.apply(env);
        return env;
    }

    @Test
    void aBlankBaseUrlWithTheDefaultGeminiModelResolvesToGemini() {
        var env = environment(Map.of("llm.mode", "remote", "llm.model", "gemini-2.5-flash", "llm.base-url", ""));
        assertEquals(AiConfig.GEMINI_ENDPOINT, env.getProperty("llm.base-url"));
    }

    @Test
    void aBlankModeAndModelFallBackToTheFrameworkDefaults() {
        // application.yml leaves LLM_MODE/LLM_MODEL without a default so
        // atmosphere.ai.* can apply; the adapters read ${llm.model:...}, whose
        // fallback ignores an empty string, so nothing set must not stay blank.
        var env = environment(Map.of("llm.mode", "", "llm.model", "", "llm.base-url", ""));
        assertEquals(AiConfig.DEFAULT_MODE, env.getProperty("llm.mode"));
        assertEquals(AiConfig.DEFAULT_MODEL, env.getProperty("llm.model"));
        assertEquals(AiConfig.resolveBaseUrl(AiConfig.DEFAULT_MODE, null, AiConfig.DEFAULT_MODEL),
                env.getProperty("llm.base-url"));
    }

    @Test
    void aSetModelIsKeptWhenOnlyTheModeIsBlank() {
        var env = environment(Map.of("llm.mode", " ", "llm.model", "gpt-4o-mini", "llm.base-url", ""));
        assertEquals(AiConfig.DEFAULT_MODE, env.getProperty("llm.mode"));
        assertEquals("gpt-4o-mini", env.getProperty("llm.model"));
        assertEquals(AiConfig.resolveBaseUrl("remote", null, "gpt-4o-mini"), env.getProperty("llm.base-url"));
    }

    @Test
    void aBlankBaseUrlInLocalModeResolvesToOllama() {
        var env = environment(Map.of("llm.mode", "local", "llm.model", "qwen2.5:7b", "llm.base-url", ""));
        assertEquals(AiConfig.resolveBaseUrl("local", null, "qwen2.5:7b"), env.getProperty("llm.base-url"));
        assertTrue(env.getProperty("llm.base-url").contains("11434"), env.getProperty("llm.base-url"));
    }

    @Test
    void anOpenAiModelResolvesToOpenAi() {
        var env = environment(Map.of("llm.mode", "remote", "llm.model", "gpt-4o-mini", "llm.base-url", ""));
        assertEquals(AiConfig.resolveBaseUrl("remote", null, "gpt-4o-mini"), env.getProperty("llm.base-url"));
    }

    @Test
    void anExplicitBaseUrlIsNeverOverridden() {
        var env = environment(Map.of("llm.mode", "remote", "llm.model", "gemini-2.5-flash",
                "llm.base-url", "https://llm.example.test/v1"));
        assertEquals("https://llm.example.test/v1", env.getProperty("llm.base-url"));
        assertNull(env.getPropertySources().get(LlmEndpointDefaultsSupport.SOURCE_NAME),
                "nothing is added when the user set the endpoint");
    }

    @Test
    void theDefaultsAreRegisteredForEverySpringBootLine() throws IOException {
        // Each jar ships its own spring.factories; read them one by one, since a
        // merged Properties view keeps only the last file's value for a key.
        // Spring Boot 4 reads the first key, Spring Boot 3 only the second.
        // Names, not class literals: LlmEndpointDefaults is not compiled on Spring Boot 3.
        var postProcessors = new ArrayList<String>();
        var listeners = new ArrayList<String>();
        var files = getClass().getClassLoader().getResources("META-INF/spring.factories");
        while (files.hasMoreElements()) {
            var factories = PropertiesLoaderUtils.loadProperties(new UrlResource(files.nextElement()));
            postProcessors.add(factories.getProperty("org.springframework.boot.EnvironmentPostProcessor", ""));
            listeners.add(factories.getProperty("org.springframework.context.ApplicationListener", ""));
        }
        assertTrue(postProcessors.stream().anyMatch(
                        v -> v.contains("org.atmosphere.samples.springboot.aitools.LlmEndpointDefaults")),
                "LlmEndpointDefaults is not registered for Spring Boot 4: " + postProcessors);
        assertTrue(listeners.stream().anyMatch(v -> v.contains(LlmEndpointDefaultsBoot3Listener.class.getName())),
                "LlmEndpointDefaultsBoot3Listener is not registered for Spring Boot 3: " + listeners);
    }

    @Test
    void theListenerAppliesTheDefaultsOnlyWithoutSpringBoot4sPostProcessorType() {
        // The event is mocked: its bootstrap context type moved packages between
        // Spring Boot 3 and 4, and this test compiles on both.
        var withPostProcessorType = environment();
        new LlmEndpointDefaultsBoot3Listener(true).onApplicationEvent(prepared(withPostProcessorType));
        assertNull(withPostProcessorType.getPropertySources().get(LlmEndpointDefaultsSupport.SOURCE_NAME),
                "with Spring Boot 4's post-processor type present the listener must do nothing");

        var withoutPostProcessorType = environment();
        new LlmEndpointDefaultsBoot3Listener(false).onApplicationEvent(prepared(withoutPostProcessorType));
        assertEquals(AiConfig.DEFAULT_MODEL, withoutPostProcessorType.getProperty("llm.model"));
    }

    @Test
    void aSecondRunLeavesTheFirstRunsPropertySourceAlone() {
        // The post-processor and the listener could both run on one environment.
        // The second run finds the first one's source by name and changes nothing,
        // although llm.model and llm.base-url are still blank here.
        var env = environment();
        var first = new MapPropertySource(LlmEndpointDefaultsSupport.SOURCE_NAME,
                new HashMap<>(Map.of("llm.mode", "local")));
        env.getPropertySources().addFirst(first);
        LlmEndpointDefaultsSupport.apply(env);
        assertSame(first, env.getPropertySources().get(LlmEndpointDefaultsSupport.SOURCE_NAME));
        assertEquals("local", env.getProperty("llm.mode"));
        assertEquals("", env.getProperty("llm.base-url"), "nothing is added by the second run");
    }

    private static StandardEnvironment environment() {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addLast(new MapPropertySource("application.yml", new HashMap<>(Map.of(
                "llm.mode", "", "llm.model", "", "llm.base-url", ""))));
        return env;
    }

    private static ApplicationEnvironmentPreparedEvent prepared(StandardEnvironment env) {
        var event = mock(ApplicationEnvironmentPreparedEvent.class);
        when(event.getEnvironment()).thenReturn(env);
        return event;
    }

    @Test
    void mainSourcesImportNoAiFrameworkPackage() throws Exception {
        // `--runtime <X> --force` removes atmosphere-langchain4j and
        // langchain4j-open-ai from the pom; a main source importing them made
        // the scaffolded project fail to compile.
        var framework = Pattern.compile("^import (static )?(dev\\.langchain4j|org\\.springframework\\.ai"
                + "|com\\.google\\.adk|ai\\.koog|io\\.agentscope|com\\.embabel|com\\.microsoft\\.semantickernel"
                + "|org\\.atmosphere\\.ai\\.(langchain4j|spring|adk|koog|agentscope|embabel|sk))\\.",
                Pattern.MULTILINE);
        try (var sources = Files.walk(Path.of("src/main/java"))) {
            var offenders = sources.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return framework.matcher(Files.readString(p)).find();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toList();
            assertTrue(offenders.isEmpty(), "main sources import an AI framework: " + offenders);
        }
    }
}
