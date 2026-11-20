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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup line of {@link SpringAiAgentRuntime#configure} describes the
 * {@link ChatClient} the application's requests go through. It used to print
 * {@code model=<LLM settings model>}, so an application that bound its own
 * client on another model (the spring-ai-advisors sample binds a local echo
 * model) was reported as serving the settings default (Correctness Invariant
 * #5: startup logs report runtime truth).
 */
class SpringAiConfigureLogTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(SpringAiAgentRuntime.class);
    private AiEnvironment environment;

    @BeforeEach
    void captureLog() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
        if (environment != null) {
            environment.close();
        }
        SpringAiAgentRuntime.clearChatClientBinding();
        AgentRuntimeResolver.clearExplicitClientBinding();
    }

    @Test
    void anApplicationBoundClientIsNotReportedAsTheSettingsModel() {
        var settingsModel = "settings-default-model-xyz";
        environment = AiEnvironment.create(key -> null);
        environment.configure("remote", settingsModel, null, "https://api.openai.com/v1");
        SpringAiAgentRuntime.setChatClient(environment, ChatClient.builder(new EchoModel()).build());
        var runtime = environment.runtimes().resolveAll().stream()
                .filter(candidate -> candidate instanceof SpringAiAgentRuntime)
                .findFirst()
                .orElseThrow();
        var settings = environment.settings();
        assertEquals(settingsModel, settings.model(),
                "the settings carry a model the log could wrongly report");

        runtime.configure(settings);

        var info = appender.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertTrue(info.stream().anyMatch(line -> line.contains("application's ChatClient")),
                "the startup log must say requests go through the application's ChatClient: " + info);
        assertFalse(info.stream().anyMatch(line -> line.contains("model=" + settings.model())),
                "the startup log must not present the LLM settings model as the served one: " + info);
    }

    /** A Spring AI model that is not the settings' model: it echoes the prompt. */
    private static final class EchoModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(prompt.getContents()))));
        }
    }
}
