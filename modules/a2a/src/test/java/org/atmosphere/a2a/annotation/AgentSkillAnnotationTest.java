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
package org.atmosphere.a2a.annotation;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for {@link AgentSkill}, {@link AgentSkillParam}, and {@link AgentSkillHandler}
 * annotation metadata: retention, target, default values, and reflective discovery.
 */
class AgentSkillAnnotationTest {

    // --- Annotated fixtures ---

    @AgentSkill(id = "echo", name = "Echo Skill", description = "Echoes input", tags = {"test", "echo"})
    @AgentSkillHandler
    void annotatedMethod(@AgentSkillParam(name = "input", description = "The input text") String input,
                         @AgentSkillParam(name = "count", required = false) int count) {
        // fixture only
    }

    @AgentSkill(id = "minimal", name = "Minimal")
    @AgentSkillHandler
    void minimalMethod() {
        // fixture with defaults
    }

    // --- AgentSkill retention and target ---

    @Test
    void agentSkillHasRuntimeRetention() {
        var retention = AgentSkill.class.getAnnotation(java.lang.annotation.Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void agentSkillTargetsMethod() {
        var target = AgentSkill.class.getAnnotation(java.lang.annotation.Target.class);
        assertNotNull(target);
        assertArrayEquals(new ElementType[]{ElementType.METHOD}, target.value());
    }

    // --- AgentSkillParam retention and target ---

    @Test
    void agentSkillParamHasRuntimeRetention() {
        var retention = AgentSkillParam.class.getAnnotation(java.lang.annotation.Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void agentSkillParamTargetsParameter() {
        var target = AgentSkillParam.class.getAnnotation(java.lang.annotation.Target.class);
        assertNotNull(target);
        assertArrayEquals(new ElementType[]{ElementType.PARAMETER}, target.value());
    }

    // --- AgentSkillHandler retention and target ---

    @Test
    void agentSkillHandlerHasRuntimeRetention() {
        var retention = AgentSkillHandler.class.getAnnotation(java.lang.annotation.Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void agentSkillHandlerTargetsMethod() {
        var target = AgentSkillHandler.class.getAnnotation(java.lang.annotation.Target.class);
        assertNotNull(target);
        assertArrayEquals(new ElementType[]{ElementType.METHOD}, target.value());
    }

    // --- Reflective presence on annotated method ---

    @Test
    void agentSkillPresentWithExplicitValues() throws Exception {
        Method m = getClass().getDeclaredMethod("annotatedMethod", String.class, int.class);
        var skill = m.getAnnotation(AgentSkill.class);
        assertNotNull(skill);
        assertEquals("echo", skill.id());
        assertEquals("Echo Skill", skill.name());
        assertEquals("Echoes input", skill.description());
        assertArrayEquals(new String[]{"test", "echo"}, skill.tags());
    }

    @Test
    void agentSkillDefaultValues() throws Exception {
        Method m = getClass().getDeclaredMethod("minimalMethod");
        var skill = m.getAnnotation(AgentSkill.class);
        assertNotNull(skill);
        assertEquals("minimal", skill.id());
        assertEquals("Minimal", skill.name());
        assertEquals("", skill.description());
        assertArrayEquals(new String[]{}, skill.tags());
    }

    @Test
    void agentSkillHandlerPresent() throws Exception {
        Method m = getClass().getDeclaredMethod("annotatedMethod", String.class, int.class);
        assertNotNull(m.getAnnotation(AgentSkillHandler.class));
    }

    // --- AgentSkillParam reflective discovery ---

    @Test
    void agentSkillParamPresentWithExplicitValues() throws Exception {
        Method m = getClass().getDeclaredMethod("annotatedMethod", String.class, int.class);
        var params = m.getParameters();
        var param0 = params[0].getAnnotation(AgentSkillParam.class);
        assertNotNull(param0);
        assertEquals("input", param0.name());
        assertEquals("The input text", param0.description());
        assertEquals(true, param0.required());
    }

    @Test
    void agentSkillParamDefaultValues() throws Exception {
        Method m = getClass().getDeclaredMethod("annotatedMethod", String.class, int.class);
        var params = m.getParameters();
        var param1 = params[1].getAnnotation(AgentSkillParam.class);
        assertNotNull(param1);
        assertEquals("count", param1.name());
        assertEquals("", param1.description());
        assertEquals(false, param1.required());
    }
}
