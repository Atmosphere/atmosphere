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
package org.atmosphere.ai.batch;

import org.atmosphere.ai.AiConversationMemory;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * {@link AtmosphereHandler} serving Atmosphere's durable batch job API.
 * Registered (only when {@code atmosphere.ai.batch.enabled=true}) at
 * {@link BatchServing#BATCHES_PATH} by {@link BatchServingRegistrar}; the
 * framework's endpoint mapper routes the sub-paths here through its
 * parent-path fallback.
 *
 * <ul>
 *   <li>{@code POST /atmosphere/v1/batches} — submit a job (202 + job envelope)</li>
 *   <li>{@code GET  /atmosphere/v1/batches} — list recent jobs</li>
 *   <li>{@code GET  /atmosphere/v1/batches/{id}} — poll one job</li>
 *   <li>{@code GET  /atmosphere/v1/batches/{id}/results} — per-item results
 *       (partial while the job runs)</li>
 *   <li>{@code POST /atmosphere/v1/batches/{id}/cancel} — cancel</li>
 * </ul>
 *
 * <p>Every item of a submitted job dispatches through
 * {@link AiPipeline#execute(String, String, org.atmosphere.ai.StreamingSession)}
 * inside {@link BatchExecutor}, so governance applies per item (Invariant #7).
 * Boundary posture: bodies are size-bounded, content types validated, parse
 * errors return a 400 envelope, over-capacity submissions a 429, unknown
 * agents / jobs a 404 (Invariants #3 and #4). Endpoint-level auth is the
 * optional static bearer key from {@link BatchServing#apiKey()} — checked on
 * every route, mutating or not; any framework-installed {@code AuthInterceptor}
 * additionally gates this handler like every other Atmosphere handler.</p>
 */
public final class BatchHandler implements AtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(BatchHandler.class);

    /** Request-body bound — batch bodies are bulk by nature (Invariant #3). */
    static final int MAX_BODY_CHARS = 1 << 22;

    /** Jobs returned by the listing route. */
    static final int LIST_LIMIT = 100;

    private static final Pattern JOB_ID = Pattern.compile("^[A-Za-z0-9\\-]{1,80}$");

    private final BatchServing serving;
    private final BatchExecutor executor;
    private final BatchJobStore store;
    private final Map<String, AgentBinding> agents = new ConcurrentHashMap<>();
    private final AtomicBoolean destroyed = new AtomicBoolean();

    /** A registered serving target: the pipeline plus its conversation memory. */
    record AgentBinding(AiPipeline pipeline, AiConversationMemory memory) {
    }

    /**
     * @param serving  parsed endpoint configuration
     * @param executor the job executor; ownership transfers to this handler —
     *                 {@link #destroy()} closes it (and then the store) when
     *                 the framework tears the handler down (Invariant #1)
     * @param store    the executor's job store, for the read routes
     */
    public BatchHandler(BatchServing serving, BatchExecutor executor, BatchJobStore store) {
        this.serving = serving;
        this.executor = executor;
        this.store = store;
    }

    /** The executor backing this surface (programmatic consumers poll it). */
    public BatchExecutor executor() {
        return executor;
    }

    /**
     * Register an agent / endpoint pipeline under the given serving name.
     * First registration wins on name collision (a warning is logged) — the
     * same rule as the OpenAI-compatible surface. The memory (when present)
     * lets the executor clear each item's per-item conversation key after
     * the dispatch.
     */
    public void register(String name, AiPipeline pipeline, AiConversationMemory memory) {
        if (name == null || name.isBlank() || pipeline == null) {
            return;
        }
        var existing = agents.putIfAbsent(name, new AgentBinding(pipeline, memory));
        if (existing != null && existing.pipeline() != pipeline) {
            logger.warn("Batch serving name '{}' already registered — keeping the first "
                    + "registration (rename one agent)", name);
        }
    }

    /** Names currently registered (runtime truth, Invariant #5). */
    public Set<String> registeredAgents() {
        return Set.copyOf(agents.keySet());
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        var request = resource.getRequest();
        var response = resource.getResponse();
        try {
            authorize(request);
            var method = request.getMethod();
            var route = route(request.getRequestURI());
            switch (route.kind()) {
                case ROOT -> {
                    if ("POST".equalsIgnoreCase(method)) {
                        submit(request, response);
                    } else if ("GET".equalsIgnoreCase(method)) {
                        writeJson(response, 200, BatchWire.jobsJson(store.jobs(LIST_LIMIT)));
                    } else {
                        throw BatchError.methodNotAllowed();
                    }
                }
                case JOB -> {
                    if (!"GET".equalsIgnoreCase(method)) {
                        throw BatchError.methodNotAllowed();
                    }
                    var job = store.job(route.jobId())
                            .orElseThrow(() -> BatchError.jobNotFound(route.jobId()));
                    writeJson(response, 200, BatchWire.jobJson(job));
                }
                case RESULTS -> {
                    if (!"GET".equalsIgnoreCase(method)) {
                        throw BatchError.methodNotAllowed();
                    }
                    store.job(route.jobId())
                            .orElseThrow(() -> BatchError.jobNotFound(route.jobId()));
                    writeJson(response, 200,
                            BatchWire.resultsJson(route.jobId(), store.items(route.jobId())));
                }
                case CANCEL -> {
                    if (!"POST".equalsIgnoreCase(method)) {
                        throw BatchError.methodNotAllowed();
                    }
                    cancel(route.jobId(), response);
                }
            }
        } catch (BatchError e) {
            writeJson(response, e.status(), BatchWire.errorJson(e));
        } catch (RuntimeException e) {
            logger.error("Batch endpoint request failed", e);
            writeJson(response, 500, BatchWire.errorJson(
                    BatchError.serverError("The server had an unexpected error.")));
        }
    }

    private void submit(AtmosphereRequest request, AtmosphereResponse response)
            throws IOException {
        requireJsonContentType(request);
        var submission = BatchWire.parse(readBody(request), serving.maxItemsPerJob());
        var binding = agents.get(submission.agent());
        if (binding == null) {
            throw BatchError.agentNotFound(submission.agent());
        }
        BatchJob job;
        try {
            job = executor.submit(submission.agent(), binding.pipeline(), binding.memory(),
                    submission.items(), submission.submitter());
        } catch (RejectedExecutionException e) {
            throw BatchError.overCapacity(e.getMessage());
        }
        writeJson(response, 202, BatchWire.jobJson(job));
    }

    private void cancel(String jobId, AtmosphereResponse response) throws IOException {
        var job = store.job(jobId).orElseThrow(() -> BatchError.jobNotFound(jobId));
        if (job.status().terminal()) {
            throw BatchError.conflict("Batch job '" + jobId + "' is already "
                    + job.status().wire() + " and cannot be cancelled.");
        }
        var cancelled = executor.cancel(jobId)
                .orElseThrow(() -> BatchError.jobNotFound(jobId));
        writeJson(response, 200, BatchWire.jobJson(cancelled));
    }

    // ── Routing ────────────────────────────────────────────────────────────

    private enum RouteKind { ROOT, JOB, RESULTS, CANCEL }

    private record Route(RouteKind kind, String jobId) {
    }

    /**
     * Resolve the request URI to a batch route; the job-id segment is
     * strictly validated before it is interpreted (Invariant #4).
     *
     * @throws BatchError 400 for a malformed id, 404 for an unknown sub-path
     */
    private static Route route(String uri) {
        var path = uri != null ? uri : "";
        var idx = path.indexOf(BatchServing.BATCHES_PATH);
        var remainder = idx >= 0 ? path.substring(idx + BatchServing.BATCHES_PATH.length()) : "";
        while (remainder.endsWith("/")) {
            remainder = remainder.substring(0, remainder.length() - 1);
        }
        if (remainder.isEmpty()) {
            return new Route(RouteKind.ROOT, null);
        }
        if (!remainder.startsWith("/")) {
            throw BatchError.jobNotFound(remainder);
        }
        var segments = remainder.substring(1).split("/", -1);
        var jobId = segments[0];
        if (!JOB_ID.matcher(jobId).matches()) {
            throw BatchError.invalidRequest("Malformed batch job id.");
        }
        if (segments.length == 1) {
            return new Route(RouteKind.JOB, jobId);
        }
        if (segments.length == 2) {
            switch (segments[1]) {
                case "results" -> {
                    return new Route(RouteKind.RESULTS, jobId);
                }
                case "cancel" -> {
                    return new Route(RouteKind.CANCEL, jobId);
                }
                default -> throw BatchError.invalidRequest(
                        "Unknown batch action '" + segments[1] + "'.");
            }
        }
        throw BatchError.invalidRequest("Malformed batch path.");
    }

    // ── Boundary helpers (same posture as the OpenAI-compatible surface) ──

    private void authorize(AtmosphereRequest request) {
        var expected = serving.apiKey();
        if (expected == null) {
            return;
        }
        var header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw BatchError.unauthorized();
        }
        var presented = header.substring(7).strip();
        if (!MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            throw BatchError.unauthorized();
        }
    }

    private static void requireJsonContentType(AtmosphereRequest request) {
        var contentType = request.getContentType();
        if (contentType == null
                || !contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
            throw BatchError.unsupportedMediaType();
        }
    }

    private static String readBody(AtmosphereRequest request) throws IOException {
        var reader = request.getReader();
        var body = new StringBuilder();
        var buffer = new char[8192];
        int total = 0;
        int read;
        while ((read = reader.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BODY_CHARS) {
                throw BatchError.payloadTooLarge(MAX_BODY_CHARS);
            }
            body.append(buffer, 0, read);
        }
        if (body.isEmpty()) {
            throw BatchError.invalidRequest("Request body must not be empty.");
        }
        return body.toString();
    }

    private static void writeJson(AtmosphereResponse response, int status, String json)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json);
        response.getWriter().flush();
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        // Request/response handler: every route answers inside onRequest, so
        // there is no broadcast state to relay.
        if (event.isCancelled() || event.isClosedByClient()) {
            logger.debug("Batch endpoint connection closed: {}", event.getResource().uuid());
        }
    }

    /**
     * Framework teardown: stop the executor (interrupting in-flight jobs,
     * which are failed with a clear status) and close the store the registrar
     * created for this handler. Idempotent (Invariant #2).
     */
    @Override
    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        agents.clear();
        try {
            executor.close();
        } finally {
            store.close();
        }
    }
}
