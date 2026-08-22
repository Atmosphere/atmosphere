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
    public IsolationTier tier() {
        return IsolationTier.CONTAINER;
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
        var create = runProcess(createArgs(id, image, limits, null), Duration.ofSeconds(30));
        if (create.exitCode() != 0) {
            throw new IllegalStateException("docker create failed: " + create.stderr());
        }
        var start = runProcess(List.of("docker", "start", id), Duration.ofSeconds(15));
        if (start.exitCode() != 0) {
            throw new IllegalStateException("docker start failed: " + start.stderr());
        }
        return new DockerSandbox(id, limits, metadata == null ? Map.of() : Map.copyOf(metadata));
    }

    /**
     * Build the {@code docker create} argument array. Shared between
     * {@link #create} and {@link DockerSandbox#expose}, which recreates the
     * container with a published port.
     *
     * @param publishPort container port to publish on an ephemeral host
     *                    port ({@code -p 0:<port>}), or {@code null}
     */
    static List<String> createArgs(String id, String image, SandboxLimits limits,
                                   Integer publishPort) {
        var args = new ArrayList<String>();
        args.add("docker");
        args.add("create");
        args.add("--name");
        args.add(id);
        args.add("--cpus=" + limits.cpuFraction());
        args.add("--memory=" + limits.memoryBytes() + "b");
        switch (limits.networkPolicy().mode()) {
            case NONE -> args.add("--network=none");
            case GIT_ONLY, ALLOWLIST -> {
                // Container gets bridge networking; an egress firewall
                // enforced outside Docker (e.g. iptables, istio egress policy)
                // restricts which hosts are reachable. This avoids coupling
                // the sandbox module to a specific host-firewall tool.
                args.add("--network=bridge");
                args.add("--label");
                args.add("atmosphere.network.policy=" + limits.networkPolicy().mode().name());
                for (var host : limits.networkPolicy().allowedHosts()) {
                    args.add("--label");
                    args.add("atmosphere.network.allow=" + host);
                }
            }
            case FULL -> args.add("--network=bridge");
        }
        if (publishPort != null) {
            args.add("-p");
            args.add("0:" + publishPort);
        }
        // Keep container alive for exec calls; the entrypoint idles.
        args.add("--entrypoint");
        args.add("sleep");
        args.add(image);
        args.add(String.valueOf(limits.wallTime().toSeconds() + 60));
        return args;
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
        private final AtomicBoolean hibernated = new AtomicBoolean();
        /** Images `expose` commits as recreation vehicles; removed on close (Invariant #1). */
        private final List<String> ephemeralImages =
                java.util.Collections.synchronizedList(new ArrayList<>());

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
            resumeIfHibernated();
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
            resumeIfHibernated();
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
            resumeIfHibernated();
            var exec = runProcess(List.of("docker", "exec", id,
                    "cat", pathInsideSandbox.toString()), Duration.ofSeconds(30));
            if (exec.exitCode() != 0) {
                throw new IllegalStateException("docker exec cat failed: " + exec.stderr());
            }
            return exec.stdout();
        }

        /**
         * Publishes a container port on an ephemeral host port. Docker
         * cannot publish a port on a running container, so this commits the
         * container's filesystem, removes it, and recreates it under the
         * same name with {@code -p 0:<port>} from the committed image —
         * process state is lost (the entrypoint idles anyway), filesystem
         * state is preserved.
         */
        @Override
        public int expose(int portInsideSandbox) {
            checkOpen();
            if (portInsideSandbox < 1 || portInsideSandbox > 65535) {
                throw new IllegalArgumentException("port out of range: " + portInsideSandbox);
            }
            if (limits.networkPolicy().mode() == NetworkPolicy.Mode.NONE) {
                throw new IllegalStateException(
                        "cannot expose a port: sandbox network policy is NONE");
            }
            resumeIfHibernated();
            var imageRef = "atmo-expose-" + UUID.randomUUID();
            var commit = runProcess(List.of("docker", "commit", id, imageRef),
                    Duration.ofSeconds(60));
            if (commit.exitCode() != 0) {
                throw new IllegalStateException("docker commit for expose failed: " + commit.stderr());
            }
            ephemeralImages.add(imageRef);
            var rm = runProcess(List.of("docker", "rm", "-f", id), Duration.ofSeconds(30));
            if (rm.exitCode() != 0) {
                throw new IllegalStateException("docker rm for expose failed: " + rm.stderr());
            }
            var create = runProcess(createArgs(id, imageRef, limits, portInsideSandbox),
                    Duration.ofSeconds(30));
            if (create.exitCode() != 0) {
                // The original container is gone — this sandbox cannot limp
                // on. Reach the terminal state loudly (Invariant #2).
                closed.set(true);
                removeEphemeralImages();
                throw new IllegalStateException(
                        "docker create for expose failed; sandbox " + id
                                + " is closed: " + create.stderr());
            }
            var start = runProcess(List.of("docker", "start", id), Duration.ofSeconds(15));
            if (start.exitCode() != 0) {
                closed.set(true);
                removeEphemeralImages();
                throw new IllegalStateException(
                        "docker start for expose failed; sandbox " + id
                                + " is closed: " + start.stderr());
            }
            var port = runProcess(List.of("docker", "port", id, portInsideSandbox + "/tcp"),
                    Duration.ofSeconds(10));
            if (port.exitCode() != 0 || port.stdout().isBlank()) {
                throw new IllegalStateException("docker port lookup failed: " + port.stderr());
            }
            // First line looks like "0.0.0.0:49153" (or ":::49153" for v6).
            var firstLine = port.stdout().lines().findFirst().orElse("");
            var colon = firstLine.lastIndexOf(':');
            try {
                return Integer.parseInt(firstLine.substring(colon + 1).trim());
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "unparseable docker port output: " + port.stdout(), e);
            }
        }

        /**
         * Commits the container filesystem to an image; the returned
         * reference is a Docker image name a caller can restore from with
         * {@code DockerSandboxProvider.create(reference, ...)}. The image
         * outlives this sandbox by design — the caller owns it and releases
         * it with {@code docker rmi} when no longer needed.
         */
        @Override
        public SandboxSnapshot snapshot() {
            checkOpen();
            var snapshotId = UUID.randomUUID().toString();
            var imageRef = "atmo-snapshot-" + snapshotId;
            var commit = runProcess(List.of("docker", "commit", id, imageRef),
                    Duration.ofSeconds(60));
            if (commit.exitCode() != 0) {
                throw new IllegalStateException("docker commit failed: " + commit.stderr());
            }
            return new SandboxSnapshot(snapshotId, imageRef, Instant.now());
        }

        /**
         * Freezes the container's processes via the cgroup freezer
         * ({@code docker pause}) — CPU is reclaimed, filesystem and memory
         * state are preserved. The next {@link #exec}, {@link #writeFile},
         * or {@link #readFile} implicitly resumes.
         */
        @Override
        public void hibernate() {
            checkOpen();
            if (!hibernated.compareAndSet(false, true)) {
                return; // already hibernated — idempotent
            }
            var pause = runProcess(List.of("docker", "pause", id), Duration.ofSeconds(15));
            if (pause.exitCode() != 0) {
                hibernated.set(false);
                throw new IllegalStateException("docker pause failed: " + pause.stderr());
            }
        }

        private void resumeIfHibernated() {
            if (!hibernated.compareAndSet(true, false)) {
                return;
            }
            var unpause = runProcess(List.of("docker", "unpause", id), Duration.ofSeconds(15));
            if (unpause.exitCode() != 0) {
                hibernated.set(true);
                throw new IllegalStateException("docker unpause failed: " + unpause.stderr());
            }
        }

        private void removeEphemeralImages() {
            List<String> images;
            synchronized (ephemeralImages) {
                images = new ArrayList<>(ephemeralImages);
                ephemeralImages.clear();
            }
            for (var image : images) {
                var rmi = runProcessQuiet(List.of("docker", "rmi", "-f", image));
                if (rmi.isPresent() && rmi.get().exitCode() != 0) {
                    logger.debug("docker rmi -f {} returned {}: {}",
                            image, rmi.get().exitCode(), rmi.get().stderr());
                }
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
            // Best-effort teardown: errors land in the trace log but the
            // caller does not see a thrown exception from close().
            var rm = runProcessQuiet(List.of("docker", "rm", "-f", id));
            if (rm.isPresent() && rm.get().exitCode() != 0) {
                logger.warn("docker rm -f {} returned {}: {}",
                        id, rm.get().exitCode(), rm.get().stderr());
            }
            removeEphemeralImages();
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
