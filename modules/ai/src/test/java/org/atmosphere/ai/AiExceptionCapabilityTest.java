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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiExceptionCapabilityTest {

    // --- AiException ---

    @Test
    void exceptionWithMessage() {
        var ex = new AiException("test error");
        assertEquals("test error", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void exceptionWithMessageAndCause() {
        var cause = new RuntimeException("root");
        var ex = new AiException("wrapper", cause);
        assertEquals("wrapper", ex.getMessage());
        assertEquals(cause, ex.getCause());
    }

    @Test
    void exceptionIsRuntimeException() {
        assertTrue(RuntimeException.class.isAssignableFrom(AiException.class));
    }

    // --- AiCapability ---

    @Test
    void allCapabilitiesExist() {
        var values = AiCapability.values();
        assertNotNull(values);
        assertTrue(values.length >= 15, "Expected at least 15 capabilities");
    }

    @Test
    void textStreamingCapability() {
        assertEquals(AiCapability.TEXT_STREAMING, AiCapability.valueOf("TEXT_STREAMING"));
    }

    @Test
    void toolCallingCapability() {
        assertEquals(AiCapability.TOOL_CALLING, AiCapability.valueOf("TOOL_CALLING"));
    }

    @Test
    void structuredOutputCapability() {
        assertEquals(AiCapability.STRUCTURED_OUTPUT, AiCapability.valueOf("STRUCTURED_OUTPUT"));
    }

    @Test
    void visionCapability() {
        assertEquals(AiCapability.VISION, AiCapability.valueOf("VISION"));
    }

    @Test
    void agentOrchestrationCapability() {
        assertEquals(AiCapability.AGENT_ORCHESTRATION, AiCapability.valueOf("AGENT_ORCHESTRATION"));
    }

    @Test
    void cancellationCapability() {
        assertEquals(AiCapability.CANCELLATION, AiCapability.valueOf("CANCELLATION"));
    }

    @Test
    void tokenUsageCapability() {
        assertEquals(AiCapability.TOKEN_USAGE, AiCapability.valueOf("TOKEN_USAGE"));
    }

    @Test
    void perRequestRetryCapability() {
        assertEquals(AiCapability.PER_REQUEST_RETRY, AiCapability.valueOf("PER_REQUEST_RETRY"));
    }

    @Test
    void toolCallDeltaCapability() {
        assertEquals(AiCapability.TOOL_CALL_DELTA, AiCapability.valueOf("TOOL_CALL_DELTA"));
    }

    @Test
    void multiAgentHandoffCapability() {
        assertEquals(AiCapability.MULTI_AGENT_HANDOFF, AiCapability.valueOf("MULTI_AGENT_HANDOFF"));
    }
}
