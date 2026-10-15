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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.agent.skill.ScopePolicyResolver;
import org.atmosphere.ai.annotation.AgentScope;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link CoordinatorProcessor#handle} end to end for a
 * {@code @Coordinator} whose explicit {@code @AgentScope(unrestricted = true)}
 * overrides the scope its skill's {@code ## Guardrails} section would derive:
 * registration must log exactly one WARN naming the coordinator, the skill and
 * the justification, as on the {@code @Agent} path.
 */
class CoordinatorProcessorScopeOverrideWarnTest {

    @Agent(name = "override-warn-worker")
    public static class Worker {
    }

    @Coordinator(name = "unrestricted-override-coordinator",
            skillFile = "prompts/unrestricted-override-coordinator.md")
    @Fleet(@AgentRef(type = Worker.class))
    @AgentScope(unrestricted = true, justification = "Broad demo coordinator")
    public static class UnrestrictedOverrideCoordinator {
    }

    /** Restrictive but invalid (blank purpose) over a skill whose guardrails derive a scope. */
    @Coordinator(name = "invalid-scope-coordinator",
            skillFile = "prompts/invalid-scope-coordinator.md")
    @Fleet(@AgentRef(type = Worker.class))
    @AgentScope(forbiddenTopics = "gambling", tier = AgentScope.Tier.RULE_BASED)
    public static class InvalidScopeCoordinator {
    }

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        logger().addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger().detachAppender(appender);
        appender.stop();
    }

    private static ch.qos.logback.classic.Logger logger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ScopePolicyResolver.class);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"}) // Processor.handle takes Class<Object>
    void registrationLogsOneWarnForTheOverride() throws Exception {
        var framework = new AtmosphereFramework();
        framework.init();
        try {
            appender.list.clear();
            new CoordinatorProcessor().handle(framework, (Class) UnrestrictedOverrideCoordinator.class);

            assertTrue(framework.getAtmosphereHandlers()
                            .containsKey("/atmosphere/agent/unrestricted-override-coordinator"),
                    "registration must complete, so the WARN count reflects a full handle()");
            List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .toList();
            assertEquals(1, warnings.size(),
                    "registration must log exactly one WARN for the override, got " + warnings);
            var message = warnings.get(0).getFormattedMessage();
            assertTrue(message.contains("unrestricted-override-coordinator"), message);
            assertTrue(message.contains("prompts/unrestricted-override-coordinator.md"), message);
            assertTrue(message.contains("Broad demo coordinator"), message);
        } finally {
            framework.destroy();
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"}) // Processor.handle takes Class<Object>
    void registrationLogsOneErrorForAnInvalidAnnotation() throws Exception {
        var framework = new AtmosphereFramework();
        framework.init();
        try {
            appender.list.clear();
            new CoordinatorProcessor().handle(framework, (Class) InvalidScopeCoordinator.class);

            assertTrue(framework.getAtmosphereHandlers()
                            .containsKey("/atmosphere/agent/invalid-scope-coordinator"),
                    "registration must complete, so the ERROR count reflects a full handle()");
            List<ILoggingEvent> errors = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .toList();
            assertEquals(1, errors.size(), "registration must log exactly one ERROR for the "
                    + "invalid @AgentScope, although the web chain and the pipeline each "
                    + "resolve the scope, got " + errors);
            var message = errors.get(0).getFormattedMessage();
            assertTrue(message.contains(InvalidScopeCoordinator.class.getName()), message);
            assertTrue(message.contains("prompts/invalid-scope-coordinator.md"), message);
        } finally {
            framework.destroy();
        }
    }
}
