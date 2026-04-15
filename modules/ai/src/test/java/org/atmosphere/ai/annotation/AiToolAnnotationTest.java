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
package org.atmosphere.ai.annotation;

import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Annotation contract tests for {@link AiTool}, {@link Param},
 * {@link Prompt}, and {@link RequiresApproval}.
 */
class AiToolAnnotationTest {

    // ── @AiTool ──

    @Test
    void aiToolRetainedAtRuntime() {
        var retention = AiTool.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void aiToolTargetsMethods() {
        var target = AiTool.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(new ElementType[]{ElementType.METHOD}, target.value());
    }

    @Test
    void aiToolIsDocumented() {
        assertNotNull(AiTool.class.getAnnotation(Documented.class));
    }

    @Test
    void aiToolAttributesAccessible() throws Exception {
        var method = SampleToolProvider.class.getDeclaredMethod("weather", String.class);
        var annotation = method.getAnnotation(AiTool.class);
        assertNotNull(annotation);
        assertEquals("get_weather", annotation.name());
        assertEquals("Get weather for a city", annotation.description());
    }

    // ── @Param ──

    @Test
    void paramRetainedAtRuntime() {
        var retention = Param.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void paramTargetsParameters() {
        var target = Param.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(new ElementType[]{ElementType.PARAMETER}, target.value());
    }

    @Test
    void paramIsDocumented() {
        assertNotNull(Param.class.getAnnotation(Documented.class));
    }

    @Test
    void paramDefaultDescription() throws Exception {
        var method = SampleToolProvider.class.getDeclaredMethod("weather", String.class);
        var param = method.getParameters()[0].getAnnotation(Param.class);
        assertNotNull(param);
        assertEquals("city", param.value());
        assertEquals("", param.description());
        assertTrue(param.required());
    }

    @Test
    void paramCustomDescriptionAndRequired() throws Exception {
        var method = SampleToolProvider.class.getDeclaredMethod("search",
                String.class, int.class);
        var params = method.getParameters();

        var query = params[0].getAnnotation(Param.class);
        assertEquals("query", query.value());
        assertEquals("The search query", query.description());
        assertTrue(query.required());

        var limit = params[1].getAnnotation(Param.class);
        assertEquals("limit", limit.value());
        assertEquals("Max results", limit.description());
        assertEquals(false, limit.required());
    }

    // ── @Prompt ──

    @Test
    void promptRetainedAtRuntime() {
        var retention = Prompt.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void promptTargetsMethods() {
        var target = Prompt.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(new ElementType[]{ElementType.METHOD}, target.value());
    }

    @Test
    void promptIsDocumented() {
        assertNotNull(Prompt.class.getAnnotation(Documented.class));
    }

    @Test
    void promptDetectedOnMethod() throws Exception {
        Method method = PromptHolder.class.getDeclaredMethod("onPrompt",
                String.class, StreamingSession.class);
        assertNotNull(method.getAnnotation(Prompt.class));
    }

    @Test
    void promptNotPresentOnUnannotatedMethod() throws Exception {
        Method method = PromptHolder.class.getDeclaredMethod("notAPrompt",
                String.class);
        var annotation = method.getAnnotation(Prompt.class);
        assertEquals(null, annotation);
    }

    // ── @RequiresApproval ──

    @Test
    void requiresApprovalRetainedAtRuntime() {
        var retention = RequiresApproval.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void requiresApprovalTargetsMethods() {
        var target = RequiresApproval.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(new ElementType[]{ElementType.METHOD}, target.value());
    }

    @Test
    void requiresApprovalDefaultTimeout() throws Exception {
        var method = ApprovalHolder.class.getDeclaredMethod("defaultTimeout");
        var annotation = method.getAnnotation(RequiresApproval.class);
        assertNotNull(annotation);
        assertEquals("Confirm action?", annotation.value());
        assertEquals(300, annotation.timeoutSeconds());
    }

    @Test
    void requiresApprovalCustomTimeout() throws Exception {
        var method = ApprovalHolder.class.getDeclaredMethod("customTimeout");
        var annotation = method.getAnnotation(RequiresApproval.class);
        assertNotNull(annotation);
        assertEquals("Dangerous!", annotation.value());
        assertEquals(120, annotation.timeoutSeconds());
    }

    @Test
    void requiresApprovalNotDocumented() {
        // RequiresApproval is intentionally NOT @Documented (unlike AiTool/Param/Prompt)
        var documented = RequiresApproval.class.getAnnotation(Documented.class);
        assertEquals(null, documented);
    }

    // ---- Test fixture classes ----

    @SuppressWarnings("unused")
    static class SampleToolProvider {
        @AiTool(name = "get_weather", description = "Get weather for a city")
        public String weather(@Param("city") String city) {
            return "sunny in " + city;
        }

        @AiTool(name = "search_docs", description = "Search documents")
        public String search(
                @Param(value = "query", description = "The search query") String query,
                @Param(value = "limit", description = "Max results",
                       required = false) int limit) {
            return "results";
        }
    }

    @SuppressWarnings("unused")
    static class PromptHolder {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
        }

        public void notAPrompt(String message) {
        }
    }

    @SuppressWarnings("unused")
    static class ApprovalHolder {
        @RequiresApproval("Confirm action?")
        public void defaultTimeout() {
        }

        @RequiresApproval(value = "Dangerous!", timeoutSeconds = 120)
        public void customTimeout() {
        }
    }
}
