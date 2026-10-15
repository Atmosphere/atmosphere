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
package org.atmosphere.agent.processor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.agent.skill.ScopePolicyResolver;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link AgentProcessor#handle} end to end for an {@code @Agent} whose
 * explicit {@code @AgentScope(unrestricted = true)} overrides the scope its
 * skill's {@code ## Guardrails} section would derive: registration must log
 * exactly one WARN naming the agent, the skill and the justification, even
 * though the web chain and the pipeline each resolve the scope.
 */
class AgentProcessorScopeOverrideWarnTest {

    @Agent(name = "unrestricted-override-agent",
            skillFile = "prompts/unrestricted-override-agent.md")
    @AgentScope(unrestricted = true, justification = "General-purpose demo assistant")
    public static class UnrestrictedOverrideAgent {
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
            new AgentProcessor().handle(framework, (Class) UnrestrictedOverrideAgent.class);
            assertOneOverrideWarn(framework);
        } finally {
            framework.destroy();
        }
    }

    private void assertOneOverrideWarn(AtmosphereFramework framework) {
        assertTrue(framework.getAtmosphereHandlers()
                        .containsKey("/atmosphere/agent/unrestricted-override-agent"),
                "registration must complete, so the WARN count reflects a full handle()");
        List<ILoggingEvent> warnings = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertEquals(1, warnings.size(),
                "registration must log exactly one WARN for the override, got " + warnings);
        var message = warnings.get(0).getFormattedMessage();
        assertTrue(message.contains("unrestricted-override-agent"), message);
        assertTrue(message.contains("prompts/unrestricted-override-agent.md"), message);
        assertTrue(message.contains("General-purpose demo assistant"), message);
    }
}
