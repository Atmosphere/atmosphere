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
package org.atmosphere.ai.openai;

import org.atmosphere.ai.AiBudgetExceededException;
import org.atmosphere.ai.AiConversationMemory;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link AtmosphereHandler} serving registered agent pipelines over the
 * OpenAI chat-completions wire format. Registered (only when
 * {@code atmosphere.ai.openai.enabled=true}) at
 * {@link OpenAiServing#CHAT_COMPLETIONS_PATH} and
 * {@link OpenAiServing#MODELS_PATH} by {@link OpenAiServingRegistrar}.
 *
 * <p>Every completion dispatches through
 * {@link AiPipeline#execute(String, String, StreamingSession)} — the same
 * entry the channel / A2A / AG-UI bridges use — so admission governance,
 * guardrails, budget enforcement, and cost accounting apply identically on
 * this surface (Correctness Invariant #7, Mode Parity). Client-supplied
 * prior turns are staged into the pipeline's conversation memory under a
 * per-request key that is always cleared afterwards; client {@code system}
 * messages become system-role history and can never replace the agent's own
 * (scope-hardened) system prompt.</p>
 *
 * <p>Boundary posture: bodies are size-bounded, content types validated,
 * parse errors return a 400 OpenAI error envelope, unknown models 404, and
 * concurrent completions are capped with a 503 on overflow (Invariants #3
 * and #4). Endpoint-level auth is the optional static bearer key from
 * {@link OpenAiServing#apiKey()}; any framework-installed
 * {@code AuthInterceptor} additionally gates this handler like every other
 * Atmosphere handler.</p>
 */
public final class OpenAiChatHandler implements AtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiChatHandler.class);

    /** Request-body bound — far above any legitimate chat payload (Invariant #3). */
    static final int MAX_BODY_CHARS = 1 << 20;

    /** Concurrent-completion cap; overflow answers 503 instead of queueing unbounded. */
    static final int MAX_CONCURRENT_COMPLETIONS = 64;

    /** How long a dispatched completion may take before the request errors out. */
    private static final long COMPLETION_TIMEOUT_MS = 120_000L;

    private final OpenAiServing serving;
    private final Map<String, AgentBinding> agents = new ConcurrentHashMap<>();
    private final Semaphore inFlight = new Semaphore(MAX_CONCURRENT_COMPLETIONS);

    /** A registered serving target: the pipeline plus its conversation memory. */
    record AgentBinding(AiPipeline pipeline, AiConversationMemory memory) {
    }

    public OpenAiChatHandler(OpenAiServing serving) {
        this.serving = serving;
    }

    /**
     * Register an agent / endpoint pipeline under the given serving name.
     * First registration wins on name collision (a warning is logged).
     */
    public void register(String name, AiPipeline pipeline, AiConversationMemory memory) {
        if (name == null || name.isBlank() || pipeline == null) {
            return;
        }
        var existing = agents.putIfAbsent(name, new AgentBinding(pipeline, memory));
        if (existing != null && existing.pipeline() != pipeline) {
            logger.warn("OpenAI serving name '{}' already registered — keeping the first "
                    + "registration (rename one agent or add an explicit "
                    + "atmosphere.ai.openai.models.* mapping)", name);
        }
    }

    /** Names currently registered (runtime truth for the models listing). */
    public Set<String> registeredAgents() {
        return Set.copyOf(agents.keySet());
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        var request = resource.getRequest();
        var response = resource.getResponse();
        try {
            authorize(request);
            var uri = request.getRequestURI();
            if (uri != null && uri.endsWith("/models")) {
                if (!"GET".equalsIgnoreCase(request.getMethod())) {
                    throw OpenAiError.methodNotAllowed();
                }
                writeJson(response, 200,
                        OpenAiWire.modelsJson(servedModelNames(), Instant.now().getEpochSecond()));
                return;
            }
            if (!"POST".equalsIgnoreCase(request.getMethod())) {
                throw OpenAiError.methodNotAllowed();
            }
            requireJsonContentType(request);
            var inbound = OpenAiWire.parse(readBody(request));
            var targetName = resolveTarget(inbound.model(), serving, agents.keySet());
            var binding = agents.get(targetName);
            if (binding == null) {
                throw OpenAiError.unavailable("The model '"
                        + (inbound.model() != null ? inbound.model() : targetName)
                        + "' maps to agent '" + targetName + "', which is not available.");
            }
            if (!inFlight.tryAcquire()) {
                throw OpenAiError.unavailable(
                        "The server is handling the maximum number of concurrent completions.");
            }
            try {
                dispatch(resource, inbound, targetName, binding);
            } finally {
                inFlight.release();
            }
        } catch (OpenAiError e) {
            writeJson(response, e.status(), OpenAiWire.errorJson(e));
        } catch (RuntimeException e) {
            logger.error("OpenAI-compatible endpoint request failed", e);
            writeJson(response, 500, OpenAiWire.errorJson(
                    OpenAiError.serverError("The server had an unexpected error.")));
        }
    }

    /**
     * Resolve an inbound {@code model} value to a registered serving name.
     * A non-empty configured map acts as a whitelist; without a map the model
     * must exactly match a registered agent name; the configured default
     * agent (when set) catches everything else. Package-private and static
     * for direct unit testing.
     *
     * @throws OpenAiError 400 when the model is absent and no default is
     *         configured, 404 when the model cannot be resolved
     */
    static String resolveTarget(String model, OpenAiServing serving, Set<String> registered) {
        var requested = model == null ? "" : model.strip();
        if (requested.isEmpty()) {
            if (serving.defaultAgent() != null) {
                return serving.defaultAgent();
            }
            throw OpenAiError.invalidRequest("'model' is required.", "model");
        }
        if (!serving.models().isEmpty()) {
            var mapped = serving.models().get(requested);
            if (mapped != null) {
                return mapped;
            }
        } else if (registered.contains(requested)) {
            return requested;
        }
        if (serving.defaultAgent() != null) {
            return serving.defaultAgent();
        }
        throw OpenAiError.modelNotFound(requested);
    }

    private void dispatch(AtmosphereResource resource, OpenAiWire.InboundChatCompletion inbound,
                          String targetName, AgentBinding binding) throws IOException {
        var completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        var created = Instant.now().getEpochSecond();
        var echoModel = inbound.model() != null ? inbound.model() : targetName;
        var conversationKey = conversationKey(inbound.user());
        var memory = binding.memory();
        try {
            if (memory != null) {
                for (var turn : inbound.priorTurns()) {
                    memory.addMessage(conversationKey, turn);
                }
            } else if (!inbound.priorTurns().isEmpty()) {
                logger.debug("Agent '{}' has no conversation memory — {} prior turn(s) from the "
                        + "OpenAI request are not threaded into the completion",
                        targetName, inbound.priorTurns().size());
            }
            if (inbound.stream()) {
                dispatchStreaming(resource, inbound, binding.pipeline(), conversationKey,
                        completionId, created, echoModel);
            } else {
                dispatchBlocking(resource, inbound, binding.pipeline(), conversationKey,
                        completionId, created, echoModel);
            }
        } finally {
            if (memory != null) {
                memory.clear(conversationKey);
            }
        }
    }

    private void dispatchBlocking(AtmosphereResource resource,
                                  OpenAiWire.InboundChatCompletion inbound, AiPipeline pipeline,
                                  String conversationKey, String completionId, long created,
                                  String echoModel) throws IOException {
        var session = new CollectingCompletionSession();
        try {
            pipeline.execute(conversationKey, inbound.userMessage(), session);
        } catch (RuntimeException e) {
            logger.error("OpenAI-compatible completion dispatch failed (agent key {})",
                    conversationKey, e);
            throw failureToError(e);
        }
        if (!session.await(COMPLETION_TIMEOUT_MS)) {
            session.abandon();
            throw OpenAiError.serverError("The completion did not finish in time.");
        }
        var failure = session.failure();
        if (failure != null) {
            throw failureToError(failure);
        }
        writeJson(resource.getResponse(), 200, OpenAiWire.completionJson(
                completionId, created, echoModel, session.text(), session.usage()));
    }

    private void dispatchStreaming(AtmosphereResource resource,
                                   OpenAiWire.InboundChatCompletion inbound, AiPipeline pipeline,
                                   String conversationKey, String completionId, long created,
                                   String echoModel) throws IOException {
        var servletResponse = (jakarta.servlet.http.HttpServletResponse)
                resource.getResponse().getResponse();
        var writer = new SseChunkWriter(servletResponse, completionId, created, echoModel);
        var session = new StreamingCompletionSession(writer);
        try {
            pipeline.execute(conversationKey, inbound.userMessage(), session);
        } catch (RuntimeException e) {
            logger.error("OpenAI-compatible streaming dispatch failed (agent key {})",
                    conversationKey, e);
            session.error(e);
        }
        var finished = session.await(COMPLETION_TIMEOUT_MS);
        if (!finished) {
            // Late runtime writes must not land after the handler's terminal
            // frames; timeout messaging stays distinct from runtime failures.
            session.abandon();
        }
        var failure = finished ? session.failure() : null;
        if (!writer.started()) {
            // Nothing was streamed yet — the response is uncommitted, so a
            // pre-stream failure surfaces as a proper HTTP error envelope
            // exactly like the non-streaming mode (Mode Parity #7).
            if (failure != null) {
                throw failureToError(failure);
            }
            if (!finished) {
                throw OpenAiError.serverError("The completion did not finish in time.");
            }
        }
        if (failure != null || !finished) {
            var error = failure != null
                    ? failureToError(failure)
                    : OpenAiError.serverError("The completion did not finish in time.");
            writer.writeError(error);
            writer.writeDone();
            return;
        }
        writer.ensureStarted();
        writer.writeFinish("stop");
        var usage = session.usage();
        if (usage != null && usage.hasCounts()) {
            writer.writeUsage(usage);
        }
        writer.writeDone();
    }

    /**
     * Map a pipeline-reported failure to the OpenAI error envelope. Guardrail
     * and governance denials arrive as {@link SecurityException} (403), budget
     * trips as {@link AiBudgetExceededException} (429); anything else is a
     * generic 500 whose details stay in the server log.
     */
    private static OpenAiError failureToError(Throwable failure) {
        if (failure instanceof OpenAiError openAiError) {
            return openAiError;
        }
        if (failure instanceof SecurityException) {
            return OpenAiError.requestBlocked(failure.getMessage() != null
                    ? failure.getMessage() : "Request blocked by policy.");
        }
        if (failure instanceof AiBudgetExceededException) {
            return OpenAiError.budgetExceeded(failure.getMessage() != null
                    ? failure.getMessage() : "The configured AI budget was exceeded.");
        }
        return OpenAiError.serverError("The completion failed with an internal error.");
    }

    private void authorize(AtmosphereRequest request) {
        var expected = serving.apiKey();
        if (expected == null) {
            return;
        }
        var header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw OpenAiError.unauthorized();
        }
        var presented = header.substring(7).strip();
        if (!MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            throw OpenAiError.unauthorized();
        }
    }

    private static void requireJsonContentType(AtmosphereRequest request) {
        var contentType = request.getContentType();
        if (contentType == null
                || !contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
            throw OpenAiError.unsupportedMediaType();
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
                throw OpenAiError.payloadTooLarge(MAX_BODY_CHARS);
            }
            body.append(buffer, 0, read);
        }
        if (body.isEmpty()) {
            throw OpenAiError.invalidRequest("Request body must not be empty.");
        }
        return body.toString();
    }

    /**
     * The model names a client may send: the configured mapping's names when a
     * mapping exists (it acts as a whitelist), otherwise the registered agent
     * names — runtime-resolved, never configuration intent (Invariant #5).
     */
    private List<String> servedModelNames() {
        return serving.models().isEmpty()
                ? agents.keySet().stream().sorted().toList()
                : serving.models().keySet().stream().sorted().toList();
    }

    private static String conversationKey(String user) {
        var suffix = UUID.randomUUID().toString();
        return user != null && !user.isBlank()
                ? "openai:" + user + ":" + suffix
                : "openai:" + suffix;
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
        // Request/response handler: completions are answered inside
        // onRequest, so there is no broadcast state to relay.
        if (event.isCancelled() || event.isClosedByClient()) {
            logger.debug("OpenAI-compatible connection closed: {}", event.getResource().uuid());
        }
    }

    @Override
    public void destroy() {
        // Registered at two paths — destroy() fires once per registration and
        // must stay idempotent (Invariant #2).
        agents.clear();
    }

    /**
     * Shared terminal plumbing for the two completion sessions: single
     * terminal transition, usage capture (typed {@link TokenUsage} event
     * first, legacy {@code ai.tokens.*} metadata as fallback), and an
     * awaitable completion latch. Success, failure, cancel, and timeout all
     * resolve the latch exactly once (Invariant #2).
     */
    private abstract static class AbstractCompletionSession implements StreamingSession {

        private final String id = UUID.randomUUID().toString();
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<TokenUsage> typedUsage = new AtomicReference<>();
        private final AtomicLong metadataInput = new AtomicLong();
        private final AtomicLong metadataOutput = new AtomicLong();
        private final AtomicLong metadataTotal = new AtomicLong();

        @Override
        public String sessionId() {
            return id;
        }

        @Override
        public void usage(TokenUsage usage) {
            if (usage != null && usage.hasCounts()) {
                typedUsage.set(usage);
            }
        }

        @Override
        public void sendMetadata(String key, Object value) {
            if (value instanceof Number number) {
                switch (key) {
                    case "ai.tokens.input" -> metadataInput.set(number.longValue());
                    case "ai.tokens.output" -> metadataOutput.set(number.longValue());
                    case "ai.tokens.total" -> metadataTotal.set(number.longValue());
                    default -> {
                        // Wire-internal metadata has no OpenAI representation.
                    }
                }
            }
        }

        @Override
        public void progress(String message) {
            // Progress frames have no representation on the OpenAI wire.
        }

        @Override
        public void complete() {
            complete(null);
        }

        @Override
        public void complete(String summary) {
            if (closed.compareAndSet(false, true)) {
                onCompleted(summary);
                done.countDown();
            }
        }

        @Override
        public void error(Throwable t) {
            if (closed.compareAndSet(false, true)) {
                failure.set(t != null ? t : new IllegalStateException("Unknown stream error"));
                done.countDown();
            }
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public boolean hasErrored() {
            return failure.get() != null;
        }

        /** Invoked exactly once on the first successful terminal transition. */
        abstract void onCompleted(String summary);

        boolean await(long timeoutMs) {
            try {
                return done.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        /**
         * Force the session closed after an await timeout so a late-running
         * runtime can no longer emit into a response the handler has already
         * finalized (Invariant #2 — the handler's error frame / [DONE] is the
         * terminal write). Subsequent send/complete/error calls become no-ops.
         */
        void abandon() {
            if (closed.compareAndSet(false, true)) {
                failure.compareAndSet(null,
                        new IllegalStateException("Completion abandoned after timeout"));
                done.countDown();
            }
        }

        Throwable failure() {
            return failure.get();
        }

        /** Typed usage when reported, else the legacy metadata counters, else {@code null}. */
        TokenUsage usage() {
            var typed = typedUsage.get();
            if (typed != null) {
                return typed;
            }
            var fallback = TokenUsage.fromCounts(metadataInput.get(), metadataOutput.get(),
                    null, metadataTotal.get() > 0 ? metadataTotal.get() : null);
            return fallback.hasCounts() ? fallback : null;
        }
    }

    /** Buffers the full response text for the non-streaming mode. */
    static final class CollectingCompletionSession extends AbstractCompletionSession {

        private final StringBuilder text = new StringBuilder();
        private final ReentrantLock lock = new ReentrantLock();

        @Override
        public void send(String chunk) {
            if (chunk == null || isClosed()) {
                return;
            }
            lock.lock();
            try {
                text.append(chunk);
            } finally {
                lock.unlock();
            }
        }

        @Override
        void onCompleted(String summary) {
            if (summary != null && !summary.isBlank()) {
                lock.lock();
                try {
                    text.setLength(0);
                    text.append(summary);
                } finally {
                    lock.unlock();
                }
            }
        }

        String text() {
            lock.lock();
            try {
                return text.toString();
            } finally {
                lock.unlock();
            }
        }
    }

    /** Maps streaming events onto {@code chat.completion.chunk} SSE frames. */
    static final class StreamingCompletionSession extends AbstractCompletionSession {

        private final SseChunkWriter writer;
        private final AtomicLong streamedChars = new AtomicLong();

        StreamingCompletionSession(SseChunkWriter writer) {
            this.writer = writer;
        }

        @Override
        public void send(String chunk) {
            if (chunk == null || chunk.isEmpty() || isClosed()) {
                return;
            }
            streamedChars.addAndGet(chunk.length());
            writer.writeContent(chunk);
        }

        @Override
        void onCompleted(String summary) {
            // A runtime that only reports the aggregated summary (never
            // streamed a delta) still yields exactly one content chunk.
            if (streamedChars.get() == 0 && summary != null && !summary.isBlank()) {
                writer.writeContent(summary);
            }
        }
    }

    /**
     * Serializes {@code chat.completion.chunk} SSE frames onto the raw servlet
     * response. Headers are committed lazily on the first frame so pre-stream
     * failures can still answer with a plain HTTP error envelope.
     */
    static final class SseChunkWriter {

        private final jakarta.servlet.http.HttpServletResponse response;
        private final ReentrantLock lock = new ReentrantLock();
        private final String completionId;
        private final long created;
        private final String model;
        private PrintWriter writer;
        private volatile boolean started;

        SseChunkWriter(jakarta.servlet.http.HttpServletResponse response,
                       String completionId, long created, String model) {
            this.response = response;
            this.completionId = completionId;
            this.created = created;
            this.model = model;
        }

        boolean started() {
            return started;
        }

        /** Commit SSE headers and emit the leading role delta exactly once. */
        void ensureStarted() {
            lock.lock();
            try {
                startLocked();
            } finally {
                lock.unlock();
            }
        }

        void writeContent(String text) {
            lock.lock();
            try {
                startLocked();
                frameLocked(OpenAiWire.chunkJson(completionId, created, model,
                        Map.of("content", text), null));
            } finally {
                lock.unlock();
            }
        }

        void writeFinish(String reason) {
            lock.lock();
            try {
                startLocked();
                frameLocked(OpenAiWire.chunkJson(completionId, created, model,
                        Map.of(), reason));
            } finally {
                lock.unlock();
            }
        }

        void writeUsage(TokenUsage usage) {
            lock.lock();
            try {
                startLocked();
                frameLocked(OpenAiWire.usageChunkJson(completionId, created, model, usage));
            } finally {
                lock.unlock();
            }
        }

        void writeError(OpenAiError error) {
            lock.lock();
            try {
                startLocked();
                frameLocked(OpenAiWire.errorJson(error));
            } finally {
                lock.unlock();
            }
        }

        void writeDone() {
            lock.lock();
            try {
                startLocked();
                writer.write("data: [DONE]\n\n");
                writer.flush();
            } finally {
                lock.unlock();
            }
        }

        private void startLocked() {
            if (started) {
                return;
            }
            try {
                response.setContentType("text/event-stream");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-cache");
                response.setHeader("Connection", "keep-alive");
                response.setHeader("X-Accel-Buffering", "no");
                writer = response.getWriter();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            started = true;
            var role = new LinkedHashMap<String, Object>();
            role.put("role", "assistant");
            role.put("content", "");
            frameLocked(OpenAiWire.chunkJson(completionId, created, model, role, null));
        }

        private void frameLocked(String json) {
            writer.write("data: " + json + "\n\n");
            writer.flush();
        }
    }
}
