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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link CodeSandbox} backed by an ephemeral container. One container is
 * started per session in the constructor and reused across every
 * {@link #exec(SandboxCommand)} round; {@link #close()} removes it.
 *
 * <p>Lifecycle ownership is explicit: this class <em>creates</em> the container,
 * so it is the only thing that removes it (Correctness Invariant #1). Every exit
 * path — normal completion, timeout, exec failure, or {@link #close()} from a
 * cancelled session — converges on {@link #remove()} (Invariant #2). Output is
 * bounded ({@link BoundedOutput}, Invariant #3) and the command line is built
 * from discrete arguments with model code routed through stdin
 * ({@link ContainerCommandBuilder}, Invariant #4).</p>
 */
final class ContainerCodeSandbox implements CodeSandbox {

    private static final Logger logger = LoggerFactory.getLogger(ContainerCodeSandbox.class);

    /** Grace period to let output-drain threads finish after a process exits. */
    private static final Duration DRAIN_GRACE = Duration.ofSeconds(2);

    /** Workspace subdirectory the model writes artifacts (screenshots, files) to. */
    static final String ARTIFACTS_DIR = ContainerCommandBuilder.WORKSPACE + "/artifacts";

    /** Larger capture cap for the (base64) artifact collector stream than for logs. */
    private static final int ARTIFACT_CAPTURE_BYTES = 8 * 1024 * 1024;

    /** Cap on artifacts surfaced per round, so a screenshot loop cannot flood the wire. */
    private static final int MAX_ARTIFACTS = 8;

    /**
     * Fixed collector script (never carries model input, so it is safe as a
     * {@code sh -c} argument — Invariant #4). For each file in the artifacts dir
     * it emits {@code <name>\t<base64>} on one line, then removes it so each
     * round surfaces only newly-written artifacts.
     */
    private static final String COLLECTOR_SCRIPT =
            "mkdir -p " + ARTIFACTS_DIR + "; cd " + ARTIFACTS_DIR + " || exit 0; "
            + "for f in *; do [ -f \"$f\" ] || continue; "
            + "printf '%s\\t%s\\n' \"$f\" \"$(base64 -w0 \"$f\")\"; rm -f \"$f\"; done";

    private final String engine;
    private final String containerName;
    private final CodeSandboxConfig config;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** The in-flight exec process, exposed so {@link #close()} can interrupt it. */
    private volatile Process currentExec;

    private ContainerCodeSandbox(String engine, String containerName, CodeSandboxConfig config) {
        this.engine = engine;
        this.containerName = containerName;
        this.config = config;
    }

    /**
     * Provision and start the container. On any failure the partially-created
     * container is removed before the exception propagates, so a failed start
     * never leaks a container (Invariant #2).
     */
    static ContainerCodeSandbox start(String engine, String sessionId, CodeSandboxConfig config)
            throws SandboxException {
        String name = containerName(sessionId);
        var sandbox = new ContainerCodeSandbox(engine, name, config);
        try {
            var result = sandbox.runProcess(
                    ContainerCommandBuilder.runArgs(engine, name, config),
                    null, Duration.ofSeconds(60), config.maxOutputBytes(), false);
            if (result.exitCode() != 0) {
                throw new SandboxException("Failed to start sandbox container '" + name
                        + "' (exit " + result.exitCode() + "): " + result.stderr().strip());
            }
            logger.debug("Started code sandbox container {} (image={}, network={})",
                    name, config.image(), config.network());
            sandbox.runSetup();
            return sandbox;
        } catch (SandboxException e) {
            sandbox.remove();
            throw e;
        }
    }

    /**
     * Run the optional one-time bootstrap command (e.g. installing a language
     * package the image does not ship). Failures are logged, not fatal — the
     * model can still attempt work and will see the error in its first round.
     * The command is operator-supplied configuration, never model input, so it
     * is safe as a {@code bash -lc} argument (Invariant #4).
     */
    private void runSetup() {
        if (config.setup().isBlank()) {
            return;
        }
        var args = java.util.List.of(engine, "exec", containerName, "bash", "-lc", config.setup());
        try {
            // Generous timeout: a setup step may pull packages over the network.
            var result = runProcess(args, null, Duration.ofSeconds(180),
                    config.maxOutputBytes(), false);
            if (result.exitCode() != 0) {
                logger.warn("Sandbox setup command exited {} for {}: {}",
                        result.exitCode(), containerName, result.stderr().strip());
            } else {
                logger.debug("Sandbox setup completed for {}", containerName);
            }
        } catch (SandboxException e) {
            logger.warn("Sandbox setup failed for {}: {}", containerName, e.getMessage());
        }
    }

    @Override
    public String id() {
        return containerName;
    }

    @Override
    public boolean isReady() {
        return !closed.get();
    }

    @Override
    public SandboxResult exec(SandboxCommand command) throws SandboxException {
        if (closed.get()) {
            throw new SandboxException("Sandbox " + containerName + " is closed");
        }
        Duration timeout = command.timeout() != null ? command.timeout() : config.execTimeout();
        var execArgs = ContainerCommandBuilder.execArgs(engine, containerName, command.language());
        var result = runProcess(execArgs, command.code(), timeout, config.maxOutputBytes(), true);
        var artifacts = collectArtifacts(timeout);
        return new SandboxResult(result.exitCode(), result.stdout(), result.stderr(),
                result.truncated(), result.timedOut(), result.duration(), artifacts);
    }

    /**
     * Pull any artifacts the round wrote to {@link #ARTIFACTS_DIR} (screenshots,
     * generated files) out of the container as bytes, then clear them. Artifact
     * collection is best-effort: a collector hiccup must not fail an otherwise
     * good code result, so failures degrade to an empty list (logged at debug).
     */
    private java.util.List<SandboxArtifact> collectArtifacts(Duration timeout) {
        if (closed.get()) {
            return java.util.List.of();
        }
        var args = java.util.List.of(engine, "exec", containerName, "sh", "-c", COLLECTOR_SCRIPT);
        try {
            var result = runProcess(args, null, timeout, ARTIFACT_CAPTURE_BYTES, false);
            return parseArtifacts(result.stdout());
        } catch (SandboxException e) {
            logger.debug("Artifact collection skipped for {}: {}", containerName, e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Parse the collector's {@code <name>\t<base64>} lines into artifacts. Lines
     * that do not decode are skipped; collection is capped at {@link #MAX_ARTIFACTS}.
     */
    static java.util.List<SandboxArtifact> parseArtifacts(String collectorOutput) {
        if (collectorOutput == null || collectorOutput.isBlank()) {
            return java.util.List.of();
        }
        var artifacts = new java.util.ArrayList<SandboxArtifact>();
        for (String line : collectorOutput.split("\n")) {
            if (artifacts.size() >= MAX_ARTIFACTS) {
                break;
            }
            int tab = line.indexOf('\t');
            if (tab <= 0 || tab == line.length() - 1) {
                continue;
            }
            String name = line.substring(0, tab);
            try {
                byte[] data = java.util.Base64.getDecoder()
                        .decode(line.substring(tab + 1).trim());
                artifacts.add(new SandboxArtifact(name, mimeFor(name), data));
            } catch (IllegalArgumentException e) {
                logger.debug("Skipping undecodable artifact line for {}", name);
            }
        }
        return java.util.List.copyOf(artifacts);
    }

    /** Infer a media type from a file name extension; conservative default. */
    static String mimeFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".log")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // close-once
        }
        Process exec = currentExec;
        if (exec != null && exec.isAlive()) {
            exec.destroyForcibly();
        }
        remove();
    }

    // --- internals ------------------------------------------------------------

    /**
     * Run a container-engine process, optionally piping {@code stdin} to it, and
     * capture bounded output. Used for both the {@code run} (start) and
     * {@code exec} (round) commands.
     */
    private SandboxResult runProcess(List<String> args, String stdin, Duration timeout,
                                     int outputCap, boolean track) throws SandboxException {
        var start = Instant.now();
        var out = new BoundedOutput(outputCap);
        var err = new BoundedOutput(outputCap);
        Process process = null;
        boolean tracked = false;
        try {
            process = new ProcessBuilder(args).start();
            // Track the code-exec process so close() can interrupt an in-flight round.
            if (track) {
                currentExec = process;
                tracked = true;
            }
            feedStdin(process, stdin);

            Thread outDrain = drain(process.getInputStream(), out, "code-sandbox-stdout");
            Thread errDrain = drain(process.getErrorStream(), err, "code-sandbox-stderr");

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            boolean timedOut = false;
            if (!finished) {
                timedOut = true;
                process.destroyForcibly();
                process.waitFor(DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
            joinQuietly(outDrain);
            joinQuietly(errDrain);

            int exitCode = process.isAlive() ? -1 : process.exitValue();
            return new SandboxResult(exitCode, out.toString(), err.toString(),
                    out.truncated() || err.truncated(), timedOut,
                    Duration.between(start, Instant.now()), List.of());
        } catch (IOException e) {
            throw new SandboxException("Sandbox process failed: " + String.join(" ", args), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new SandboxException("Interrupted while running sandbox command", e);
        } finally {
            if (tracked) {
                currentExec = null;
            }
        }
    }

    private static void feedStdin(Process process, String stdin) throws IOException {
        try (OutputStream os = process.getOutputStream()) {
            if (stdin != null) {
                os.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /** Start a daemon thread that drains a stream into a bounded buffer. */
    private static Thread drain(InputStream stream, BoundedOutput sink, String name) {
        var thread = new Thread(() -> {
            char[] buffer = new char[4096];
            try (var reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    sink.append(new String(buffer, 0, read));
                }
            } catch (IOException e) {
                // Stream closed underneath us when the process was killed — expected
                // on the timeout path. Record at TRACE rather than swallowing silently.
                logger.trace("Drain stream {} closed early", name, e);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(DRAIN_GRACE.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Idempotent, best-effort container removal. Safe to call after a failed start. */
    private void remove() {
        try {
            new ProcessBuilder(ContainerCommandBuilder.removeArgs(engine, containerName))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (IOException e) {
            logger.debug("Failed to remove sandbox container {}: {}", containerName, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted removing sandbox container {}", containerName);
        }
    }

    /**
     * Derive a container name from the session id. Container names accept only
     * {@code [a-zA-Z0-9_.-]}; anything else is replaced so an attacker-influenced
     * session id can never inject an argument (Invariant #4 — names are validated
     * before reaching the engine).
     */
    static String containerName(String sessionId) {
        String sanitized = (sessionId == null ? "" : sessionId)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "-")
                // Trim leading/trailing separators so a separator-only id (e.g. an
                // all-whitespace session id) collapses to the "session" fallback
                // rather than a bare run of dashes.
                .replaceAll("^[-._]+", "")
                .replaceAll("[-._]+$", "");
        if (sanitized.isBlank()) {
            sanitized = "session";
        }
        if (sanitized.length() > 48) {
            sanitized = sanitized.substring(0, 48);
        }
        return "atmo-sandbox-" + sanitized;
    }
}
