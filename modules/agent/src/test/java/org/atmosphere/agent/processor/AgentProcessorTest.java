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

import org.atmosphere.agent.ClasspathDetector;
import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.agent.annotation.Command;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Prompt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgentProcessor} — specifically the annotation scanning,
 * skill file parsing, and zero-code agent support.
 */
public class AgentProcessorTest {

    @Agent(name = "test-agent", description = "A test agent")
    static class MinimalAgent {
    }

    @Agent(name = "full-agent", description = "Full agent")
    static class FullAgent {
        @Command(value = "/status", description = "Show status")
        public String status() {
            return "OK";
        }

        @Command(value = "/deploy", confirm = "Really?")
        public String deploy(String args) {
            return "Deployed " + args;
        }

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            session.stream(message);
        }

        @AiTool(name = "weather", description = "Get weather")
        public String getWeather(String city) {
            return "Sunny in " + city;
        }
    }

    @Test
    public void testAnnotationPresent() {
        var annotation = MinimalAgent.class.getAnnotation(Agent.class);
        assertNotNull(annotation);
        assertEquals("test-agent", annotation.name());
        assertEquals("A test agent", annotation.description());
        assertEquals("", annotation.skillFile());
    }

    @Test
    public void testFullAgentAnnotation() {
        var annotation = FullAgent.class.getAnnotation(Agent.class);
        assertNotNull(annotation);
        assertEquals("full-agent", annotation.name());
    }

    @Test
    public void testFullAgentHasPrompt() {
        var hasPrompt = false;
        for (var method : FullAgent.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Prompt.class)) {
                hasPrompt = true;
                break;
            }
        }
        assertTrue(hasPrompt);
    }

    @Test
    public void testFullAgentHasCommands() {
        var commandCount = 0;
        for (var method : FullAgent.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Command.class)) {
                commandCount++;
            }
        }
        assertEquals(2, commandCount);
    }

    @Test
    public void testFullAgentHasTools() {
        var toolCount = 0;
        for (var method : FullAgent.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(AiTool.class)) {
                toolCount++;
            }
        }
        assertEquals(1, toolCount);
    }

    @Test
    public void testClasspathDetectorA2a() {
        // A2A is on the classpath in this test (optional dep)
        assertTrue(ClasspathDetector.hasA2a());
    }

    @Test
    public void testClasspathDetectorMcp() {
        // MCP is on the classpath in this test (optional dep)
        assertTrue(ClasspathDetector.hasMcp());
    }

    @Test
    public void testClasspathDetectorUnknown() {
        assertFalse(ClasspathDetector.isPresent("com.nonexistent.FakeClass"));
    }

    @Test
    public void testSyntheticPromptExists() {
        // Verify the SyntheticPrompt inner class has a @Prompt method
        var found = false;
        for (var method : AgentProcessor.SyntheticPrompt.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Prompt.class)) {
                found = true;
                assertEquals(2, method.getParameterCount());
                assertEquals(String.class, method.getParameterTypes()[0]);
                assertEquals(StreamingSession.class, method.getParameterTypes()[1]);
                break;
            }
        }
        assertTrue(found, "SyntheticPrompt must have a @Prompt method");
    }
}
