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
package org.atmosphere.a2a.runtime;

import org.atmosphere.a2a.types.TaskPushNotificationConfig;
import org.atmosphere.a2a.types.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Implements A2A task push notifications: stores the webhook configs a client
 * registers against a task and, when that task reaches a terminal state,
 * POSTs the final {@link TaskStatusUpdateEvent} to each registered URL.
 *
 * <p>Wired by registering itself as a {@link TaskManager} status listener in
 * the constructor; {@link #close()} unregisters it (Correctness Invariant #1 —
 * symmetric registration/removal) and shuts down the owned HTTP client.</p>
 *
 * <h2>Backpressure (Correctness Invariant #3)</h2>
 *
 * Both the number of tasks tracked and the configs per task are bounded.
 * Delivery is fire-and-forget on the JDK HTTP client's executor with a
 * request timeout, so a slow or dead webhook cannot block task completion or
 * accumulate threads.
 *
 * <h2>Boundary safety (Correctness Invariant #4)</h2>
 *
 * A registered URL is validated to be an absolute {@code http}/{@code https}
 * URI before it is stored; other schemes are rejected at the boundary rather
 * than dereferenced. To prevent the endpoint being turned into an SSRF proxy,
 * a webhook whose host resolves to a non-routable / internal address
 * (loopback, private, link-local — including the {@code 169.254.169.254} cloud
 * metadata range — wildcard, multicast, or IPv6 unique-local) is refused by
 * default, and the resolved address is re-checked at delivery time to fail
 * closed against DNS rebinding. Delivery to internal hosts is possible only by
 * explicit opt-in ({@code allowPrivateTargets}), intended for trusted networks.
 * The config {@code token} (when present) is sent as the
 * {@code X-A2A-Notification-Token} header so the receiver can authenticate
 * the callback.
 */
public final class PushNotificationService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PushNotificationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Header carrying the client-supplied validation token to the webhook. */
    public static final String TOKEN_HEADER = "X-A2A-Notification-Token";

    /** Default cap on tasks with registered push configs. */
    public static final int DEFAULT_MAX_TASKS = 10_000;
    /** Cap on push configs per task. */
    public static final int MAX_CONFIGS_PER_TASK = 8;

    private final Map<String, Map<String, TaskPushNotificationConfig>> configs = new ConcurrentHashMap<>();
    private final TaskManager taskManager;
    private final Consumer<TaskStatusUpdateEvent> listener = this::onStatusUpdate;
    private final HttpClient http;
    private final boolean ownsHttp;
    private final int maxTasks;
    private final Duration requestTimeout;
    private final boolean allowPrivateTargets;
    private volatile boolean closed;

    public PushNotificationService(TaskManager taskManager) {
        this(taskManager, false);
    }

    /**
     * @param allowPrivateTargets when {@code false} (the secure default) a
     *     webhook URL whose host resolves to a loopback, private, link-local,
     *     wildcard, multicast, or IPv6 unique-local address is rejected to
     *     prevent SSRF; set {@code true} only on trusted networks where
     *     delivery to internal hosts is intended.
     */
    public PushNotificationService(TaskManager taskManager, boolean allowPrivateTargets) {
        this(taskManager, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build(), true,
                DEFAULT_MAX_TASKS, Duration.ofSeconds(10), allowPrivateTargets);
    }

    public PushNotificationService(TaskManager taskManager, HttpClient http, boolean ownsHttp,
                                   int maxTasks, Duration requestTimeout, boolean allowPrivateTargets) {
        this.taskManager = taskManager;
        this.http = http;
        this.ownsHttp = ownsHttp;
        if (maxTasks <= 0) {
            throw new IllegalArgumentException("maxTasks must be > 0, got " + maxTasks);
        }
        this.maxTasks = maxTasks;
        this.requestTimeout = requestTimeout;
        this.allowPrivateTargets = allowPrivateTargets;
        taskManager.onStatusUpdate(listener);
    }

    /**
     * Store a webhook config for a task, generating an id when the client did
     * not supply one. Rejects a non-{@code http(s)} or relative URL at the
     * boundary. Returns the stored config (with its assigned id).
     */
    public TaskPushNotificationConfig create(TaskPushNotificationConfig config) {
        validateUrl(config.url());
        var id = config.id() != null && !config.id().isBlank()
                ? config.id() : UUID.randomUUID().toString();
        var stored = new TaskPushNotificationConfig(config.tenant(), id, config.taskId(),
                config.url(), config.token(), config.authentication());
        configs.compute(config.taskId(), (taskId, existing) -> {
            var map = existing != null ? existing
                    : new ConcurrentHashMap<String, TaskPushNotificationConfig>();
            if (map.size() >= MAX_CONFIGS_PER_TASK && !map.containsKey(id)) {
                throw new IllegalStateException(
                        "push config limit (" + MAX_CONFIGS_PER_TASK + ") reached for task " + taskId);
            }
            map.put(id, stored);
            return map;
        });
        evictOldestIfOverCapacity();
        return stored;
    }

    /** Look up a single stored config, or {@code null} if absent. */
    public TaskPushNotificationConfig get(String taskId, String configId) {
        var map = configs.get(taskId);
        return map != null ? map.get(configId) : null;
    }

    /** All configs registered for a task (possibly empty). */
    public List<TaskPushNotificationConfig> list(String taskId) {
        var map = configs.get(taskId);
        return map != null ? List.copyOf(map.values()) : List.of();
    }

    /** Delete a config; returns {@code true} if one was removed. */
    public boolean delete(String taskId, String configId) {
        var map = configs.get(taskId);
        if (map == null) {
            return false;
        }
        var removed = map.remove(configId) != null;
        if (map.isEmpty()) {
            configs.remove(taskId, map);
        }
        return removed;
    }

    private void onStatusUpdate(TaskStatusUpdateEvent event) {
        if (closed || event.status() == null || event.status().state() == null
                || !event.status().state().isTerminal()) {
            return;
        }
        // Drain the task's configs on terminal delivery — the task is done,
        // so the webhooks will never fire again (Invariant #2, terminal path).
        var taskConfigs = configs.remove(event.taskId());
        if (taskConfigs == null) {
            return;
        }
        for (var config : taskConfigs.values()) {
            deliver(config, event);
        }
    }

    private void deliver(TaskPushNotificationConfig config, TaskStatusUpdateEvent event) {
        try {
            var target = URI.create(config.url());
            if (!allowPrivateTargets && resolvesToInternalOrUnknown(target.getHost())) {
                // DNS-rebinding / late-flip defence: the host passed the guard
                // at registration but now resolves to an internal address (or no
                // longer resolves). Fail closed (Correctness Invariant #4).
                logger.warn("Skipping push notification for task {}: {} resolves to a "
                        + "non-routable/internal address", config.taskId(), config.url());
                return;
            }
            var body = MAPPER.writeValueAsString(event);
            var builder = HttpRequest.newBuilder(target)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (config.token() != null && !config.token().isBlank()) {
                builder.header(TOKEN_HEADER, config.token());
            }
            // Fire-and-forget: a slow/dead webhook must not block task
            // completion. Failures are logged, never propagated (best-effort).
            http.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            logger.warn("Push notification to {} failed for task {}: {}",
                                    config.url(), config.taskId(), error.getMessage());
                        } else if (response.statusCode() >= 400) {
                            logger.warn("Push notification to {} for task {} returned HTTP {}",
                                    config.url(), config.taskId(), response.statusCode());
                        } else {
                            logger.debug("Delivered push notification for task {} to {}",
                                    config.taskId(), config.url());
                        }
                    });
        } catch (RuntimeException e) {
            logger.warn("Failed to build push notification for task {}: {}",
                    config.taskId(), e.getMessage(), e);
        }
    }

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("push notification url must not be empty");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid push notification url: " + url, e);
        }
        var scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "push notification url must be an absolute http(s) URL: " + url);
        }
        if (allowPrivateTargets) {
            return;
        }
        // SSRF egress guard: reject a webhook that resolves to a non-routable /
        // internal address. A host that cannot be resolved now is allowed
        // through here and re-checked (fail closed) at delivery time.
        var host = unbracket(uri.getHost());
        try {
            for (var address : InetAddress.getAllByName(host)) {
                if (isInternalAddress(address)) {
                    throw new IllegalArgumentException(
                            "push notification url resolves to a non-routable/internal address ("
                                    + address.getHostAddress() + "); refusing to store: " + url);
                }
            }
        } catch (UnknownHostException e) {
            logger.debug("push notification host {} did not resolve at registration; "
                    + "it will be re-validated at delivery time", host);
        }
    }

    /**
     * Resolve {@code host} and report whether it maps to an internal address or
     * cannot be resolved. Used at delivery time to fail closed against DNS
     * rebinding — an unresolvable host returns {@code true} (do not deliver).
     */
    private static boolean resolvesToInternalOrUnknown(String host) {
        if (host == null) {
            return true;
        }
        try {
            for (var address : InetAddress.getAllByName(unbracket(host))) {
                if (isInternalAddress(address)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            return true;
        }
    }

    private static boolean isInternalAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        // IPv6 unique-local addresses (fc00::/7) are not covered by
        // isSiteLocalAddress and are used by some cloud metadata services.
        var octets = address.getAddress();
        return octets.length == 16 && (octets[0] & 0xfe) == 0xfc;
    }

    private static String unbracket(String host) {
        if (host.length() > 1 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private void evictOldestIfOverCapacity() {
        // Bound the number of tracked tasks. Eviction order is unspecified
        // (configs are keyed by random task UUID with no timestamp here); the
        // cap exists purely as a DoS backstop — terminal delivery and DELETE
        // are the normal drains.
        while (configs.size() > maxTasks) {
            var victim = configs.keySet().stream().findAny().orElse(null);
            if (victim == null || configs.remove(victim) == null) {
                return;
            }
            logger.trace("evicted push configs for task {} (capacity {})", victim, maxTasks);
        }
    }

    @Override
    public void close() {
        closed = true;
        taskManager.removeStatusListener(listener);
        configs.clear();
        if (ownsHttp) {
            http.close();
        }
    }
}
