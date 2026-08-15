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
import java.util.concurrent.TimeUnit;
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
 * the host. The stub records its argument vector, so the tests can assert not just
 * the verdict but <em>whether the engine was invoked at all</em> and <em>with which
 * arguments</em>.</p>
 *
 * <p>The load-bearing case is {@link #daemonDownIsNotAvailableEvenThoughCliExitsZero()}.
 * A real {@code docker info} <em>exits 0 even when the daemon is unreachable</em>: the
 * CLI still renders client-side information and reports "Cannot connect to the Docker
 * daemon" on stderr. An exit-code-only probe therefore reported the engine as available
 * whenever the CLI was merely installed, which advertised the {@code code_exec} tool on
 * hosts where it could not possibly run.</p>
 *
 * <p><b>One stub, written once.</b> The executable is created and exec'd a single time
 * for the whole class; per-test behaviour comes from a data file it sources. macOS runs
 * a security check the first time a freshly written executable is exec'd, and a binary
 * per test paid that cost on every case — under concurrent build load one stall
 * exceeded a minute, far past {@code PROBE_TIMEOUT_MILLIS}, so a healthy stub read as
 * an unreachable engine and the suite failed non-deterministically here while passing
 * on Linux CI. Reusing one warm binary keeps that stall out of the measurement instead
 * of widening a production timeout to accommodate a test artifact.</p>
 */
class ContainerEngineProbeTest {

    /** Server version emitted by the stub standing in for a reachable daemon. */
    private static final String SERVER_VERSION = "28.1.1";

    /** Data file the shared stub sources to pick up the current test's behaviour. */
    private static final String MODE_FILE = "mode.sh";

    @TempDir
    static Path shared;

    private static Path sharedStub;

    @BeforeAll
    static void createSharedStub() throws IOException {
        assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"),
                "engine stubs are POSIX shell scripts");

        sharedStub = shared.resolve("fake-engine");
        Files.writeString(sharedStub,
                "#!/bin/sh\n"
                        + "dir=$(dirname \"$0\")\n"
                        + "printf '%s\\n' \"$*\" >> \"$dir/invocations\"\n"
                        + "[ -f \"$dir/" + MODE_FILE + "\" ] && . \"$dir/" + MODE_FILE + "\"\n"
                        + "exit 0\n");
        assumeTrue(sharedStub.toFile().setExecutable(true), "cannot mark stub executable");

        // Pay the first-exec security check here, off the probe path.
        try {
            new ProcessBuilder(sharedStub.toString(), "--warmup")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Files.deleteIfExists(marker());
    }

    // --- runtime truth ---------------------------------------------------------

    @Test
    void daemonDownIsNotAvailableEvenThoughCliExitsZero() throws IOException {
        // Exactly what `docker info --format {{.ServerVersion}}` does with the daemon
        // stopped: diagnostic on stderr, EMPTY stdout, and exit status 0.
        var engine = stub("""
                echo "Cannot connect to the Docker daemon at unix:///var/run/docker.sock." >&2
                """);

        assertFalse(factoryFor(engine).isAvailable(),
                "an unreachable daemon must not be advertised as available, even though "
                        + "the CLI exits 0 — exit status alone cannot distinguish a running "
                        + "daemon from a merely installed client");
    }

    @Test
    void reachableDaemonIsAvailable() throws IOException {
        var engine = stub("echo \"" + SERVER_VERSION + "\"\n");

        assertTrue(factoryFor(engine).isAvailable(),
                "a daemon that reports a server version must be advertised as available");
    }

    @Test
    void blankServerVersionIsNotAvailable() throws IOException {
        // Whitespace-only stdout is still "no server version" — guards against a probe
        // that checks for output rather than for a meaningful value.
        var engine = stub("echo \"   \"\n");

        assertFalse(factoryFor(engine).isAvailable(),
                "whitespace-only server version must count as unreachable");
    }

    @Test
    void nonZeroExitIsNotAvailable() throws IOException {
        var engine = stub("echo \"" + SERVER_VERSION + "\"\nexit 1\n");

        assertFalse(factoryFor(engine).isAvailable(),
                "a failing probe must not be advertised as available regardless of stdout");
    }

    @Test
    void absentEngineBinaryIsNotAvailable() {
        assertFalse(factoryFor(shared.resolve("no-such-engine")).isAvailable(),
                "an engine binary that is not invocable must not be advertised as available");
    }

    // --- the probe command itself ----------------------------------------------

    @Test
    void probeAsksTheEngineForItsServerVersion() throws IOException {
        // Pins the command, not just the decision rule. The "empty stdout means
        // unreachable" contract only holds for a template that renders empty when the
        // daemon is down; switching it to something that always prints (e.g. `{{json .}}`)
        // would keep every other test green while silently restoring the original bug.
        var engine = stub("echo \"" + SERVER_VERSION + "\"\n");

        assertTrue(factoryFor(engine).isAvailable());

        assertEquals(List.of("info --format {{.ServerVersion}}"), invocations(),
                "the probe must ask the engine for its server version");
    }

    @Test
    void oversizedEngineOutputIsBoundedAndDoesNotHang() throws IOException {
        // A misbehaving engine that floods stdout must neither exhaust memory nor stall
        // the probe. Historically stdout was a pipe read only after waitFor(), so output
        // larger than the pipe buffer (64 KiB) blocked the child and timed the probe out.
        var engine = stub("""
                i=0
                while [ $i -lt 4000 ]; do
                  printf '%s\\n' "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                  i=$((i+1))
                done
                """);

        assertTrue(factoryFor(engine).isAvailable(),
                "an engine that returns a large but non-empty response is still reachable");
    }

    // --- probe caching (Invariant #3: no unbounded work on a per-request path) --

    @Test
    void negativeProbeResultIsCached() throws IOException {
        // isAvailable() runs per inbound message. Before this was fixed the TTL guard
        // keyed on the resolved engine name, which is null after any FAILED probe — so
        // a daemon-down host re-forked the engine CLI on every single message, serialized
        // on a JVM-wide lock, burning the probe timeout each time.
        var engine = stub("");
        var factory = factoryFor(engine);

        assertFalse(factory.isAvailable());
        assertFalse(factory.isAvailable());
        assertFalse(factory.isAvailable());

        assertEquals(1, invocations().size(),
                "a negative probe result must be cached for the probe TTL, not re-run per call");
    }

    @Test
    void positiveProbeResultIsCached() throws IOException {
        var engine = stub("echo \"" + SERVER_VERSION + "\"\n");
        var factory = factoryFor(engine);

        assertTrue(factory.isAvailable());
        assertTrue(factory.isAvailable());

        assertEquals(1, invocations().size(),
                "a positive probe result must be cached for the probe TTL");
    }

    // --- default deny ----------------------------------------------------------

    @Test
    void disabledConfigIsNotAvailableWithoutProbing() throws IOException {
        // Default deny (Invariant #6): the master switch wins, and must short-circuit
        // BEFORE the engine is invoked — asserting only the boolean would let a refactor
        // that reordered the guards fork the CLI on every message of a deployment that
        // has code execution switched off.
        var engine = stub("echo \"" + SERVER_VERSION + "\"\n");

        var disabled = config(false, engine.toString(), "example/playwright:pinned");
        assertFalse(new ContainerCodeSandboxFactory(disabled).isAvailable(),
                "code execution disabled must stay unavailable");
        assertTrue(invocations().isEmpty(), "a disabled config must never invoke the engine");
    }

    @Test
    void missingImageIsNotAvailableWithoutProbing() throws IOException {
        var engine = stub("echo \"" + SERVER_VERSION + "\"\n");

        var noImage = config(true, engine.toString(), "");
        assertFalse(new ContainerCodeSandboxFactory(noImage).isAvailable(),
                "no configured image must stay unavailable");
        assertTrue(invocations().isEmpty(), "a config with no image must never invoke the engine");
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

    /** File the shared stub appends its argument vector to, one line per invocation. */
    private static Path marker() {
        return shared.resolve("invocations");
    }

    /** Argument vectors the stub was invoked with, in order; empty when never run. */
    private static List<String> invocations() throws IOException {
        return Files.exists(marker()) ? Files.readAllLines(marker()) : List.of();
    }

    /**
     * Point the shared stub at {@code body} for this test and reset its invocation
     * record. Only this data file changes between tests — the executable itself is
     * written and warmed once in {@link #createSharedStub()}.
     */
    private static Path stub(String body) throws IOException {
        Files.writeString(shared.resolve(MODE_FILE), body);
        Files.deleteIfExists(marker());
        return sharedStub;
    }
}
