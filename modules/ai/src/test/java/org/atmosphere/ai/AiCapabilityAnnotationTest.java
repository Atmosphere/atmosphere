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
package org.atmosphere.ai;

import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.annotation.RequiresApproval;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for AI annotation metadata: {@link AiTool}, {@link Prompt},
 * {@link Param}, and {@link RequiresApproval}.
 */
class AiCapabilityAnnotationTest {

    // --- Annotated fixtures ---

    @AiTool(name = "get_weather", description = "Get current weather for a city")
    @RequiresApproval(value = "Weather lookup costs money. Approve?", timeoutSeconds = 60)
    String getWeather(@Param(value = "city", description = "City name") String city,
                      @Param(value = "unit", required = false) String unit) {
        return "sunny";
    }

    @Prompt
    void onPrompt() {
        // fixture
    }

    @AiTool(name = "noop", description = "Does nothing")
    void noopTool() {
        // fixture with no params
    }

    // --- AiTool retention and target ---

    @Test
    void aiToolHasRuntimeRetention() {
        var retention = AiTool.class.getAnnotation(java.lang.annotation.Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void aiToolTargetsMethod() {
        var target = AiTool.class.getAnnotation(java.lang.annotation.Target.class);
        assertNotNull(target);
        assertEquals(1, target.value().length);
        assertEquals(ElementType.METHOD, target.value()[0]);
    }

    // --- Prompt retention and target ---

    @Test
    void promptHasRuntimeRetention() {
        var retention = Prompt.class.getAnnotation(java.lang.annotation.Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void promptTargetsMethod() {
        var target = Prompt.class.getAnnotation(java.lang.annotation.Target.class);
        assertNotNull(target);
        assertEquals(1, target.value().length);
        assertEquals(ElementType.METHOD, target.value()[0]);
    }

    // --- Param retention and target ---

    @Test
    void paramHasRuntimeRetention() {
        var retention = Param.class.getAnnotation(java.lang.annotation.Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void paramTargetsParameter() {
        var target = Param.class.getAnnotation(java.lang.annotation.Target.class);
        assertNotNull(target);
        assertEquals(1, target.value().length);
        assertEquals(ElementType.PARAMETER, target.value()[0]);
    }

    // --- RequiresApproval retention and target ---

    @Test
    void requiresApprovalHasRuntimeRetention() {
        var retention = RequiresApproval.class.getAnnotation(java.lang.annotation.Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void requiresApprovalTargetsMethod() {
        var target = RequiresApproval.class.getAnnotation(java.lang.annotation.Target.class);
        assertNotNull(target);
        assertEquals(1, target.value().length);
        assertEquals(ElementType.METHOD, target.value()[0]);
    }

    // --- Reflective presence ---

    @Test
    void aiToolPresentWithValues() throws Exception {
        Method m = getClass().getDeclaredMethod("getWeather", String.class, String.class);
        var tool = m.getAnnotation(AiTool.class);
        assertNotNull(tool);
        assertEquals("get_weather", tool.name());
        assertEquals("Get current weather for a city", tool.description());
    }

    @Test
    void promptPresentOnHandler() throws Exception {
        Method m = getClass().getDeclaredMethod("onPrompt");
        assertNotNull(m.getAnnotation(Prompt.class));
    }

    @Test
    void paramPresentWithExplicitValues() throws Exception {
        Method m = getClass().getDeclaredMethod("getWeather", String.class, String.class);
        var params = m.getParameters();
        var p0 = params[0].getAnnotation(Param.class);
        assertNotNull(p0);
        assertEquals("city", p0.value());
        assertEquals("City name", p0.description());
        assertEquals(true, p0.required());
    }

    @Test
    void paramDefaultValues() throws Exception {
        Method m = getClass().getDeclaredMethod("getWeather", String.class, String.class);
        var params = m.getParameters();
        var p1 = params[1].getAnnotation(Param.class);
        assertNotNull(p1);
        assertEquals("unit", p1.value());
        assertEquals("", p1.description());
        assertEquals(false, p1.required());
    }

    @Test
    void requiresApprovalPresentWithValues() throws Exception {
        Method m = getClass().getDeclaredMethod("getWeather", String.class, String.class);
        var approval = m.getAnnotation(RequiresApproval.class);
        assertNotNull(approval);
        assertEquals("Weather lookup costs money. Approve?", approval.value());
        assertEquals(60, approval.timeoutSeconds());
    }

    @Test
    void requiresApprovalDefaultTimeout() throws Exception {
        // Default timeout is 300 per the annotation definition
        assertEquals(300L, RequiresApproval.class.getMethod("timeoutSeconds").getDefaultValue());
    }
}
