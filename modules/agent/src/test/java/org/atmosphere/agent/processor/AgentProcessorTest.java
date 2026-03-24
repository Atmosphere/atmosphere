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

import org.atmosphere.a2a.annotation.A2aParam;
import org.atmosphere.a2a.annotation.A2aSkill;
import org.atmosphere.a2a.annotation.A2aTaskHandler;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.types.Artifact;
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

    // ── Headless agent test classes ──

    @Agent(name = "headless-a2a", endpoint = "/atmosphere/a2a/headless",
            description = "Headless agent with A2A skills")
    static class HeadlessA2aAgent {
        @A2aSkill(id = "greet", name = "Greet", description = "Say hello",
                tags = {"greeting"})
        @A2aTaskHandler
        public void greet(TaskContext task,
                          @A2aParam(name = "name", description = "Name") String name) {
            task.addArtifact(Artifact.text("Hello " + name));
            task.complete("Greeted " + name);
        }
    }

    @Agent(name = "explicit-headless", headless = true,
            description = "Explicitly headless agent")
    static class ExplicitHeadlessAgent {
        @Prompt
        public void onPrompt(String msg, StreamingSession session) {
            session.stream(msg);
        }
    }

    @Agent(name = "mixed-agent", description = "Full agent with A2A skills")
    static class MixedAgent {
        @Prompt
        public void onPrompt(String msg, StreamingSession session) {
            session.stream(msg);
        }

        @A2aSkill(id = "compute", name = "Compute", description = "Compute something",
                tags = {"math"})
        @A2aTaskHandler
        public void compute(TaskContext task,
                            @A2aParam(name = "expr", description = "Expression") String expr) {
            task.addArtifact(Artifact.text("Result: " + expr));
            task.complete("Computed");
        }
    }

    @Agent(name = "custom-endpoint", endpoint = "/api/custom",
            version = "2.0.0", description = "Custom endpoint agent")
    static class CustomEndpointAgent {
        @A2aSkill(id = "ping", name = "Ping", description = "Ping",
                tags = {"health"})
        @A2aTaskHandler
        public void ping(TaskContext task) {
            task.complete("pong");
        }
    }

    // ── Headless detection tests ──

    @Test
    public void testHeadlessAutoDetected() {
        var processor = new AgentProcessor();
        var annotation = HeadlessA2aAgent.class.getAnnotation(Agent.class);
        assertTrue(processor.isHeadless(annotation, HeadlessA2aAgent.class),
                "Agent with @A2aSkill methods and no @Prompt should be headless");
    }

    @Test
    public void testExplicitHeadlessFlag() {
        var processor = new AgentProcessor();
        var annotation = ExplicitHeadlessAgent.class.getAnnotation(Agent.class);
        assertTrue(processor.isHeadless(annotation, ExplicitHeadlessAgent.class),
                "Agent with headless=true should be headless even with @Prompt");
    }

    @Test
    public void testMixedAgentNotHeadless() {
        var processor = new AgentProcessor();
        var annotation = MixedAgent.class.getAnnotation(Agent.class);
        assertFalse(processor.isHeadless(annotation, MixedAgent.class),
                "Agent with both @Prompt and @A2aSkill should NOT be headless");
    }

    @Test
    public void testFullAgentNotHeadless() {
        var processor = new AgentProcessor();
        var annotation = FullAgent.class.getAnnotation(Agent.class);
        assertFalse(processor.isHeadless(annotation, FullAgent.class),
                "Agent with @Prompt should NOT be headless");
    }

    @Test
    public void testMinimalAgentNotHeadless() {
        var processor = new AgentProcessor();
        var annotation = MinimalAgent.class.getAnnotation(Agent.class);
        assertFalse(processor.isHeadless(annotation, MinimalAgent.class),
                "Agent with no @Prompt and no @A2aSkill should NOT be headless");
    }

    // ── Annotation attribute tests ──

    @Test
    public void testCustomEndpointAttribute() {
        var annotation = CustomEndpointAgent.class.getAnnotation(Agent.class);
        assertEquals("/api/custom", annotation.endpoint());
    }

    @Test
    public void testVersionAttribute() {
        var annotation = CustomEndpointAgent.class.getAnnotation(Agent.class);
        assertEquals("2.0.0", annotation.version());
    }

    @Test
    public void testDefaultEndpointIsEmpty() {
        var annotation = MinimalAgent.class.getAnnotation(Agent.class);
        assertEquals("", annotation.endpoint());
    }

    @Test
    public void testDefaultVersionIs1() {
        var annotation = MinimalAgent.class.getAnnotation(Agent.class);
        assertEquals("1.0.0", annotation.version());
    }

    @Test
    public void testDefaultHeadlessIsFalse() {
        var annotation = MinimalAgent.class.getAnnotation(Agent.class);
        assertFalse(annotation.headless());
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
