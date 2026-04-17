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
 * <p>Production deployments must use {@link DockerSandboxProvider} or a
 * dedicated hypervisor-backed backend.</p>
 */
public final class InProcessSandboxProvider implements SandboxProvider {

    private static final Logger logger = LoggerFactory.getLogger(InProcessSandboxProvider.class);

    @Override
    public String name() {
        return "in-process";
    }

    @Override
    public boolean isAvailable() {
        return true;
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
            var result = DockerSandboxProvider.runProcess(command, effective);
            return new SandboxExec(result.exitCode(), result.stdout(),
                    result.stderr(), result.elapsed(), result.timedOut());
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
