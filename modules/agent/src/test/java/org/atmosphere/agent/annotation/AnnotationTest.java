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
package org.atmosphere.agent.annotation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Agent} and {@link Command} annotation metadata.
 */
public class AnnotationTest {

    @Agent(name = "test", skillFile = "skill.md", description = "A test agent")
    static class FullAgent {
    }

    @Agent(name = "minimal")
    static class MinimalAgent {
    }

    static class CommandHolder {
        @Command(value = "/full", description = "Full command", confirm = "Sure?")
        public String fullCommand() {
            return "ok";
        }

        @Command("/minimal")
        public String minimalCommand() {
            return "ok";
        }
    }

    @Test
    public void testAgentAnnotationFull() {
        var annotation = FullAgent.class.getAnnotation(Agent.class);
        assertNotNull(annotation);
        assertEquals("test", annotation.name());
        assertEquals("skill.md", annotation.skillFile());
        assertEquals("A test agent", annotation.description());
    }

    @Test
    public void testAgentAnnotationDefaults() {
        var annotation = MinimalAgent.class.getAnnotation(Agent.class);
        assertNotNull(annotation);
        assertEquals("minimal", annotation.name());
        assertEquals("", annotation.skillFile());
        assertEquals("", annotation.description());
    }

    @Test
    public void testAgentRetention() {
        // Must be RUNTIME for framework scanning
        var retention = Agent.class.getAnnotation(java.lang.annotation.Retention.class);
        assertNotNull(retention);
        assertEquals(java.lang.annotation.RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testAgentTarget() {
        var target = Agent.class.getAnnotation(java.lang.annotation.Target.class);
        assertNotNull(target);
        assertEquals(1, target.value().length);
        assertEquals(java.lang.annotation.ElementType.TYPE, target.value()[0]);
    }

    @Test
    public void testCommandAnnotationFull() throws Exception {
        var method = CommandHolder.class.getDeclaredMethod("fullCommand");
        var annotation = method.getAnnotation(Command.class);
        assertNotNull(annotation);
        assertEquals("/full", annotation.value());
        assertEquals("Full command", annotation.description());
        assertEquals("Sure?", annotation.confirm());
    }

    @Test
    public void testCommandAnnotationDefaults() throws Exception {
        var method = CommandHolder.class.getDeclaredMethod("minimalCommand");
        var annotation = method.getAnnotation(Command.class);
        assertNotNull(annotation);
        assertEquals("/minimal", annotation.value());
        assertEquals("", annotation.description());
        assertEquals("", annotation.confirm());
    }

    @Test
    public void testCommandRetention() {
        var retention = Command.class.getAnnotation(java.lang.annotation.Retention.class);
        assertNotNull(retention);
        assertEquals(java.lang.annotation.RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testCommandTarget() {
        var target = Command.class.getAnnotation(java.lang.annotation.Target.class);
        assertNotNull(target);
        assertEquals(1, target.value().length);
        assertEquals(java.lang.annotation.ElementType.METHOD, target.value()[0]);
    }
}
