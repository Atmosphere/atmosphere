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
package org.atmosphere.ai.code;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runtime-truth tests for {@link ContainerCodeSandboxFactory#isAvailable()}
 * (Correctness Invariant #5).
 *
 * <p>The engine probe runs {@code <engine> info --format {{.ServerVersion}}} as a
 * subprocess, so these tests substitute a stub executable for {@code <engine>} and
 * drive each real-world outcome deterministically — no container engine needed on
 * the host. Every stub records its argument vector to a marker file, so the tests
 * can assert not just the verdict but <em>whether the engine was invoked at all</em>
 * and <em>with which arguments</em>.</p>
 *
 * <p>The load-bearing case is {@link #daemonDownIsNotAvailableEvenThoughCliExitsZero()}.
 * A real {@code docker info} <em>exits 0 even when the daemon is unreachable</em>: the
 * CLI still renders client-side information and reports "Cannot connect to the Docker
 * daemon" on stderr. An exit-code-only probe therefore reported the engine as available
 * whenever the CLI was merely installed, which advertised the {@code code_exec} tool on
 * hosts where it could not possibly run.</p>
 */
class ContainerEngineProbeTest {

    /** Server version emitted by the stub standing in for a reachable daemon. */
    private static final String SERVER_VERSION = "28.1.1";

    @BeforeAll
    static void requirePosixShell() {
        assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"),
                "engine stubs are POSIX shell scripts");
    }

    // --- runtime truth ---------------------------------------------------------

    @Test
    void daemonDownIsNotAvailableEvenThoughCliExitsZero(@TempDir Path tmp) throws IOException {
        // Exactly what `docker info --format {{.ServerVersion}}` does with the daemon
        // stopped: diagnostic on stderr, EMPTY stdout, and exit status 0.
        var engine = stub(tmp, """
                echo "Cannot connect to the Docker daemon at unix:///var/run/docker.sock." >&2
                exit 0
                """);

        assertFalse(factoryFor(engine).isAvailable(),
                "an unreachable daemon must not be advertised as available, even though "
                        + "the CLI exits 0 — exit status alone cannot distinguish a running "
                        + "daemon from a merely installed client");
    }

    @Test
    void reachableDaemonIsAvailable(@TempDir Path tmp) throws IOException {
        var engine = stub(tmp, "echo \"" + SERVER_VERSION + "\"\nexit 0\n");

        assertTrue(factoryFor(engine).isAvailable(),
                "a daemon that reports a server version must be advertised as available");
    }

    @Test
    void blankServerVersionIsNotAvailable(@TempDir Path tmp) throws IOException {
        // Whitespace-only stdout is still "no server version" — guards against a probe
        // that checks for output rather than for a meaningful value.
        var engine = stub(tmp, "echo \"   \"\nexit 0\n");

        assertFalse(factoryFor(engine).isAvailable(),
                "whitespace-only server version must count as unreachable");
    }

    @Test
    void nonZeroExitIsNotAvailable(@TempDir Path tmp) throws IOException {
        var engine = stub(tmp, "echo \"" + SERVER_VERSION + "\"\nexit 1\n");

        assertFalse(factoryFor(engine).isAvailable(),
                "a failing probe must not be advertised as available regardless of stdout");
    }

    @Test
    void absentEngineBinaryIsNotAvailable(@TempDir Path tmp) {
        assertFalse(factoryFor(tmp.resolve("no-such-engine")).isAvailable(),
                "an engine binary that is not invocable must not be advertised as available");
    }

    // --- the probe command itself ----------------------------------------------

    @Test
    void probeAsksTheEngineForItsServerVersion(@TempDir Path tmp) throws IOException {
        // Pins the command, not just the decision rule. The "empty stdout means
        // unreachable" contract only holds for a template that renders empty when the
        // daemon is down; switching it to something that always prints (e.g. `{{json .}}`)
        // would keep every other test green while silently restoring the original bug.
        var engine = stub(tmp, "echo \"" + SERVER_VERSION + "\"\nexit 0\n");

        assertTrue(factoryFor(engine).isAvailable());

        assertEquals(List.of("info --format {{.ServerVersion}}"), invocations(tmp),
                "the probe must ask the engine for its server version");
    }

    @Test
    void oversizedEngineOutputIsBoundedAndDoesNotHang(@TempDir Path tmp) throws IOException {
        // A misbehaving engine that floods stdout must neither exhaust memory nor stall
        // the probe. Historically stdout was a pipe read only after waitFor(), so output
        // larger than the pipe buffer blocked the child and timed the probe out.
        var engine = stub(tmp, """
                i=0
                while [ $i -lt 4000 ]; do
                  printf '%s\\n' "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                  i=$((i+1))
                done
                exit 0
                """);

        assertTrue(factoryFor(engine).isAvailable(),
                "an engine that returns a large but non-empty response is still reachable");
    }

    // --- probe caching (Invariant #3: no unbounded work on a per-request path) --

    @Test
    void negativeProbeResultIsCached(@TempDir Path tmp) throws IOException {
        // isAvailable() runs per inbound message. Before this was fixed the TTL guard
        // keyed on the resolved engine name, which is null after any FAILED probe — so
        // a daemon-down host re-forked the engine CLI on every single message, serialized
        // on a JVM-wide lock, burning the probe timeout each time.
        var engine = stub(tmp, "exit 0\n");
        var factory = factoryFor(engine);

        assertFalse(factory.isAvailable());
        assertFalse(factory.isAvailable());
        assertFalse(factory.isAvailable());

        assertEquals(1, invocations(tmp).size(),
                "a negative probe result must be cached for the probe TTL, not re-run per call");
    }

    @Test
    void positiveProbeResultIsCached(@TempDir Path tmp) throws IOException {
        var engine = stub(tmp, "echo \"" + SERVER_VERSION + "\"\nexit 0\n");
        var factory = factoryFor(engine);

        assertTrue(factory.isAvailable());
        assertTrue(factory.isAvailable());

        assertEquals(1, invocations(tmp).size(),
                "a positive probe result must be cached for the probe TTL");
    }

    // --- default deny ----------------------------------------------------------

    @Test
    void disabledConfigIsNotAvailableWithoutProbing(@TempDir Path tmp) throws IOException {
        // Default deny (Invariant #6): the master switch wins, and must short-circuit
        // BEFORE the engine is invoked — asserting only the boolean would let a refactor
        // that reordered the guards fork the CLI on every message of a deployment that
        // has code execution switched off.
        var engine = stub(tmp, "echo \"" + SERVER_VERSION + "\"\nexit 0\n");

        var disabled = config(false, engine.toString(), "example/playwright:pinned");
        assertFalse(new ContainerCodeSandboxFactory(disabled).isAvailable(),
                "code execution disabled must stay unavailable");
        assertTrue(invocations(tmp).isEmpty(), "a disabled config must never invoke the engine");
    }

    @Test
    void missingImageIsNotAvailableWithoutProbing(@TempDir Path tmp) throws IOException {
        var engine = stub(tmp, "echo \"" + SERVER_VERSION + "\"\nexit 0\n");

        var noImage = config(true, engine.toString(), "");
        assertFalse(new ContainerCodeSandboxFactory(noImage).isAvailable(),
                "no configured image must stay unavailable");
        assertTrue(invocations(tmp).isEmpty(), "a config with no image must never invoke the engine");
    }

    // --- helpers ---------------------------------------------------------------

    private static ContainerCodeSandboxFactory factoryFor(Path engine) {
        return new ContainerCodeSandboxFactory(
                config(true, engine.toString(), "example/playwright:pinned"));
    }

    private static CodeSandboxConfig config(boolean enabled, String engine, String image) {
        return new CodeSandboxConfig(
                enabled, engine, image, "none", "512m",
                1.0d, 256, Duration.ofSeconds(60), Duration.ofSeconds(300), 256 * 1024, "");
    }

    /** Path the stubs append their argument vector to, one line per invocation. */
    private static Path marker(Path dir) {
        return dir.resolve("invocations");
    }

    /** Argument vectors the stub was invoked with, in order; empty when never run. */
    private static List<String> invocations(Path dir) throws IOException {
        Path marker = marker(dir);
        return Files.exists(marker) ? Files.readAllLines(marker) : List.of();
    }

    /**
     * Write an executable stub standing in for the container-engine CLI. The stub
     * records its arguments before running {@code body}, so tests can assert both
     * invocation count and argv.
     */
    private static Path stub(Path dir, String body) throws IOException {
        Path stub = dir.resolve("fake-engine");
        Files.writeString(stub, "#!/bin/sh\nprintf '%s\\n' \"$*\" >> '"
                + marker(dir) + "'\n" + body);
        assumeTrue(stub.toFile().setExecutable(true), "cannot mark stub executable");
        return stub;
    }
}
