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
package org.atmosphere.ai.tool;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.atmosphere.ai.jfr.ToolInvocationEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the tri-state tool permission policy: ALLOW fast-path, DENY refuses
 * with a cancellation JSON + JFR event, CONFIRM forces the approval gate.
 */
class ToolPermissionPolicyTest {

    private ToolPermissionPolicy previousGlobal;

    @BeforeEach
    void snapshotGlobal() {
        previousGlobal = ToolPermissionPolicy.global();
    }

    @AfterEach
    void restoreGlobal() {
        ToolPermissionPolicy.setGlobal(previousGlobal);
    }

    @Test
    void defaultGlobalIsAllowAll() {
        assertSame(ToolPermissionPolicy.ALLOW_ALL, ToolPermissionPolicy.global());
    }

    @Test
    void propertiesPolicyResolvesPerToolKey() {
        var props = new Properties();
        props.setProperty("atmosphere.tools.permissions.default", "allow");
        props.setProperty("atmosphere.tools.permissions.delete_account", "deny");
        props.setProperty("atmosphere.tools.permissions.run_script", "confirm");

        var policy = PropertiesToolPermissionPolicy.from(props);
        assertEquals(ToolPermission.DENY, policy.decide("delete_account", Map.of()));
        assertEquals(ToolPermission.CONFIRM, policy.decide("run_script", Map.of()));
        assertEquals(ToolPermission.ALLOW, policy.decide("get_weather", Map.of()));
    }

    @Test
    void propertiesPolicyFallsBackToDefaultOnUnknownValue() {
        var props = new Properties();
        props.setProperty("atmosphere.tools.permissions.default", "deny");
        props.setProperty("atmosphere.tools.permissions.weird", "maybe");
        var policy = PropertiesToolPermissionPolicy.from(props);
        assertEquals(ToolPermission.DENY, policy.decide("weird", Map.of()));
        assertEquals(ToolPermission.DENY, policy.decide("never_configured", Map.of()));
    }

    @Test
    void propertiesPolicyDefaultsToAllowWhenUnset() {
        var policy = PropertiesToolPermissionPolicy.from(new Properties());
        assertEquals(ToolPermission.ALLOW, policy.decide("anything", Map.of()));
    }

    @Test
    void executeWithApprovalDenyShortCircuitsAndEmitsJfr() throws Exception {
        var props = new Properties();
        props.setProperty("atmosphere.tools.permissions.dangerous", "deny");
        ToolPermissionPolicy.setGlobal(PropertiesToolPermissionPolicy.from(props));

        var executor = new CountingExecutor();
        var def = ToolDefinition.builder("dangerous", "test").executor(executor).build();

        var events = recordAndCollect("org.atmosphere.ai.ToolInvocation", () -> {
            var result = ToolExecutionHelper.executeWithApproval(
                    "dangerous", def, Map.of(),
                    null, null,
                    ToolApprovalPolicy.annotated());
            assertNotNull(result);
            assertTrue(result.contains("cancelled"),
                    "DENY decision must short-circuit with a cancellation result, got: " + result);
        });

        assertEquals(0, executor.calls,
                "DENY must not invoke the underlying tool executor");
        assertFalse(events.isEmpty(), "DENY must emit a ToolInvocation JFR event");
        var denied = events.stream()
                .filter(e -> ToolInvocationEvent.OUTCOME_DENIED.equals(e.getValue("outcome")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no DENIED ToolInvocation event"));
        assertEquals("dangerous", denied.getValue("tool"));
    }

    @Test
    void executeWithApprovalAllowRunsExecutor() {
        ToolPermissionPolicy.setGlobal(ToolPermissionPolicy.ALLOW_ALL);
        var executor = new CountingExecutor();
        var def = ToolDefinition.builder("calculator", "test").executor(executor).build();

        var result = ToolExecutionHelper.executeWithApproval(
                "calculator", def, Map.of(),
                null, null,
                ToolApprovalPolicy.annotated());

        assertEquals(1, executor.calls);
        assertEquals("ok", result);
    }

    private static List<RecordedEvent> recordAndCollect(String eventName, Runnable body) throws Exception {
        try (var recording = new Recording()) {
            recording.enable(eventName);
            recording.start();
            body.run();
            recording.stop();
            var dump = Files.createTempFile("atmosphere-perm-test-", ".jfr");
            try {
                recording.dump(dump);
                var events = new ArrayList<RecordedEvent>();
                try (var file = new RecordingFile(dump)) {
                    while (file.hasMoreEvents()) {
                        var event = file.readEvent();
                        if (event.getEventType().getName().equals(eventName)) {
                            events.add(event);
                        }
                    }
                }
                return events;
            } finally {
                Files.deleteIfExists(dump);
            }
        }
    }

    private static class CountingExecutor implements ToolExecutor {
        final AtomicInteger callCounter = new AtomicInteger();
        int calls;

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            calls = callCounter.incrementAndGet();
            return "ok";
        }
    }
}
