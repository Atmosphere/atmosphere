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
package org.atmosphere.ai.agentscope;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.agentscope.core.model.Model;
import org.atmosphere.ai.AiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code LLM_MODE=local}, the documented keyless mode, reaches the local
 * Ollama endpoint through the AgentScope adapter as it does through the
 * built-in runtime. The auto-configured {@link Model} read only
 * {@code llm.base-url}, so with the mode alone it sent the user's prompt, and
 * the placeholder key, to api.openai.com. An explicit {@code llm.base-url}
 * still wins.
 *
 * <p>The process environment is removed from every Spring environment built
 * here, so an {@code LLM_*} variable exported by the shell running the build
 * cannot change the outcome.</p>
 */
class AtmosphereAgentScopeAutoConfigurationLocalModeTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AtmosphereAgentScopeAutoConfiguration.class);

    @BeforeEach
    void captureLog() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(appender);
    }

    @Test
    void localModeAloneBuildsTheModelForTheLocalEndpoint() {
        try (var context = context(Map.of("LLM_MODE", "local", "llm.model", "qwen2.5:7b"))) {
            assertNotNull(context.getBeanProvider(Model.class).getIfAvailable(),
                    "local mode needs no API key: the model must be built");
            assertEquals("http://localhost:11434/v1", builtEndpoint(),
                    "LLM_MODE=local must send the prompt to the local Ollama endpoint, not to api.openai.com");
        }
    }

    /** The sweep's setup: local mode with the placeholder key the docs ask for. */
    @Test
    void localModeWithAPlaceholderKeyStaysLocal() {
        try (var context = context(Map.of("LLM_MODE", "local", "llm.api-key", "ollama"))) {
            context.getBean(Model.class);

            assertEquals("http://localhost:11434/v1", builtEndpoint());
        }
    }

    @Test
    void anExplicitBaseUrlStillWinsOverLocalMode() {
        try (var context = context(Map.of("llm.mode", "local", "llm.base-url", "http://127.0.0.1:8000/v1"))) {
            context.getBean(Model.class);

            assertEquals("http://127.0.0.1:8000/v1", builtEndpoint());
        }
    }

    @Test
    void remoteModeKeepsOpenAiAndStillNeedsAKey() {
        try (var context = context(Map.of())) {
            assertNull(context.getBeanProvider(Model.class).getIfAvailable(),
                    "outside local mode a missing key still means no model");
        }
        try (var context = context(Map.of("llm.api-key", "sk-test"))) {
            context.getBean(Model.class);

            assertEquals("https://api.openai.com/v1", builtEndpoint());
        }
    }

    /**
     * A blank base URL follows the model, as for the built-in runtime: it used
     * to mean OpenAI whatever the model, so a Gemini model and key went to
     * api.openai.com.
     */
    @Test
    void aGeminiModelWithoutABaseUrlCallsGemini() {
        try (var context = context(Map.of("llm.api-key", "AIza-test", "llm.model", "gemini-2.5-flash"))) {
            context.getBean(Model.class);

            assertEquals(AiConfig.GEMINI_ENDPOINT, builtEndpoint());
        }
    }

    @Test
    void anExplicitBaseUrlWinsOverAGeminiModel() {
        try (var context = context(Map.of("llm.api-key", "k", "llm.model", "gemini-2.5-flash",
                "llm.base-url", "https://proxy.example/v1"))) {
            context.getBean(Model.class);

            assertEquals("https://proxy.example/v1", builtEndpoint());
        }
    }

    /**
     * The gate reads the mode as the bean body and {@code AiConfig} do: trimmed
     * and case-insensitive. A case-sensitive {@code == 'local'} built no model
     * for {@code LLM_MODE=Local}, though the body would have called the local
     * endpoint.
     */
    @Test
    void theGateReadsTheModeTrimmedAndCaseInsensitive() {
        try (var context = context(Map.of("LLM_MODE", " Local "))) {
            assertNotNull(context.getBeanProvider(Model.class).getIfAvailable(), "LLM_MODE=' Local ' is local mode");
            assertEquals("http://localhost:11434/v1", builtEndpoint());
        }
        try (var context = context(Map.of("llm.mode", "LOCAL"))) {
            assertNotNull(context.getBeanProvider(Model.class).getIfAvailable(), "llm.mode=LOCAL is local mode");
        }
    }

    private String builtEndpoint() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("Auto-building AgentScope OpenAIChatModel"))
                .map(message -> message.replaceAll(".*endpoint=(\\S+) .*", "$1"))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        var env = context.getEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(AtmosphereAgentScopeAutoConfiguration.class);
        context.refresh();
        return context;
    }
}
