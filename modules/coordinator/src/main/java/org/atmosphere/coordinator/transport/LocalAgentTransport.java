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
package org.atmosphere.coordinator.transport;

import tools.jackson.databind.ObjectMapper;
import org.atmosphere.a2a.runtime.LocalDispatchable;
import org.atmosphere.coordinator.fleet.AgentResult;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Transport for co-located agents (same JVM). Invokes the agent's A2A protocol
 * handler directly — no HTTP, no network overhead.
 *
 * <p>Resolution is lazy and <em>re-queried on every invocation</em>. The
 * transport does not cache a handler reference at construction time, so an
 * {@code @Agent}-annotated bean that finishes registration after the
 * coordinator wires its fleet (common under Spring Boot, where bean creation
 * order can interleave with framework annotation scanning) is still found on
 * the first dispatch. When more than one candidate path is supplied, each is
 * tried in order before the call is reported as a failure — this matches the
 * path list {@code CoordinatorProcessor} probes for agent endpoints
 * ({@code customEndpoint}, {@code /atmosphere/agent/{name}/a2a},
 * {@code /atmosphere/a2a/{name}}).</p>
 */
public class LocalAgentTransport implements AgentTransport {

    private static final Logger logger = LoggerFactory.getLogger(LocalAgentTransport.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final ThreadLocal<Set<String>> dispatchChain = ThreadLocal.withInitial(HashSet::new);

    private final AtmosphereFramework framework;
    private final List<String> candidatePaths;

    /**
     * Constructs a transport that resolves the given agent via a single A2A
     * handler path. Prefer
     * {@link #LocalAgentTransport(AtmosphereFramework, String, List)} when the
     * caller knows more than one candidate path — otherwise a handler
     * registered at a variant path (e.g. {@code /atmosphere/a2a/{name}})
     * will be missed even though the framework has it.
     */
    public LocalAgentTransport(AtmosphereFramework framework, String agentName, String a2aPath) {
        this(framework, agentName, List.of(a2aPath));
    }

    /**
     * Constructs a transport that tries each candidate path in order on every
     * call. The first registered, {@link LocalDispatchable}-capable handler
     * wins. When none resolve, the call returns a failure result and a
     * {@code WARN} log records the full candidate list so the startup race
     * (agent handler not yet registered when the coordinator dispatches) is
     * visible to operators.
     *
     * @param framework      the Atmosphere framework whose handler map is re-queried per call
     * @param agentName      the logical agent name (used for logging / errors only)
     * @param candidatePaths ordered list of A2A handler paths to probe; must be non-empty
     */
    public LocalAgentTransport(AtmosphereFramework framework, String agentName,
                               List<String> candidatePaths) {
        if (candidatePaths == null || candidatePaths.isEmpty()) {
            throw new IllegalArgumentException(
                    "LocalAgentTransport for '" + agentName + "' requires at least one candidate path");
        }
        this.framework = framework;
        this.candidatePaths = List.copyOf(candidatePaths);
    }

    /**
     * Resolves the current in-JVM handler for this agent by walking the
     * candidate path list on the live framework handler map. Returns a
     * result that distinguishes three states: a usable {@link LocalDispatchable},
     * a handler registered at one of the candidate paths that does <em>not</em>
     * implement {@code LocalDispatchable} (misconfiguration, not a race), and
     * nothing registered at any candidate path (the startup race case).
     * This is intentionally called on every dispatch — caching here would
     * reintroduce the startup race this transport exists to eliminate.
     */
    private Resolution resolve() {
        var handlers = framework.getAtmosphereHandlers();
        String wrongTypePath = null;
        for (var path : candidatePaths) {
            var wrapper = handlers.get(path);
            if (wrapper == null) {
                continue;
            }
            if (wrapper.atmosphereHandler() instanceof LocalDispatchable dispatchable) {
                return new Resolution(dispatchable, null);
            }
            if (wrongTypePath == null) {
                wrongTypePath = path;
            }
        }
        return new Resolution(null, wrongTypePath);
    }

    /**
     * Outcome of a candidate-path walk. When {@link #dispatchable} is non-null
     * the call can proceed; when {@link #wrongTypePath} is non-null a handler
     * exists but is not {@link LocalDispatchable}; when both are null nothing
     * is registered yet.
     */
    private record Resolution(LocalDispatchable dispatchable, String wrongTypePath) { }

    @Override
    public AgentResult send(String agentName, String skill, Map<String, Object> args) {
        var chain = dispatchChain.get();
        if (!chain.add(agentName)) {
            throw new IllegalStateException(
                    "Circular local dispatch detected: agent '" + agentName + "' is already in the dispatch chain");
        }
        var start = Instant.now();
        try {
            var requestBody = JsonRpcUtils.buildSendRequest(skill, args);

            var resolved = resolve();
            if (resolved.dispatchable() == null) {
                if (resolved.wrongTypePath() != null) {
                    return AgentResult.failure(agentName, skill,
                            "Handler at " + resolved.wrongTypePath()
                                    + " does not support local dispatch",
                            Duration.between(start, Instant.now()));
                }
                logger.warn("LocalAgentTransport: no in-JVM handler for agent '{}' at any of {}; "
                        + "returning failure without A2A fall-through. Check that the @Agent bean "
                        + "is registered before the coordinator dispatches.",
                        agentName, candidatePaths);
                return AgentResult.failure(agentName, skill,
                        "Agent not found at " + candidatePaths,
                        Duration.between(start, Instant.now()));
            }

            var dispatchable = resolved.dispatchable();

            var responseStr = dispatchable.dispatchLocal(requestBody);
            var json = mapper.readTree(responseStr);
            var duration = Duration.between(start, Instant.now());

            // Check for JSON-RPC error field before reporting success
            if (json.has("error")) {
                var errorNode = json.get("error");
                var errorMsg = errorNode.has("message")
                        ? errorNode.get("message").stringValue()
                        : errorNode.toString();
                logger.warn("Local dispatch to '{}' skill '{}' returned error: {}",
                        agentName, skill, errorMsg);
                return AgentResult.failure(agentName, skill, errorMsg, duration);
            }

            // Check for failed task status
            var result = json.get("result");
            if (result != null && result.has("status")) {
                var state = result.get("status").has("state")
                        ? result.get("status").get("state").stringValue() : "";
                if ("failed".equalsIgnoreCase(state) || "canceled".equalsIgnoreCase(state)) {
                    var statusMsg = result.get("status").has("message")
                            ? result.get("status").get("message").stringValue()
                            : "Task " + state;
                    return AgentResult.failure(agentName, skill, statusMsg, duration);
                }
            }

            var text = JsonRpcUtils.extractArtifactText(json);

            logger.debug("Local dispatch to '{}' skill '{}' completed in {}ms",
                    agentName, skill, duration.toMillis());
            return new AgentResult(agentName, skill, text, Map.of(), duration, true);

        } catch (Exception e) {
            logger.error("Local dispatch to '{}' failed", agentName, e);
            return AgentResult.failure(agentName, skill,
                    "Local dispatch failed: " + e.getMessage(),
                    Duration.between(start, Instant.now()));
        } finally {
            chain.remove(agentName);
            if (chain.isEmpty()) {
                dispatchChain.remove();
            }
        }
    }

    @Override
    public void stream(String agentName, String skill, Map<String, Object> args,
                       Consumer<String> onToken, Runnable onComplete) {
        try {
            var resolved = resolve();
            if (resolved.dispatchable() != null) {
                var requestBody = JsonRpcUtils.buildSendRequest(skill, args);
                var tokenEmitted = new AtomicBoolean(false);
                Consumer<String> trackingToken = token -> {
                    tokenEmitted.set(true);
                    onToken.accept(token);
                };
                resolved.dispatchable().dispatchLocalStreaming(requestBody, trackingToken, () -> {});
                if (tokenEmitted.get()) {
                    onComplete.run();
                    return;
                }
                logger.debug("Local streaming to '{}' produced no tokens, " +
                        "falling back to send", agentName);
            }
        } catch (Exception e) {
            logger.debug("Local streaming to '{}' failed, falling back to send: {}",
                    agentName, e.getMessage());
        }
        // Graceful fallback to synchronous
        var result = send(agentName, skill, args);
        onToken.accept(result.text());
        onComplete.run();
    }

    @Override
    public boolean isAvailable() {
        // Re-query on every call: an agent bean that registered its handler
        // after this transport was constructed becomes available the moment
        // the framework map records it, with no restart required.
        return resolve().dispatchable() != null;
    }
}
