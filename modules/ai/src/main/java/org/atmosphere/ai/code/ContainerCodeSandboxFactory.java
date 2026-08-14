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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CodeSandboxFactory} that provisions an ephemeral container per session.
 *
 * <p>{@link #isAvailable()} reflects confirmed runtime state, not configuration
 * intent (Correctness Invariant #5): it returns {@code true} only when execution
 * is enabled, an image is configured, <em>and</em> a container engine responds to
 * an {@code info} probe right now. The probe result is cached briefly so the
 * per-request capability check stays cheap without going stale. When enabled but
 * the engine is absent, a one-time warning is logged so the misconfiguration is
 * visible at startup.</p>
 */
final class ContainerCodeSandboxFactory implements CodeSandboxFactory {

    private static final Logger logger = LoggerFactory.getLogger(ContainerCodeSandboxFactory.class);

    /** Candidate engines tried, in order, when {@code engine=auto}. */
    private static final List<String> AUTO_ENGINES = List.of("docker", "podman");
    private static final long PROBE_TTL_MILLIS = 30_000L;
    private static final long PROBE_TIMEOUT_MILLIS = 5_000L;
    /**
     * Cap on the engine's captured stdout. {@code info --format {{.ServerVersion}}}
     * emits a short version string; anything beyond this is a misconfigured engine,
     * and reading it unbounded would be a memory sink on a per-request path
     * (Correctness Invariant #3).
     */
    private static final int PROBE_OUTPUT_LIMIT_BYTES = 8 * 1024;

    private final CodeSandboxConfig config;

    private final Object probeLock = new Object();
    private long lastProbeAt;
    private boolean lastProbeOk;
    /**
     * Whether a probe has completed at least once. The TTL guard keys on this
     * rather than on {@link #resolvedEngine}, which is null after every failed
     * probe — keying on the engine would make negative results uncacheable and
     * re-fork the engine CLI on every capability check.
     */
    private boolean probed;
    private String resolvedEngine;
    private boolean warnedUnavailable;

    ContainerCodeSandboxFactory(CodeSandboxConfig config) {
        this.config = config;
    }

    @Override
    public boolean isAvailable() {
        if (!config.enabled()) {
            return false;
        }
        if (config.image().isBlank()) {
            warnOnce("Code execution is enabled but no image is configured ("
                    + CodeSandboxConfig.IMAGE + "); the code_exec tool stays disabled.");
            return false;
        }
        return engineAvailable();
    }

    @Override
    public CodeSandbox create(String sessionId) throws SandboxException {
        if (!config.enabled()) {
            throw new SandboxException("Code execution is disabled");
        }
        if (config.image().isBlank()) {
            throw new SandboxException("No sandbox image configured (" + CodeSandboxConfig.IMAGE + ")");
        }
        if (!engineAvailable()) {
            throw new SandboxException("No container engine available for code execution "
                    + "(tried " + (config.engine().equals("auto") ? AUTO_ENGINES : config.engine()) + ")");
        }
        return ContainerCodeSandbox.start(resolvedEngine, sessionId, config);
    }

    // --- runtime engine detection ---------------------------------------------

    private boolean engineAvailable() {
        synchronized (probeLock) {
            long now = System.currentTimeMillis();
            if (probed && now - lastProbeAt < PROBE_TTL_MILLIS) {
                return lastProbeOk;
            }
            lastProbeAt = now;
            probed = true;
            lastProbeOk = false;
            resolvedEngine = null;

            var candidates = config.engine().equals("auto")
                    ? AUTO_ENGINES : List.of(config.engine());
            for (String candidate : candidates) {
                if (probe(candidate)) {
                    resolvedEngine = candidate;
                    lastProbeOk = true;
                    break;
                }
            }
            if (!lastProbeOk) {
                warnOnce("Code execution is enabled but no container engine responded "
                        + "(tried " + candidates + "); the code_exec tool stays disabled.");
            }
            return lastProbeOk;
        }
    }

    private static boolean probe(String engine) {
        Path capture = null;
        Process process = null;
        try {
            // stdout is captured to a file rather than a pipe. A pipe would make the
            // read depend on EOF, which arrives only once *every* holder of the write
            // end closes it — a grandchild that inherited the descriptor (an ssh or
            // colima wrapper standing in for the engine) keeps it open forever, and
            // config.engine() is an arbitrary operator string used verbatim as argv[0].
            // Blocking there would strand the probe with probeLock held and wedge every
            // later capability check. A file also cannot fill and stall the child the
            // way a full pipe buffer would (Correctness Invariants #2 and #3).
            capture = Files.createTempFile("atmosphere-engine-probe", ".out");
            process = new ProcessBuilder(ContainerCommandBuilder.infoArgs(engine))
                    .redirectOutput(ProcessBuilder.Redirect.to(capture.toFile()))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                return false;
            }
            if (process.exitValue() != 0) {
                return false;
            }
            // `<engine> info` exits 0 even when the daemon is unreachable: the CLI
            // still renders client-side info and reports "Cannot connect to the
            // ... daemon" on stderr. The {{.ServerVersion}} template resolves to an
            // empty string in that case, so a non-blank server version is the only
            // signal that the engine is actually reachable. Trusting the exit code
            // advertised code_exec on hosts where the CLI was installed but the
            // daemon was stopped (Correctness Invariant #5, runtime truth).
            String serverVersion = readCapturedVersion(capture);
            if (serverVersion.isEmpty()) {
                logger.trace("Container engine '{}' CLI responded but reported no server "
                        + "version; treating the daemon as unreachable", engine);
                return false;
            }
            return true;
        } catch (IOException e) {
            // Binary not on PATH — a normal "engine absent" outcome, not an error.
            logger.trace("Container engine '{}' not invocable: {}", engine, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            // Every terminal path — success, non-zero exit, timeout, interrupt, I/O
            // failure — leaves no live child and no capture file behind (Invariant #2).
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            deleteQuietly(capture);
        }
    }

    /** Read at most {@link #PROBE_OUTPUT_LIMIT_BYTES} of the captured stdout. */
    private static String readCapturedVersion(Path capture) throws IOException {
        try (InputStream in = Files.newInputStream(capture)) {
            return new String(in.readNBytes(PROBE_OUTPUT_LIMIT_BYTES), StandardCharsets.UTF_8).trim();
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.trace("Could not delete engine-probe capture {}: {}", path, e.getMessage());
        }
    }

    private void warnOnce(String message) {
        synchronized (probeLock) {
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                logger.warn(message);
            }
        }
    }
}
