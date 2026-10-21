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
package org.atmosphere.ai.spring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.atmosphere.ai.AiEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LLM_MODE=local}, the documented keyless mode, reaches the local
 * Ollama endpoint through the Spring AI adapter as it does through the
 * built-in runtime. The auto-configured {@link ChatClient} read only the
 * base-URL properties, so with the mode alone it sent the user's prompt, and
 * the placeholder key, to api.openai.com. An explicit
 * {@code spring.ai.openai.base-url} still wins.
 *
 * <p>The process environment is removed from every Spring environment built
 * here, so an {@code LLM_*} variable exported by the shell running the build
 * cannot change the outcome.</p>
 */
class AtmosphereSpringAiLocalModeTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AtmosphereSpringAiAutoConfiguration.class);

    @BeforeEach
    void captureLog() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(appender);
        SpringAiAgentRuntime.clearChatClientBinding();
    }

    @Test
    void localModeAloneBuildsTheClientForTheLocalEndpoint() {
        try (var context = context(Map.of("LLM_MODE", "local", "LLM_MODEL", "qwen2.5:7b"))) {
            var client = context.getBeanProvider(ChatClient.class).getIfAvailable();

            assertTrue(client != null, "local mode needs no API key: the ChatClient must be built");
            assertEquals("http://localhost:11434/v1", builtEndpoint(),
                    "LLM_MODE=local must send the prompt to the local Ollama endpoint, not to api.openai.com");
        }
    }

    /** The sweep's setup: local mode with the placeholder key the docs ask for. */
    @Test
    void localModeWithAPlaceholderKeyStaysLocal() {
        try (var context = context(Map.of("LLM_MODE", "local", "LLM_API_KEY", "ollama"))) {
            context.getBean(ChatClient.class);

            assertEquals("http://localhost:11434/v1", builtEndpoint(),
                    "a placeholder key must not send a local-mode prompt to api.openai.com");
        }
    }

    @Test
    void anExplicitBaseUrlStillWinsOverLocalMode() throws IOException {
        var paths = new CopyOnWriteArrayList<String>();
        var server = completionServer(paths);
        try {
            var baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            try (var context = context(Map.of("LLM_MODE", "local",
                    "spring.ai.openai.base-url", baseUrl, "spring.ai.openai.api-key", "sk-test"))) {
                var client = context.getBean(ChatClient.class);

                assertEquals(baseUrl, builtEndpoint());
                assertEquals("pong", client.prompt("ping").call().content());
                assertEquals(List.of("/v1/chat/completions"), paths,
                        "the prompt must reach the explicitly configured endpoint");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remoteModeWithoutAKeyStillBuildsNoClient() {
        try (var context = context(Map.of())) {
            assertNull(context.getBeanProvider(ChatClient.class).getIfAvailable(),
                    "outside local mode a missing key still means no ChatClient (demo mode)");
        }
    }

    @Test
    void remoteModeWithoutABaseUrlUsesOpenAisV1Root() {
        assertEquals("https://api.openai.com/v1",
                AtmosphereSpringAiAutoConfiguration.resolveBaseUrl("", "remote"),
                "the OpenAI SDK takes the base URL verbatim: without /v1 every call is a 404");
        assertEquals("http://localhost:11434/v1",
                AtmosphereSpringAiAutoConfiguration.resolveBaseUrl(null, "LOCAL"));
    }

    @Test
    void theBridgeMapsLocalModeToTheLocalEndpointAndAPlaceholderKey() {
        var env = environment(Map.of("LLM_MODE", "local"));

        new AtmosphereSpringAiEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertEquals("http://localhost:11434/v1", env.getProperty("spring.ai.openai.base-url"),
                "Spring AI's own OpenAI beans (the EmbeddingModel Atmosphere binds) must follow local mode too");
        assertEquals(AtmosphereSpringAiAutoConfiguration.LOCAL_PLACEHOLDER_API_KEY,
                env.getProperty("spring.ai.openai.api-key"));
    }

    @Test
    void theBridgeLeavesAnExplicitSpringAiBaseUrlAlone() {
        var env = environment(Map.of("LLM_MODE", "local",
                "spring.ai.openai.base-url", "https://gateway.example/v1", "LLM_API_KEY", "sk-real"));

        new AtmosphereSpringAiEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertEquals("https://gateway.example/v1", env.getProperty("spring.ai.openai.base-url"));
        assertEquals("sk-real", env.getProperty("spring.ai.openai.api-key"));
    }

    @Test
    void theBridgeKeepsLlmBaseUrlOverLocalMode() {
        var env = environment(Map.of("LLM_MODE", "local", "LLM_BASE_URL", "http://127.0.0.1:8000/v1"));

        new AtmosphereSpringAiEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertEquals("http://127.0.0.1:8000/v1", env.getProperty("spring.ai.openai.base-url"));
    }

    @Test
    void theBridgeAddsNothingInRemoteModeWithoutSettings() {
        var env = environment(Map.of());

        new AtmosphereSpringAiEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertFalse(env.getPropertySources().contains(AtmosphereSpringAiEnvironmentPostProcessor.SOURCE_NAME),
                "remote mode with nothing configured must not invent an endpoint or a key");
    }

    /** The startup log does not present a ChatClient the application replaced as the one in use. */
    @Test
    void anApplicationBoundClientIsReportedAsTheOneInUse() {
        var environment = AiEnvironment.create(key -> null);
        try {
            SpringAiAgentRuntime.setChatClient(environment, Mockito.mock(ChatClient.class));
            var beanFactory = new DefaultListableBeanFactory();
            beanFactory.registerSingleton("aiEnvironment", environment);
            beanFactory.registerSingleton("chatClient", Mockito.mock(ChatClient.class));

            new AtmosphereSpringAiAutoConfiguration(beanFactory.getBeanProvider(AiEnvironment.class))
                    .springAiAgentRuntime(beanFactory.getBeanProvider(ChatClient.class));

            assertTrue(messages().contains("The application bound its own Spring AI ChatClient; "
                            + "the auto-configured ChatClient is not used"),
                    "the log must say the auto-configured client is not the one in use: " + messages());
        } finally {
            environment.close();
        }
    }

    @Test
    void anOfferedClientIsNotReportedAsReplaced() {
        var environment = AiEnvironment.create(key -> null);
        try {
            var beanFactory = new DefaultListableBeanFactory();
            beanFactory.registerSingleton("aiEnvironment", environment);
            beanFactory.registerSingleton("chatClient", Mockito.mock(ChatClient.class));

            new AtmosphereSpringAiAutoConfiguration(beanFactory.getBeanProvider(AiEnvironment.class))
                    .springAiAgentRuntime(beanFactory.getBeanProvider(ChatClient.class));

            assertFalse(messages().stream().anyMatch(message -> message.contains("is not used")), messages()::toString);
        } finally {
            environment.close();
        }
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private String builtEndpoint() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("Spring AI ChatClient built"))
                .map(message -> message.replaceAll(".*endpoint=([^,]+),.*", "$1"))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        isolate(context.getEnvironment(), properties);
        context.register(AtmosphereSpringAiAutoConfiguration.class);
        context.refresh();
        return context;
    }

    private static ConfigurableEnvironment environment(Map<String, Object> properties) {
        var env = new StandardEnvironment();
        isolate(env, properties);
        return env;
    }

    private static void isolate(ConfigurableEnvironment env, Map<String, Object> properties) {
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("test", properties));
    }

    /** An OpenAI-compatible endpoint answering every chat completion with "pong". */
    private static HttpServer completionServer(List<String> paths) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            var body = """
                    {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-4o-mini",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }
}
