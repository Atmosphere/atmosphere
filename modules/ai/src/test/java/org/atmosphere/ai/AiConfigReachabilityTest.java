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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the answer to "is there a model to talk to".
 *
 * <p>Five sites independently used {@code apiKey() != null} as the proxy for
 * that question, and every one silently degraded a working keyless-local
 * deployment: {@code DemoAgentRuntime} shadowed every real runtime, the
 * LangChain4j auto-configuration built no model, the built-in embedding runtime
 * threw before issuing a request, and three samples answered "configure an API
 * key" with a model running and idle on localhost. None failed loudly — the
 * samples just quietly stopped exercising their own headline feature.</p>
 */
class AiConfigReachabilityTest {

    private static AiConfig.LlmSettings settings(String mode, String apiKey) {
        return new AiConfig.LlmSettings(null, "test-model", mode,
                "http://localhost:11434/v1", apiKey);
    }

    @Test
    void aLocalBackendIsReachableWithoutAnyCredential() {
        // The regression, in one line.
        assertTrue(settings("local", null).hasReachableModel());
        assertTrue(settings("local", "").hasReachableModel());
        assertTrue(settings("local", "   ").hasReachableModel());
    }

    @Test
    void aLocalBackendWithACredentialIsStillReachable() {
        // Some local gateways do take a key. Supplying one must not flip the answer.
        assertTrue(settings("local", "sk-local-gateway").hasReachableModel());
    }

    @Test
    void aCredentialedRemoteIsReachable() {
        assertTrue(settings("remote", "sk-test-not-a-real-key").hasReachableModel());
    }

    @Test
    void anUncredentialedRemoteIsNot() {
        // The one case the old check got right, and the reason it survived.
        assertFalse(settings("remote", null).hasReachableModel());
        assertFalse(settings("remote", "").hasReachableModel());
    }

    @Test
    void fakeModeIsNeverReachableEvenWithACredential() {
        // Fake mode exists precisely to avoid reaching a model. A key left over
        // in the environment must not drag a fake-mode run onto the wire.
        assertFalse(settings("fake", "sk-test-not-a-real-key").hasReachableModel());
        assertFalse(settings("fake", null).hasReachableModel());
    }
}
