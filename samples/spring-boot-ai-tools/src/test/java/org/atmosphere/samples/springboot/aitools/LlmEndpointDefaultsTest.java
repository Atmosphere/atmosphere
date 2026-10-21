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

import org.apache.commons.logging.LogFactory;
import org.atmosphere.ai.AiConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample's sources must not depend on one AI framework ({@code atmosphere
 * new --template ai-tools --runtime <X> --force} strips LangChain4j from the
 * pom), and a blank {@code llm.base-url} must still reach the endpoint
 * {@link AiConfig} resolves rather than the adapters' OpenAI fallback.
 */
class LlmEndpointDefaultsTest {

    private static StandardEnvironment environment(Map<String, Object> yml) {
        var env = new StandardEnvironment();
        env.getPropertySources().addLast(new MapPropertySource("application.yml", new HashMap<>(yml)));
        new LlmEndpointDefaults().postProcessEnvironment(env, null);
        return env;
    }

    @Test
    void aBlankBaseUrlWithTheDefaultGeminiModelResolvesToGemini() {
        var env = environment(Map.of("llm.mode", "remote", "llm.model", "gemini-2.5-flash", "llm.base-url", ""));
        assertEquals(AiConfig.GEMINI_ENDPOINT, env.getProperty("llm.base-url"));
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
        assertNull(env.getPropertySources().get(LlmEndpointDefaults.SOURCE_NAME),
                "nothing is added when the user set the endpoint");
    }

    @Test
    void theDefaultsAreRegisteredWithSpringBoot() throws IOException {
        // Each jar ships its own spring.factories; read them one by one, since a
        // merged Properties view keeps only the last file's value for the key.
        var registered = new ArrayList<String>();
        var files = getClass().getClassLoader().getResources("META-INF/spring.factories");
        while (files.hasMoreElements()) {
            var factories = PropertiesLoaderUtils.loadProperties(new UrlResource(files.nextElement()));
            // The key Spring Boot 3 and 4 both read (the type moved packages in Boot 4).
            registered.add(factories.getProperty("org.springframework.boot.env.EnvironmentPostProcessor", ""));
        }
        assertTrue(registered.stream().anyMatch(v -> v.contains(LlmEndpointDefaults.class.getName())),
                "LlmEndpointDefaults is not registered in META-INF/spring.factories: " + registered);
    }

    @Test
    void atmosphereAiSettingsReachTheEndpointBeforeItIsDerived() throws Exception {
        // LLM_MODE= and LLM_MODEL= exported blank, atmosphere.ai.mode=local: the
        // starter's AtmosphereAiLlmPropertyBridge maps atmosphere.ai.* onto the
        // blank llm.*, and the endpoint must be derived from that, not from the
        // blank llm.* (which resolves to Gemini: a local run sent to Google).
        // The post-processors are loaded and ordered the way Spring Boot does,
        // under the key both Boot 3 and Boot 4 read; the type is looked up by
        // name, as it is deprecated in Boot 4.
        var type = Class.forName("org.springframework.boot.env.EnvironmentPostProcessor");
        var wanted = Set.of("org.atmosphere.spring.boot.AtmosphereAiLlmPropertyBridge",
                LlmEndpointDefaults.class.getName());
        var processors = new ArrayList<Object>(SpringFactoriesLoader
                .forDefaultResourceLocation(getClass().getClassLoader())
                .load(type, null, SpringFactoriesLoader.FailureHandler.logging(
                        LogFactory.getLog(getClass())))
                .stream()
                .filter(p -> wanted.contains(p.getClass().getName()))
                .toList());
        assertEquals(wanted.size(), processors.size(), "registered: " + processors);
        AnnotationAwareOrderComparator.sort(processors);

        var env = new StandardEnvironment();
        env.getPropertySources().addLast(new MapPropertySource("application.yml", new HashMap<>(Map.of(
                "llm.mode", "", "llm.model", "", "llm.base-url", "",
                "atmosphere.ai.mode", "local", "atmosphere.ai.model", "qwen2.5:7b"))));
        var postProcess = type.getMethod("postProcessEnvironment",
                ConfigurableEnvironment.class, SpringApplication.class);
        for (var processor : processors) {
            postProcess.invoke(processor, env, null);
        }

        var baseUrl = env.getProperty("llm.base-url");
        assertEquals(AiConfig.resolveBaseUrl("local", null, "qwen2.5:7b"), baseUrl);
        assertTrue(baseUrl.contains("11434"), baseUrl);
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
