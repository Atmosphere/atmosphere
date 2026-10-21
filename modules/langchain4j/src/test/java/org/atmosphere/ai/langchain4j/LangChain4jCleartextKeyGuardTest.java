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
package org.atmosphere.ai.langchain4j;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.atmosphere.ai.llm.CleartextApiKeyPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LangChain4j model Atmosphere builds sends its API key over plain
 * {@code http} only to a loopback host. Before, {@code llm.base-url} pointing
 * at any {@code http} host got the key in cleartext; now the application fails
 * to start, logged at {@code ERROR}, unless it opts in.
 */
class LangChain4jCleartextKeyGuardTest {

    private static final String REMOTE_HTTP = "http://llm.invalid:8000/v1";
    private static final String KEY = "sk-test-not-a-real-key";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtmosphereLangChain4jAutoConfiguration.class))
            .withPropertyValues("llm.api-key=", "llm.mode=", "LLM_MODE=", "LLM_API_KEY=", "llm.base-url=",
                    CleartextApiKeyPolicy.ALLOW_PROPERTY + "=");

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AtmosphereLangChain4jAutoConfiguration.class);

    @BeforeEach
    void capture() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private long logged(Level level) {
        return appender.list.stream().filter(event -> event.getLevel() == level).count();
    }

    @Test
    void aKeyOverPlainHttpToANonLoopbackHostFailsTheStartWithAnError() {
        runner.withPropertyValues("llm.api-key=" + KEY, "llm.base-url=" + REMOTE_HTTP)
                .run(context -> {
                    var failure = context.getStartupFailure();
                    assertNotNull(failure, "the context must not start");
                    var root = failure;
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    assertTrue(root instanceof IllegalArgumentException, String.valueOf(root));
                    assertTrue(root.getMessage().contains(REMOTE_HTTP), root.getMessage());
                    assertTrue(root.getMessage().contains(CleartextApiKeyPolicy.ALLOW_PROPERTY), root.getMessage());
                    assertFalse(root.getMessage().contains(KEY), "the error never carries the key");
                    assertEquals(1, logged(Level.ERROR), String.valueOf(appender.list));
                });
    }

    @Test
    void withTheOptInItStartsAndWarns() {
        runner.withPropertyValues("llm.api-key=" + KEY, "llm.base-url=" + REMOTE_HTTP,
                        CleartextApiKeyPolicy.ALLOW_PROPERTY + "=true")
                .run(context -> {
                    assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                    assertEquals(1, context.getBeanNamesForType(StreamingChatModel.class).length);
                    assertEquals(1, logged(Level.WARN), String.valueOf(appender.list));
                });
    }

    @Test
    void aKeyToLoopbackOrHttpsAndAKeylessRemoteCallStillStart() {
        runner.withPropertyValues("llm.mode=local", "llm.api-key=" + KEY)
                .run(context -> assertNull(context.getStartupFailure()));
        runner.withPropertyValues("llm.api-key=" + KEY, "llm.base-url=https://llm.invalid/v1")
                .run(context -> assertNull(context.getStartupFailure()));
        runner.withPropertyValues("llm.mode=local", "llm.base-url=" + REMOTE_HTTP)
                .run(context -> assertNull(context.getStartupFailure()));
        assertEquals(0, logged(Level.WARN) + logged(Level.ERROR), String.valueOf(appender.list));
    }
}
