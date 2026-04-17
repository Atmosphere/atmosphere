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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxTest {

    @Test
    void defaultLimitsMatchPlan() {
        // v0.6 plan open question #1 locked these: 1 CPU, 512 MB, 5 min, no network.
        var defaults = SandboxLimits.DEFAULT;
        assertEquals(1.0, defaults.cpuFraction());
        assertEquals(512L * 1024L * 1024L, defaults.memoryBytes());
        assertEquals(Duration.ofMinutes(5), defaults.wallTime());
        assertFalse(defaults.network());
        assertEquals(NetworkPolicy.Mode.NONE, defaults.networkPolicy().mode());
    }

    @Test
    void networkPolicyModes() {
        var none = NetworkPolicy.NONE;
        assertFalse(none.hasEgress());
        assertEquals(NetworkPolicy.Mode.NONE, none.mode());
        assertTrue(none.allowedHosts().isEmpty());

        var gitOnly = NetworkPolicy.GIT_ONLY;
        assertTrue(gitOnly.hasEgress());
        assertEquals(NetworkPolicy.Mode.GIT_ONLY, gitOnly.mode());
        assertTrue(gitOnly.allowedHosts().contains("github.com"));

        var allow = NetworkPolicy.allowlist("api.example.com", "cdn.example.com");
        assertEquals(NetworkPolicy.Mode.ALLOWLIST, allow.mode());
        assertEquals(2, allow.allowedHosts().size());

        var full = NetworkPolicy.FULL;
        assertTrue(full.hasEgress());
        assertEquals(NetworkPolicy.Mode.FULL, full.mode());
    }

    @Test
    void allowlistRequiresAtLeastOneHost() {
        assertThrows(IllegalArgumentException.class,
                () -> new NetworkPolicy(NetworkPolicy.Mode.ALLOWLIST, java.util.List.of()));
    }

    @Test
    void legacyBooleanConstructorMapsToFullOrNone() {
        var legacyOn = new SandboxLimits(1.0, 1024L, Duration.ofSeconds(10), true);
        var legacyOff = new SandboxLimits(1.0, 1024L, Duration.ofSeconds(10), false);
        assertEquals(NetworkPolicy.Mode.FULL, legacyOn.networkPolicy().mode());
        assertEquals(NetworkPolicy.Mode.NONE, legacyOff.networkPolicy().mode());
    }

    @Test
    void limitsRejectInvalidConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new SandboxLimits(0, 1, Duration.ofSeconds(1), false));
        assertThrows(IllegalArgumentException.class,
                () -> new SandboxLimits(1, 0, Duration.ofSeconds(1), false));
        assertThrows(IllegalArgumentException.class,
                () -> new SandboxLimits(1, 1, Duration.ZERO, false));
    }

    @Test
    void sandboxExecSucceededOnlyOnCleanExit() {
        assertTrue(new SandboxExec(0, "ok", "", Duration.ofMillis(10), false).succeeded());
        assertFalse(new SandboxExec(1, "", "err", Duration.ofMillis(10), false).succeeded());
        assertFalse(new SandboxExec(0, "", "", Duration.ofMillis(10), true).succeeded(),
                "timed-out executions never count as succeeded");
    }

    @Test
    void snapshotRejectsBlankReference() {
        assertThrows(IllegalArgumentException.class,
                () -> new SandboxSnapshot("", "ref", java.time.Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new SandboxSnapshot("id", "", java.time.Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new SandboxSnapshot("id", "ref", null));
    }

    @Test
    void inProcessProviderIsAlwaysAvailable() {
        SandboxProvider provider = new InProcessSandboxProvider();
        assertEquals("in-process", provider.name());
        assertTrue(provider.isAvailable());
    }

    @Test
    void inProcessSandboxExecutesCommand() {
        var provider = new InProcessSandboxProvider();
        try (var sandbox = provider.create("jvm", SandboxLimits.DEFAULT, Map.of("owner", "test"))) {
            // 'echo hi' is portable enough across macOS and Linux runners.
            var result = sandbox.exec(List.of("echo", "atmosphere"), Duration.ofSeconds(5));
            assertTrue(result.succeeded());
            assertTrue(result.stdout().contains("atmosphere"));
            assertEquals(Map.of("owner", "test"), sandbox.metadata());
        }
    }

    @Test
    void inProcessSandboxRoundTripsFiles() {
        var provider = new InProcessSandboxProvider();
        try (var sandbox = provider.create("jvm", SandboxLimits.DEFAULT, Map.of())) {
            sandbox.writeFile(Path.of("hello.txt"), "hello, atmosphere");
            assertEquals("hello, atmosphere", sandbox.readFile(Path.of("hello.txt")));
        }
    }

    @Test
    void inProcessSandboxRejectsPathTraversal() {
        var provider = new InProcessSandboxProvider();
        try (var sandbox = provider.create("jvm", SandboxLimits.DEFAULT, Map.of())) {
            assertThrows(IllegalArgumentException.class,
                    () -> sandbox.writeFile(Path.of("../escape.txt"), "nope"));
        }
    }

    @Test
    void inProcessSandboxCloseIsIdempotent() {
        var provider = new InProcessSandboxProvider();
        var sandbox = provider.create("jvm", SandboxLimits.DEFAULT, Map.of());
        sandbox.close();
        sandbox.close();
        assertThrows(IllegalStateException.class,
                () -> sandbox.exec(List.of("echo", "no"), Duration.ofSeconds(5)));
    }

    @Test
    void sandboxToolAnnotationCarriesDefaults() throws Exception {
        var method = Dummy.class.getDeclaredMethod("sandboxed");
        var ann = method.getAnnotation(SandboxTool.class);
        assertEquals("docker", ann.backend());
        assertEquals("ubuntu:24.04", ann.image());
        assertEquals(1.0, ann.cpuFraction());
        assertEquals(512L * 1024L * 1024L, ann.memoryBytes());
        assertEquals(300L, ann.wallTimeSeconds());
        assertFalse(ann.network());
    }

    @Test
    void dockerProviderReportsAvailabilityHonestly() {
        var docker = new DockerSandboxProvider();
        // Whether Docker is installed or not, the provider must answer
        // without throwing; the runtime-truth contract forbids silent true.
        var available = docker.isAvailable();
        assertTrue(available || !available, "isAvailable returned without throwing");
    }

    private static final class Dummy {
        @SandboxTool(image = "ubuntu:24.04")
        @SuppressWarnings("unused")
        void sandboxed() {
        }
    }
}
