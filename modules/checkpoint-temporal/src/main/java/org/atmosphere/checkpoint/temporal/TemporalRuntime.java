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
package org.atmosphere.checkpoint.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Locale;

/**
 * Process-wide Temporal connection shared by every
 * {@code TemporalDurableExecutionProvider} instance (ServiceLoader creates a
 * fresh provider per {@code resolve()} call, so the connection cannot live on
 * the instance). Availability is runtime truth (Correctness Invariant #5):
 * {@link #available()} is true only after {@code newConnectedServiceStubs}
 * has actually reached the server and the worker has started — classpath
 * presence and configuration intent count for nothing. Failed probes are
 * cached for {@value #FAILED_PROBE_BACKOFF_MS}ms so per-run resolution stays
 * cheap while a later-started server is still picked up.
 *
 * <p>Configuration, each system property overridable by the equivalent
 * environment variable ({@code atmosphere.temporal.target} →
 * {@code ATMOSPHERE_TEMPORAL_TARGET}):</p>
 * <ul>
 *   <li>{@code atmosphere.temporal.target} — host:port, default {@code 127.0.0.1:7233}</li>
 *   <li>{@code atmosphere.temporal.namespace} — default {@code default}</li>
 *   <li>{@code atmosphere.temporal.task-queue} — default {@code atmosphere-workflow}</li>
 *   <li>{@code atmosphere.temporal.connect-timeout-ms} — default {@code 2000}</li>
 *   <li>{@code atmosphere.temporal.step-timeout-ms} — per-step start-to-close, default {@code 3600000} (1h)</li>
 * </ul>
 *
 * <p>Ownership (Correctness Invariant #1): only connections this class opened
 * are shut down by {@link #shutdown()} — a client installed by the test
 * harness is used but never closed here.</p>
 */
final class TemporalRuntime {

    private static final Logger logger = LoggerFactory.getLogger(TemporalRuntime.class);

    static final String TARGET_PROPERTY = "atmosphere.temporal.target";
    static final String NAMESPACE_PROPERTY = "atmosphere.temporal.namespace";
    static final String TASK_QUEUE_PROPERTY = "atmosphere.temporal.task-queue";
    static final String CONNECT_TIMEOUT_PROPERTY = "atmosphere.temporal.connect-timeout-ms";
    static final String STEP_TIMEOUT_PROPERTY = "atmosphere.temporal.step-timeout-ms";

    private static final String DEFAULT_TARGET = "127.0.0.1:7233";
    private static final String DEFAULT_NAMESPACE = "default";
    private static final String DEFAULT_TASK_QUEUE = "atmosphere-workflow";
    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 2_000L;
    private static final long DEFAULT_STEP_TIMEOUT_MS = 3_600_000L;
    private static final long FAILED_PROBE_BACKOFF_MS = 30_000L;

    private static final Object LOCK = new Object();

    private static WorkflowClient client;
    private static String taskQueue;
    private static WorkflowServiceStubs ownedStubs;
    private static WorkerFactory ownedFactory;
    private static boolean shutdownHookInstalled;
    private static long lastFailedProbeAtMs;

    private TemporalRuntime() {
    }

    /** Connect (or reuse the connection) — true only when genuinely reachable. */
    static boolean available() {
        return ensureStarted();
    }

    static WorkflowClient client() {
        synchronized (LOCK) {
            if (client == null) {
                throw new IllegalStateException("Temporal runtime is not connected");
            }
            return client;
        }
    }

    static String taskQueue() {
        synchronized (LOCK) {
            if (taskQueue == null) {
                throw new IllegalStateException("Temporal runtime is not connected");
            }
            return taskQueue;
        }
    }

    static long stepTimeoutMillis() {
        return longProperty(STEP_TIMEOUT_PROPERTY, DEFAULT_STEP_TIMEOUT_MS);
    }

    /**
     * Use an externally owned client (the Temporal test environment) instead
     * of connecting. The caller keeps ownership — {@link #shutdown()} will
     * not close it.
     */
    static void installForTesting(WorkflowClient testClient, String testTaskQueue) {
        synchronized (LOCK) {
            shutdown();
            client = testClient;
            taskQueue = testTaskQueue;
        }
    }

    /** Drop any connection (owned ones are closed) and clear the probe backoff. */
    static void reset() {
        synchronized (LOCK) {
            shutdown();
            lastFailedProbeAtMs = 0L;
        }
    }

    /** Close owned resources only; idempotent. */
    static void shutdown() {
        synchronized (LOCK) {
            if (ownedFactory != null) {
                ownedFactory.shutdown();
                ownedFactory = null;
            }
            if (ownedStubs != null) {
                ownedStubs.shutdown();
                ownedStubs = null;
            }
            client = null;
            taskQueue = null;
        }
    }

    private static boolean ensureStarted() {
        synchronized (LOCK) {
            if (client != null) {
                return true;
            }
            var now = System.currentTimeMillis();
            if (lastFailedProbeAtMs != 0L && now - lastFailedProbeAtMs < FAILED_PROBE_BACKOFF_MS) {
                return false;
            }
            WorkflowServiceStubs stubs = null;
            try {
                stubs = WorkflowServiceStubs.newConnectedServiceStubs(
                        WorkflowServiceStubsOptions.newBuilder().setTarget(target()).build(),
                        Duration.ofMillis(longProperty(CONNECT_TIMEOUT_PROPERTY, DEFAULT_CONNECT_TIMEOUT_MS)));
                var connected = WorkflowClient.newInstance(stubs,
                        WorkflowClientOptions.newBuilder().setNamespace(namespace()).build());
                var queue = stringProperty(TASK_QUEUE_PROPERTY, DEFAULT_TASK_QUEUE);
                var factory = WorkerFactory.newInstance(connected);
                var worker = factory.newWorker(queue);
                worker.registerWorkflowImplementationTypes(AtmosphereTemporalWorkflowImpl.class);
                worker.registerActivitiesImplementations(new StepExecutionActivitiesImpl());
                factory.start();
                ownedStubs = stubs;
                ownedFactory = factory;
                client = connected;
                taskQueue = queue;
                installShutdownHookOnce();
                logger.info("Connected to Temporal at {} (namespace '{}', task queue '{}')",
                        target(), namespace(), queue);
                return true;
            } catch (Exception e) {
                // Partial-init cleanup: stubs connected but worker start failed.
                if (stubs != null && ownedStubs == null) {
                    stubs.shutdown();
                }
                lastFailedProbeAtMs = now;
                logger.debug("Temporal backend not available at {}: {}", target(), e.toString());
                logger.trace("Temporal connection failure", e);
                return false;
            }
        }
    }

    private static void installShutdownHookOnce() {
        if (!shutdownHookInstalled) {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(TemporalRuntime::shutdown, "atmosphere-temporal-shutdown"));
            shutdownHookInstalled = true;
        }
    }

    private static String target() {
        return stringProperty(TARGET_PROPERTY, DEFAULT_TARGET);
    }

    private static String namespace() {
        return stringProperty(NAMESPACE_PROPERTY, DEFAULT_NAMESPACE);
    }

    private static String stringProperty(String key, String defaultValue) {
        var fromSystem = System.getProperty(key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem;
        }
        var fromEnv = System.getenv(envKey(key));
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return defaultValue;
    }

    private static long longProperty(String key, long defaultValue) {
        var raw = stringProperty(key, null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            logger.warn("Ignoring non-numeric value '{}' for {} — using {}", raw, key, defaultValue);
            return defaultValue;
        }
    }

    private static String envKey(String propertyKey) {
        return propertyKey.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
