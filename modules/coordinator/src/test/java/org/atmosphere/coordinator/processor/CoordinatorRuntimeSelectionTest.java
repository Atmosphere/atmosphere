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
package org.atmosphere.coordinator.processor;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AgentRuntimeSelectionException;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.coordinator.annotation.AgentRef;
import org.atmosphere.coordinator.annotation.Coordinator;
import org.atmosphere.coordinator.annotation.Fleet;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code @Coordinator} whose {@code atmosphere.ai.runtime} names no available
 * runtime fails its registration where the runtime is resolved, and with it
 * the framework's initialization, as an {@code @AiEndpoint} does: the failure
 * is not logged away as a missing runtime and left to whatever resolves the
 * runtime next.
 */
class CoordinatorRuntimeSelectionTest {

    @Agent(name = "selection-worker")
    public static class Worker {
    }

    @Coordinator(name = "selection-coordinator")
    @Fleet(@AgentRef(type = Worker.class))
    public static class SelectionCoordinator {
    }

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger().addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger().detachAppender(appender);
        appender.stop();
        System.clearProperty(AiConfig.RUNTIME_PROPERTY);
        AgentRuntimeResolver.reset();
    }

    private static ch.qos.logback.classic.Logger logger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CoordinatorProcessor.class);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"}) // Processor.handle takes Class<Object>
    void aRuntimeNamedButNotAvailableFailsTheRegistration() throws Exception {
        System.setProperty(AiConfig.RUNTIME_PROPERTY, "no-such-runtime");
        AgentRuntimeResolver.reset();
        var framework = new AtmosphereFramework();
        framework.init();
        try {
            var failure = assertThrows(AgentRuntimeSelectionException.class, () ->
                    new CoordinatorProcessor().handle(framework, (Class) SelectionCoordinator.class));
            assertTrue(failure.getMessage().contains(
                    "atmosphere.ai.runtime=no-such-runtime names no available AgentRuntime"), failure.getMessage());
            var swallowed = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.startsWith("Failed to resolve AgentRuntime"))
                    .toList();
            assertEquals(List.of(), swallowed, "the selection failure was logged and swallowed");
        } finally {
            framework.destroy();
        }
    }
}
