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

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the provider-neutral {@link AbstractAgentRuntime#configureNativeClient(Object)}
 * convenience entry point: it validates the argument against
 * {@link AbstractAgentRuntime#nativeClientClassName()} and delegates to the
 * type-checked {@code setNativeClient}. The provider-typed static setters
 * remain the primary wiring path; this covers only the reflective/non-DI
 * convenience method.
 */
class AbstractAgentRuntimeConfigureNativeClientTest {

    /** Minimal fake whose native client type is {@link String}. */
    static class StringClientRuntime extends AbstractAgentRuntime<String> {
        @Override
        public String name() {
            return "string-runtime";
        }

        @Override
        protected String nativeClientClassName() {
            return "java.lang.String";
        }

        @Override
        protected String createNativeClient(AiConfig.LlmSettings settings) {
            return null;
        }

        @Override
        protected void doExecute(String client, AgentExecutionContext context,
                                 StreamingSession session) {
            // no-op: this test exercises configureNativeClient, not execution
        }

        @Override
        protected String clientDescription() {
            return "StringClient";
        }

        @Override
        public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }
    }

    @Test
    void configureNativeClientInstallsAssignableClient() {
        var runtime = new StringClientRuntime();
        var client = "x";

        runtime.configureNativeClient(client);

        assertSame(client, runtime.getNativeClient(),
                "configureNativeClient should delegate the validated client to setNativeClient");
        assertEquals("x", runtime.getNativeClient());
    }

    @Test
    void configureNativeClientRejectsWrongTypeWithMessageNamingExpectedType() {
        var runtime = new StringClientRuntime();

        var ex = assertThrows(IllegalArgumentException.class,
                () -> runtime.configureNativeClient(Integer.valueOf(1)));

        assertTrue(ex.getMessage().contains("java.lang.String"),
                "message should name the expected native client type java.lang.String, was: "
                        + ex.getMessage());
        assertTrue(ex.getMessage().contains(Integer.class.getName()),
                "message should name the rejected argument type, was: " + ex.getMessage());
    }

    @Test
    void configureNativeClientRejectsNull() {
        var runtime = new StringClientRuntime();

        var ex = assertThrows(IllegalArgumentException.class,
                () -> runtime.configureNativeClient(null));

        assertTrue(ex.getMessage().contains("must not be null"),
                "message should explain that the native client must not be null, was: "
                        + ex.getMessage());
    }
}
