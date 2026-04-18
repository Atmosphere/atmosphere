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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reference {@link SandboxProvider} that runs commands in a tempdir using a
 * JVM child process ({@link ProcessBuilder}). <b>This is NOT a security
 * boundary</b> — it is a dev-only scaffolding useful for testing the SPI
 * shape without requiring a Docker daemon.
 *
 * <p>Gated by an explicit opt-in: the system property
 * {@value #INSECURE_OPT_IN} must be set to {@code true} (or the equivalent
 * environment variable {@code ATMOSPHERE_SANDBOX_INSECURE}) before
 * {@link #isAvailable()} returns {@code true}. Without the opt-in the
 * provider stays unavailable and {@link SandboxProvider} ServiceLoader
 * resolution falls through to a secure provider (Docker) or returns
 * nothing so the calling sample fails loud. Previously the provider
 * defaulted to {@code isAvailable=true} and the LLM-driven tool loop
 * could exec arbitrary shell on the developer's host when Docker was
 * unreachable — a Correctness Invariant #6 violation.</p>
 *
 * <p>Production deployments must use {@link DockerSandboxProvider} or a
 * dedicated hypervisor-backed backend.</p>
 */
public final class InProcessSandboxProvider implements SandboxProvider {

    /** System property / env var that opts the insecure provider in. */
    public static final String INSECURE_OPT_IN = "atmosphere.sandbox.insecure";

    private static final Logger logger = LoggerFactory.getLogger(InProcessSandboxProvider.class);

    /** One-shot flag so the warning fires once per JVM, not once per check. */
    private static final java.util.concurrent.atomic.AtomicBoolean WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    @Override
    public String name() {
        return "in-process";
    }

    @Override
    public boolean isAvailable() {
        if (!insecureOptInEnabled()) {
            return false;
        }
        if (WARNED.compareAndSet(false, true)) {
            logger.warn("InProcessSandboxProvider enabled via -D{}=true — NOT a security boundary. "
                    + "Commands execute on the host JVM with no isolation; use DockerSandboxProvider in production.",
                    INSECURE_OPT_IN);
        }
        return true;
    }

    private static boolean insecureOptInEnabled() {
        var sys = System.getProperty(INSECURE_OPT_IN);
        if (sys != null) {
            return Boolean.parseBoolean(sys);
        }
        var env = System.getenv("ATMOSPHERE_SANDBOX_INSECURE");
        return env != null && Boolean.parseBoolean(env);
    }

    @Override
    public Sandbox create(String image, SandboxLimits limits, Map<String, String> metadata) {
        Path root;
        try {
            root = Files.createTempDirectory("atmo-in-process-sandbox-");
        } catch (IOException e) {
            throw new IllegalStateException("failed to create sandbox tempdir", e);
        }
        return new InProcessSandbox(
                UUID.randomUUID().toString(),
                root,
                limits,
                metadata == null ? Map.of() : Map.copyOf(metadata));
    }

    static final class InProcessSandbox implements Sandbox {

        private final String id;
        private final Path root;
        private final SandboxLimits limits;
        private final Map<String, String> metadata;
        private final AtomicBoolean closed = new AtomicBoolean();

        InProcessSandbox(String id, Path root, SandboxLimits limits, Map<String, String> metadata) {
            this.id = Objects.requireNonNull(id);
            this.root = Objects.requireNonNull(root);
            this.limits = Objects.requireNonNull(limits);
            this.metadata = metadata;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public SandboxExec exec(List<String> command, Duration timeout) {
            checkOpen();
            var effective = timeout == null ? limits.wallTime() : timeout;
            // Run inside the sandbox tempdir rather than the caller's CWD so
            // unqualified paths in the LLM-proposed command resolve to the
            // sandbox root. Still NOT a security boundary — the process
            // inherits JVM privileges and can escape via absolute paths —
            // but this avoids the foot-gun where relative-path writes land
            // in the developer's working directory.
            var pb = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(false);
            var start = java.time.Instant.now();
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                throw new IllegalStateException("failed to start process: " + command, e);
            }
            var stdout = new StringBuilder();
            var stderr = new StringBuilder();
            boolean timedOut = false;
            try {
                var so = drain(process.getInputStream(), stdout);
                var se = drain(process.getErrorStream(), stderr);
                if (!process.waitFor(effective.toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    timedOut = true;
                }
                so.join();
                se.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IllegalStateException("interrupted waiting for " + command, e);
            }
            var exit = timedOut ? -1 : process.exitValue();
            var elapsed = java.time.Duration.between(start, java.time.Instant.now());
            return new SandboxExec(exit, stdout.toString(), stderr.toString(),
                    elapsed, timedOut);
        }

        private static Thread drain(java.io.InputStream stream, StringBuilder sink) {
            var t = Thread.ofVirtual().unstarted(() -> {
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    var buffer = new char[4096];
                    int read;
                    while ((read = reader.read(buffer)) != -1) {
                        synchronized (sink) {
                            sink.append(buffer, 0, read);
                        }
                    }
                } catch (IOException ignored) {
                    // stream closed when the process exited — nothing more to read
                }
            });
            t.start();
            return t;
        }

        @Override
        public void writeFile(Path pathInsideSandbox, String content) {
            checkOpen();
            var target = resolve(pathInsideSandbox);
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("writeFile failed: " + e.getMessage(), e);
            }
        }

        @Override
        public String readFile(Path pathInsideSandbox) {
            checkOpen();
            var target = resolve(pathInsideSandbox);
            try {
                return Files.readString(target, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("readFile failed: " + e.getMessage(), e);
            }
        }

        @Override
        public SandboxLimits limits() {
            return limits;
        }

        @Override
        public Map<String, String> metadata() {
            return metadata;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try (var walk = Files.walk(root)) {
                var paths = new ArrayList<Path>();
                walk.forEach(paths::add);
                // delete deepest-first
                for (var i = paths.size() - 1; i >= 0; i--) {
                    Files.deleteIfExists(paths.get(i));
                }
            } catch (IOException e) {
                logger.trace("failed to tear down sandbox {} at {}: {}",
                        id, root, e.getMessage(), e);
            }
        }

        private Path resolve(Path pathInsideSandbox) {
            var resolved = root.resolve(pathInsideSandbox.toString()).normalize();
            if (!resolved.startsWith(root)) {
                throw new IllegalArgumentException(
                        "path escapes sandbox root: " + pathInsideSandbox);
            }
            return resolved;
        }

        private void checkOpen() {
            if (closed.get()) {
                throw new IllegalStateException("sandbox " + id + " is already closed");
            }
        }
    }
}
