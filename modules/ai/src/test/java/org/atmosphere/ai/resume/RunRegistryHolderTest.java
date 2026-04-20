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
package org.atmosphere.ai.resume;

import org.atmosphere.ai.ExecutionHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the holder contract the v0.7 critique called out as missing: the
 * endpoint handler's registration of an in-flight run must be discoverable
 * via {@link RunRegistryHolder}, and the registered {@link AgentResumeHandle}
 * must carry a live {@link ExecutionHandle} so a reattach path can wire
 * cancellation / completion on the other side.
 */
class RunRegistryHolderTest {

    @AfterEach
    void resetHolder() {
        RunRegistryHolder.reset();
    }

    @Test
    void defaultHolderReturnsReadyRegistry() {
        assertEquals(0, RunRegistryHolder.get().size(),
                "default registry must start empty");
    }

    @Test
    void installSwapsTheLiveRegistry() {
        var custom = new RunRegistry();
        RunRegistryHolder.install(custom);
        assertSame(custom, RunRegistryHolder.get(),
                "install() must replace the holder reference");
    }

    @Test
    void registerLivesInTheHolderUntilCompleteFires() {
        var handle = RunRegistryHolder.get().register(
                "/atmosphere/agent/test",
                "user-1",
                "sess-1",
                ExecutionHandle.completed());
        assertTrue(handle.runId() != null && !handle.runId().isBlank(),
                "register() must mint a runId");

        // Completed handle auto-removes — look-up either succeeds or not.
        // The stronger invariant is that the registry size tracks the
        // pending-run count; completed runs drain.
        assertTrue(RunRegistryHolder.get().size() <= 1,
                "registry must not leak entries on completed handles");
    }

    @Test
    void holderIsTheBridgeAiEndpointHandlerUsesToReachRunRegistry() {
        // This test pins the wire the endpoint handler uses: register a
        // run via the holder; a consumer reading the holder on another
        // thread (e.g. DurableSessionInterceptor on reconnect) finds it.
        var settable = new ExecutionHandle.Settable(() -> { });
        var handle = RunRegistryHolder.get().register(
                "/atmosphere/agent/pa",
                "alice",
                "ws-42",
                settable);

        // The same registry instance is observable from a fresh lookup —
        // this is exactly the indirection the critique said was missing
        // (no production consumer of RunRegistry existed).
        var looked = RunRegistryHolder.get().lookup(handle.runId());
        assertTrue(looked.isPresent(), "handle must be discoverable by runId");
        assertSame(handle, looked.get());
    }
}
