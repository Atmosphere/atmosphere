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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The startup log names the model and endpoint the Spring AI Alibaba
 * {@link ChatModel} calls and whether {@code llm.mode=local} is set (Runtime
 * Truth). Spring AI's OpenAI auto-configuration builds the {@code ChatModel},
 * not Atmosphere, so before this line nothing said where the prompts went.
 */
class AtmosphereSpringAiAlibabaChatModelLogTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AtmosphereSpringAiAlibabaAutoConfiguration.class);
    private final Logger aiConfigLogger = (Logger) LoggerFactory.getLogger(AiConfig.class);

    @BeforeEach
    void captureLog() {
        appender.start();
        logger.addAppender(appender);
        aiConfigLogger.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(appender);
        aiConfigLogger.detachAppender(appender);
    }

    @Test
    void localModeNamesTheLocalEndpointAndTheModel() {
        var env = environment(Map.of("LLM_MODE", "local", "spring.ai.openai.base-url", "http://localhost:11434"));

        AtmosphereSpringAiAlibabaAutoConfiguration.logChatModelInUse(
                new UsageCapturingChatModel(openAiChatModel("qwen2.5:7b")), env);

        assertEquals(List.of("Spring AI Alibaba ChatModel in use: model=qwen2.5:7b, "
                + "endpoint=http://localhost:11434, local=true"), messages());
    }

    /** The runtime bean, which every Spring AI Alibaba application builds, writes the line. */
    @Test
    void theRuntimeBeanLogsTheChatModelInUse() {
        var environment = AiEnvironment.create(key -> null);
        try {
            var beanFactory = new DefaultListableBeanFactory();
            beanFactory.registerSingleton("aiEnvironment", environment);
            beanFactory.registerSingleton("chatModel", openAiChatModel("qwen2.5:7b"));
            new AtmosphereSpringAiAlibabaAutoConfiguration(beanFactory.getBeanProvider(AiEnvironment.class))
                    .springAiAlibabaAgentRuntime(mock(ReactAgent.class), beanFactory.getBeanProvider(ChatModel.class),
                            environment(Map.of("LLM_MODE", "local", "spring.ai.openai.base-url", "http://localhost:11434")));

            assertTrue(messages().contains("Spring AI Alibaba ChatModel in use: model=qwen2.5:7b, "
                    + "endpoint=http://localhost:11434, local=true"), messages().toString());
        } finally {
            environment.close();
        }
    }

    @Test
    void withoutABaseUrlTheEndpointIsSpringAiOnesDefault() {
        AtmosphereSpringAiAlibabaAutoConfiguration.logChatModelInUse(
                openAiChatModel("gpt-4o-mini"), environment(Map.of()));

        assertEquals(List.of("Spring AI Alibaba ChatModel in use: model=gpt-4o-mini, "
                + "endpoint=https://api.openai.com, local=false"), messages());
    }

    @Test
    void theChatBaseUrlOverridesTheSharedOne() {
        var env = environment(Map.of("spring.ai.openai.base-url", "https://shared.example",
                "spring.ai.openai.chat.base-url", "https://chat.example"));

        AtmosphereSpringAiAlibabaAutoConfiguration.logChatModelInUse(openAiChatModel("m"), env);

        assertTrue(messages().getFirst().contains("endpoint=https://chat.example,"), messages().toString());
    }

    /** Local mode overridden by a remote base URL is a WARN, as for the other runtimes. */
    @Test
    void localModeWithARemoteEndpointWarns() {
        var env = environment(Map.of("llm.mode", "local", "spring.ai.openai.base-url", "https://api.openai.com"));

        AtmosphereSpringAiAlibabaAutoConfiguration.logChatModelInUse(openAiChatModel("m"), env);

        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("https://api.openai.com")),
                "local mode must not silently call a remote endpoint: " + messages());
    }

    /** A ChatModel the application built is not described by spring.ai.openai.*: the line must not say it is. */
    @Test
    void anApplicationChatModelIsNotGivenTheSpringAiEndpoint() {
        var env = environment(Map.of("spring.ai.openai.base-url", "http://localhost:11434"));

        AtmosphereSpringAiAlibabaAutoConfiguration.logChatModelInUse(mock(ChatModel.class), env);

        var line = messages().getFirst();
        assertTrue(line.contains("supplied by the application"), line);
        assertTrue(line.contains("endpoint=(its own, not spring.ai.openai.*)"), line);
    }

    /**
     * Spring AI 1.x's real {@code OpenAiChatModel}. Its {@code OpenAiApi} is a
     * mock: the API's constructor calls a Spring Framework 6 method this
     * module's Spring 7 test classpath lacks (an application runs the 1.x
     * stack on Spring Boot 3.5), and only the model's class and options are read.
     */
    private static OpenAiChatModel openAiChatModel(String model) {
        return OpenAiChatModel.builder()
                .openAiApi(mock(OpenAiApi.class))
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .build();
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static StandardEnvironment environment(Map<String, Object> properties) {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return env;
    }
}
