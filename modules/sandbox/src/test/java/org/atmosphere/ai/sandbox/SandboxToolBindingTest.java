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
package org.atmosphere.ai.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit contract of {@link SandboxToolBinding}: annotation-to-limits mapping,
 * provider resolution strictly by {@link SandboxTool#backend()} name with an
 * availability check, descriptive fail-fast errors (no fallback of any kind),
 * and idempotent scope close.
 */
public class SandboxToolBindingTest {

    private final SandboxToolBinding binding = new SandboxToolBinding();

    @BeforeEach
    public void reset() {
        RecordingSandboxProvider.reset();
    }

    @Test
    public void appliesOnlyToAnnotatedMethods() throws Exception {
        assertTrue(binding.appliesTo(fixture("networkedTool")));
        assertFalse(binding.appliesTo(fixture("plainMethod")));
    }

    @Test
    public void limitsMapAnnotationMembersWithNetworkTrueAsFull() throws Exception {
        var limits = SandboxToolBinding.limitsFrom(
                fixture("networkedTool").getAnnotation(SandboxTool.class));

        assertEquals(2.0, limits.cpuFraction());
        assertEquals(1024L * 1024L, limits.memoryBytes());
        assertEquals(Duration.ofSeconds(42), limits.wallTime());
        // network = true maps to FULL — never the unenforced GIT_ONLY /
        // ALLOWLIST tiers.
        assertEquals(NetworkPolicy.Mode.FULL, limits.networkPolicy().mode());
    }

    @Test
    public void limitsDefaultToOneCpuHalfGigFiveMinutesNoNetwork() throws Exception {
        var limits = SandboxToolBinding.limitsFrom(
                fixture("defaultLimitsTool").getAnnotation(SandboxTool.class));

        assertEquals(1.0, limits.cpuFraction());
        assertEquals(512L * 1024L * 1024L, limits.memoryBytes());
        assertEquals(Duration.ofSeconds(300), limits.wallTime());
        // Security default-deny: network absent means NONE.
        assertEquals(NetworkPolicy.Mode.NONE, limits.networkPolicy().mode());
    }

    @Test
    public void openResolvesProviderByNameAndCreatesFromAnnotation() throws Exception {
        var scope = binding.open(fixture("networkedTool"));
        try {
            assertEquals(1, RecordingSandboxProvider.CREATE_CALLS.get());
            assertEquals("img:1", RecordingSandboxProvider.lastImage);
            assertEquals(NetworkPolicy.Mode.FULL,
                    RecordingSandboxProvider.lastLimits.networkPolicy().mode());
            assertTrue(RecordingSandboxProvider.lastMetadata.get("tool.method")
                            .contains(Fixtures.class.getName() + "#networkedTool"),
                    "metadata must carry the tool method reference");
            assertEquals(Sandbox.class, scope.injectableType());
            assertSame(RecordingSandboxProvider.lastSandbox, scope.injectable());
        } finally {
            scope.close();
        }
    }

    @Test
    public void unknownBackendFailsFastNamingItAndTheRegisteredProviders() throws Exception {
        var thrown = assertThrows(IllegalStateException.class,
                () -> binding.open(fixture("missingBackendTool")));

        assertTrue(thrown.getMessage().contains("'missing-backend'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("recording"),
                "must list the actually-registered providers: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("never fall back to in-JVM execution"),
                thrown.getMessage());
        assertEquals(0, RecordingSandboxProvider.CREATE_CALLS.get(),
                "no other provider may be substituted");
    }

    @Test
    public void unavailableBackendFailsFastEvenWhenAnotherProviderIsAvailable() throws Exception {
        // "recording" is available; "down" is not. Naming "down" must fail —
        // never silently route to the available provider.
        var thrown = assertThrows(IllegalStateException.class,
                () -> binding.open(fixture("downBackendTool")));

        assertTrue(thrown.getMessage().contains("'down'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("not available"), thrown.getMessage());
        assertEquals(0, RecordingSandboxProvider.CREATE_CALLS.get(),
                "the available provider must not be used as a fallback");
    }

    @Test
    public void unavailableInProcessErrorNamesTheOptIn() throws Exception {
        // The in-process provider ships in this module and is unavailable
        // without the insecure opt-in — the error must say how to enable it.
        // Save/restore the opt-in property so this test cannot leak state.
        var saved = System.getProperty(InProcessSandboxProvider.INSECURE_OPT_IN);
        System.clearProperty(InProcessSandboxProvider.INSECURE_OPT_IN);
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(
                    new InProcessSandboxProvider().isAvailable(),
                    "ATMOSPHERE_SANDBOX_INSECURE forces availability in this environment");

            var thrown = assertThrows(IllegalStateException.class,
                    () -> binding.open(fixture("inProcessBackendTool")));
            assertTrue(thrown.getMessage().contains(InProcessSandboxProvider.INSECURE_OPT_IN),
                    thrown.getMessage());
        } finally {
            if (saved != null) {
                System.setProperty(InProcessSandboxProvider.INSECURE_OPT_IN, saved);
            }
        }
    }

    @Test
    public void scopeCloseIsIdempotent() throws Exception {
        var scope = binding.open(fixture("networkedTool"));
        scope.close();
        scope.close();

        assertEquals(1, RecordingSandboxProvider.lastSandbox.closeCalls(),
                "double-close of the scope must close the sandbox exactly once");
    }

    private static Method fixture(String name) throws NoSuchMethodException {
        for (var method : Fixtures.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new NoSuchMethodException(name);
    }

    static class Fixtures {

        @SandboxTool(backend = "recording", image = "img:1",
                cpuFraction = 2.0, memoryBytes = 1024L * 1024L,
                wallTimeSeconds = 42, network = true)
        void networkedTool(Sandbox sandbox) {
        }

        @SandboxTool(backend = "recording", image = "img:2")
        void defaultLimitsTool(Sandbox sandbox) {
        }

        @SandboxTool(backend = "missing-backend", image = "img")
        void missingBackendTool(Sandbox sandbox) {
        }

        @SandboxTool(backend = "down", image = "img")
        void downBackendTool(Sandbox sandbox) {
        }

        @SandboxTool(backend = "in-process", image = "jvm")
        void inProcessBackendTool(Sandbox sandbox) {
        }

        void plainMethod() {
        }
    }
}
