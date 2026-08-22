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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (registre#5): {@code expose()}, {@code snapshot()} and
 * {@code hibernate()} were advertised on {@link Sandbox} but no shipped
 * backend overrode the throwing defaults. Both in-tree backends now
 * implement them; these tests pin the parts that do not need a live
 * Docker daemon.
 */
class SandboxCapabilitiesTest {

    // ── In-process backend ──

    @Test
    void inProcessSnapshotCopiesTheWorkdir() throws Exception {
        var provider = new InProcessSandboxProvider();
        Path snapshotDir = null;
        try (var sandbox = provider.create("jvm", SandboxLimits.DEFAULT, Map.of())) {
            sandbox.writeFile(Path.of("notes/plan.txt"), "step one");

            var snapshot = sandbox.snapshot();
            snapshotDir = Path.of(snapshot.reference());

            assertTrue(Files.isDirectory(snapshotDir), "reference must be a directory path");
            assertEquals("step one",
                    Files.readString(snapshotDir.resolve("notes/plan.txt")));

            // The snapshot is an independent copy — later writes must not leak in.
            sandbox.writeFile(Path.of("notes/plan.txt"), "step two");
            assertEquals("step one",
                    Files.readString(snapshotDir.resolve("notes/plan.txt")));
        } finally {
            if (snapshotDir != null) {
                try (var walk = Files.walk(snapshotDir)) {
                    for (var p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        }
    }

    @Test
    void inProcessHibernateIsSatisfiedAndExecStillWorks() {
        var provider = new InProcessSandboxProvider();
        try (var sandbox = provider.create("jvm", SandboxLimits.DEFAULT, Map.of())) {
            sandbox.hibernate();
            var exec = sandbox.exec(List.of("echo", "awake"), Duration.ofSeconds(10));
            assertEquals(0, exec.exitCode());
            assertTrue(exec.stdout().contains("awake"));
        }
    }

    @Test
    void inProcessExposeReturnsThePortUnchangedAndValidatesRange() {
        var provider = new InProcessSandboxProvider();
        try (var sandbox = provider.create("jvm", SandboxLimits.DEFAULT, Map.of())) {
            assertEquals(8080, sandbox.expose(8080),
                    "no isolation: processes bind host ports directly");
            assertThrows(IllegalArgumentException.class, () -> sandbox.expose(0));
            assertThrows(IllegalArgumentException.class, () -> sandbox.expose(70000));
        }
    }

    @Test
    void inProcessCapabilitiesRejectUseAfterClose() {
        var provider = new InProcessSandboxProvider();
        var sandbox = provider.create("jvm", SandboxLimits.DEFAULT, Map.of());
        sandbox.close();
        assertThrows(IllegalStateException.class, sandbox::snapshot);
        assertThrows(IllegalStateException.class, sandbox::hibernate);
        assertThrows(IllegalStateException.class, () -> sandbox.expose(8080));
    }

    // ── Docker backend (daemon-free parts) ──

    @Test
    void dockerExposeFailsClosedUnderNetworkPolicyNone() {
        var limits = SandboxLimits.DEFAULT; // network policy NONE
        var sandbox = new DockerSandboxProvider.DockerSandbox("atmo-test", limits, Map.of());

        // Rejected before any docker invocation: a port on a no-network
        // container is a contradiction (Correctness Invariant #6).
        var e = assertThrows(IllegalStateException.class, () -> sandbox.expose(8080));
        assertTrue(e.getMessage().contains("NONE"), e.getMessage());
    }

    @Test
    void dockerExposeValidatesPortRangeFirst() {
        var sandbox = new DockerSandboxProvider.DockerSandbox(
                "atmo-test", SandboxLimits.DEFAULT, Map.of());
        assertThrows(IllegalArgumentException.class, () -> sandbox.expose(-1));
        assertThrows(IllegalArgumentException.class, () -> sandbox.expose(65536));
    }

    @Test
    void dockerCreateArgsPublishEphemeralPortOnlyWhenRequested() {
        var full = new SandboxLimits(1.0, 512L * 1024 * 1024,
                Duration.ofMinutes(5), NetworkPolicy.FULL);

        var withPort = DockerSandboxProvider.createArgs("s1", "ubuntu:24.04", full, 8080);
        var portIdx = withPort.indexOf("-p");
        assertTrue(portIdx >= 0, "expose recreation must publish the port: " + withPort);
        assertEquals("0:8080", withPort.get(portIdx + 1),
                "an ephemeral host port must be requested, never a fixed one");

        var without = DockerSandboxProvider.createArgs("s1", "ubuntu:24.04", full, null);
        assertFalse(without.contains("-p"), "plain create must not publish ports");
    }

    @Test
    void dockerCreateArgsKeepNetworkNoneIsolation() {
        var args = DockerSandboxProvider.createArgs(
                "s1", "ubuntu:24.04", SandboxLimits.DEFAULT, null);
        assertTrue(args.contains("--network=none"));
    }
}
