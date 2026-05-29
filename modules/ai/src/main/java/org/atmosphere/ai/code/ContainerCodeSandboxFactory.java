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

    private final CodeSandboxConfig config;

    private final Object probeLock = new Object();
    private long lastProbeAt;
    private boolean lastProbeOk;
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
            if (resolvedEngine != null && now - lastProbeAt < PROBE_TTL_MILLIS) {
                return lastProbeOk;
            }
            lastProbeAt = now;
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
        try {
            Process process = new ProcessBuilder(ContainerCommandBuilder.infoArgs(engine))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            // Binary not on PATH — a normal "engine absent" outcome, not an error.
            logger.trace("Container engine '{}' not invocable: {}", engine, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
