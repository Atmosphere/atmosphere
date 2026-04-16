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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Docker-CLI-based sandbox provider. Shells out to the {@code docker}
 * command — no direct dependency on a Docker SDK, which keeps the module
 * dependency-free. Containers are created with {@code docker create} and
 * reused across {@link Sandbox#exec} calls via {@code docker exec}.
 *
 * <p>Resource limits translate to Docker CLI flags: {@code --cpus},
 * {@code --memory}, and per-exec timeouts via
 * {@link ProcessBuilder}.</p>
 *
 * <h2>Boundary safety</h2>
 *
 * Command arrays pass directly to {@link ProcessBuilder} — never
 * concatenated into a shell string, so argument expansion cannot inject
 * additional commands (Correctness Invariant #4).
 */
public final class DockerSandboxProvider implements SandboxProvider {

    private static final Logger logger = LoggerFactory.getLogger(DockerSandboxProvider.class);
    private static final Duration AVAILABILITY_PROBE_TIMEOUT = Duration.ofSeconds(3);

    @Override
    public String name() {
        return "docker";
    }

    @Override
    public boolean isAvailable() {
        try {
            var result = runProcess(List.of("docker", "version", "--format", "{{.Server.Version}}"),
                    AVAILABILITY_PROBE_TIMEOUT);
            return result.exitCode() == 0;
        } catch (RuntimeException e) {
            logger.trace("docker availability probe failed: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public Sandbox create(String image, SandboxLimits limits, Map<String, String> metadata) {
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("image must not be blank");
        }
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "Docker is not available: ensure `docker` is on PATH and the daemon is running");
        }
        var id = "atmo-sandbox-" + UUID.randomUUID();
        var args = new ArrayList<String>();
        args.add("docker");
        args.add("create");
        args.add("--name");
        args.add(id);
        args.add("--cpus=" + limits.cpuFraction());
        args.add("--memory=" + limits.memoryBytes() + "b");
        if (!limits.network()) {
            args.add("--network=none");
        }
        // Keep container alive for exec calls; the entrypoint idles.
        args.add("--entrypoint");
        args.add("sleep");
        args.add(image);
        args.add(String.valueOf(limits.wallTime().toSeconds() + 60));
        var create = runProcess(args, Duration.ofSeconds(30));
        if (create.exitCode() != 0) {
            throw new IllegalStateException("docker create failed: " + create.stderr());
        }
        var start = runProcess(List.of("docker", "start", id), Duration.ofSeconds(15));
        if (start.exitCode() != 0) {
            throw new IllegalStateException("docker start failed: " + start.stderr());
        }
        return new DockerSandbox(id, limits, metadata == null ? Map.of() : Map.copyOf(metadata));
    }

    /** Package-private — exposed for tests. */
    static ProcessResult runProcess(List<String> command, Duration timeout) {
        var pb = new ProcessBuilder(command).redirectErrorStream(false);
        var start = Instant.now();
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("failed to start process: " + command, e);
        }
        var stdoutBuf = new StringBuilder();
        var stderrBuf = new StringBuilder();
        var timedOut = new AtomicBoolean();
        try {
            var stdoutDrain = drain(process.getInputStream(), stdoutBuf);
            var stderrDrain = drain(process.getErrorStream(), stderrBuf);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                timedOut.set(true);
            }
            stdoutDrain.join();
            stderrDrain.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("interrupted waiting for " + command, e);
        }
        var exit = timedOut.get() ? -1 : process.exitValue();
        var elapsed = Duration.between(start, Instant.now());
        return new ProcessResult(exit, stdoutBuf.toString(), stderrBuf.toString(),
                elapsed, timedOut.get());
    }

    private static Thread drain(java.io.InputStream stream, StringBuilder sink) {
        var thread = Thread.ofVirtual().unstarted(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                var buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    synchronized (sink) {
                        sink.append(buffer, 0, read);
                    }
                }
            } catch (IOException e) {
                logger.trace("stream drain ended: {}", e.getMessage(), e);
            }
        });
        thread.start();
        return thread;
    }

    record ProcessResult(int exitCode, String stdout, String stderr,
                         Duration elapsed, boolean timedOut) {
    }

    /**
     * Instance class for a live Docker sandbox. Package-private: callers
     * obtain instances only via {@link DockerSandboxProvider#create}.
     */
    static final class DockerSandbox implements Sandbox {

        private final String id;
        private final SandboxLimits limits;
        private final Map<String, String> metadata;
        private final AtomicBoolean closed = new AtomicBoolean();

        DockerSandbox(String id, SandboxLimits limits, Map<String, String> metadata) {
            this.id = Objects.requireNonNull(id);
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
            var args = new ArrayList<String>();
            args.add("docker");
            args.add("exec");
            args.add(id);
            args.addAll(command);
            var effective = timeout == null ? limits.wallTime() : timeout;
            var result = runProcess(args, effective);
            return new SandboxExec(result.exitCode(), result.stdout(),
                    result.stderr(), result.elapsed(), result.timedOut());
        }

        @Override
        public void writeFile(Path pathInsideSandbox, String content) {
            checkOpen();
            Path tempFile;
            try {
                tempFile = Files.createTempFile("atmo-sandbox-write-", ".tmp");
                Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("failed to stage file for write: " + e.getMessage(), e);
            }
            try {
                var cp = runProcess(List.of("docker", "cp",
                        tempFile.toString(), id + ":" + pathInsideSandbox),
                        Duration.ofSeconds(30));
                if (cp.exitCode() != 0) {
                    throw new IllegalStateException("docker cp into sandbox failed: " + cp.stderr());
                }
            } finally {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Not a correctness concern — temp files roll up with JVM exit.
                }
            }
        }

        @Override
        public String readFile(Path pathInsideSandbox) {
            checkOpen();
            var exec = runProcess(List.of("docker", "exec", id,
                    "cat", pathInsideSandbox.toString()), Duration.ofSeconds(30));
            if (exec.exitCode() != 0) {
                throw new IllegalStateException("docker exec cat failed: " + exec.stderr());
            }
            return exec.stdout();
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
            // Best-effort teardown: errors land in the trace log but the
            // caller does not see a thrown exception from close().
            var rm = runProcessQuiet(List.of("docker", "rm", "-f", id));
            if (rm.isPresent() && rm.get().exitCode() != 0) {
                logger.warn("docker rm -f {} returned {}: {}",
                        id, rm.get().exitCode(), rm.get().stderr());
            }
        }

        private void checkOpen() {
            if (closed.get()) {
                throw new IllegalStateException("sandbox " + id + " is already closed");
            }
        }

        private static Optional<ProcessResult> runProcessQuiet(List<String> command) {
            try {
                return Optional.of(runProcess(command, Duration.ofSeconds(10)));
            } catch (RuntimeException e) {
                logger.trace("teardown command failed: {} ({})", command, e.getMessage(), e);
                return Optional.empty();
            }
        }
    }
}
