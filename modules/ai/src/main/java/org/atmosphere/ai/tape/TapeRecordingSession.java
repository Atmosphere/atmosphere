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
package org.atmosphere.ai.tape;

import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.DelegatingStreamingSession;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Record-then-forward {@link StreamingSession} decorator that appends every
 * event crossing the session boundary to the installed tape. Fidelity
 * contract: "As-produced at the session boundary, post-decorator." — on
 * disconnect the leaf drops late writes while this decorator (above the
 * leaf) keeps recording until the cancel signal lands, so a
 * {@link TapeStatus#CANCELLED} run may hold trailing produced-but-undelivered
 * steps.
 *
 * <h2>Step semantics</h2>
 * <ul>
 *   <li>Text coalescing: {@code send(String)} and {@code emit(TextDelta)}
 *       append to a bounded per-run accumulator, flushed as one {@code text}
 *       step on a semantic boundary (tool / agent / handoff / entity-start /
 *       routing / plan / approval events — progress and metadata are NOT
 *       boundaries), on a terminal, on the size cap ({@code truncated} flag
 *       in the segment payload), or when the writer tick sees the accumulator
 *       older than the flush interval. TEXT steps are SEGMENTS of one logical
 *       output; consumers must not treat fragment count as semantic.</li>
 *   <li>{@code emit(TextComplete)} subsumes the accumulator (clear, one
 *       {@code text} step carrying the full text). A {@code send(text)} equal
 *       to the ENTIRE current accumulator content (length-gated compare) is
 *       subsumed the same way — the Embabel deployed path streams deltas and
 *       may re-send the full result after.</li>
 *   <li>Typed events record one step each, {@code kind = eventType()}.
 *       {@code sendMetadata} records a {@code metadata} step, except keys on
 *       the skip-list ({@code ai.toolCall.delta.*}). Typed
 *       {@code usage()} / {@code confidence()} are NORMALIZED into the same
 *       {@code ai.tokens.*} / {@code ai.confidence.*} metadata steps the
 *       endpoint chain produces (mode parity), then forwarded typed and
 *       unchanged; typed {@code toolCallDelta()} is skipped like its
 *       metadata form.</li>
 *   <li>{@code sendContent} records a descriptor only —
 *       {@code {contentType, mimeType, fileName?, byteLength, sha256}} —
 *       never raw bytes.</li>
 *   <li>Terminals — {@code complete()}, {@code complete(String)},
 *       {@code error(Throwable)}, {@code emit(Complete)}, {@code emit(Error)}
 *       — flush the accumulator, record a terminal step, and signal the
 *       write-once status through the recorder's non-droppable control slot.
 *       The first terminal wins; later terminals are counted, never a status
 *       flip.</li>
 *   <li>Unserializable payloads become a
 *       {@code {"_unserializable":cls,"_error":msg}} placeholder, counted;
 *       recording never throws into the live stream (Invariant #4).</li>
 * </ul>
 *
 * <h2>Run identity</h2>
 *
 * Endpoint-path runs are late-bound: steps recorded before
 * {@link #bindRun(String, String)} buffer under a minted provisional id and
 * are re-keyed on bind (in practice only the {@code X-Atmosphere-Run-Id}
 * metadata frame from {@code setRunId} precedes the bind). Pipeline and
 * crash-resume runs are bound at wrap time.
 *
 * <h2>Concurrency</h2>
 *
 * The per-run monitor guards ONLY accumulator mutation, the pre-bind buffer,
 * and the queue offer — it is NEVER held across store calls or delegate
 * forwarding.
 */
public final class TapeRecordingSession extends DelegatingStreamingSession {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Metadata keys skipped by default: per-token tool-argument fragments. */
    private static final String TOOL_CALL_DELTA_PREFIX = "ai.toolCall.delta.";

    /**
     * Bound on steps buffered before {@link #bindRun}. The endpoint handler
     * binds before {@code @Prompt} dispatch, so in practice at most the
     * run-id metadata frame buffers here; the cap only guards a producer that
     * streams before its identity exists.
     */
    private static final int PRE_BIND_CAP = 256;

    private record PendingStep(String kind, String payload, long ts) {
    }

    private final TapeRecorder recorder;
    private final TapeRecorder.OpenRun run;
    private final int maxTextChars;
    private final AtomicReference<TapeStatus> localTerminal = new AtomicReference<>();
    private final Object lock = new Object();
    private final StringBuilder accumulator = new StringBuilder();
    private final List<PendingStep> preBind = new ArrayList<>();
    private long accumulatedSinceNanos;
    private boolean bound;

    TapeRecordingSession(TapeRecorder recorder, StreamingSession delegate, TapeRunInfo info) {
        super(delegate);
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        Objects.requireNonNull(info, "info");
        this.maxTextChars = recorder.config().maxTextChars();
        this.bound = !info.lateBound();
        var initialRunId = info.runId() != null ? info.runId() : "tape-" + UUID.randomUUID();
        this.run = new TapeRecorder.OpenRun(initialRunId, info, delegate.sessionId());
        recorder.track(run, this);
        if (info.resumed()) {
            record("resumed", payload(), null);
        }
    }

    /** The tape run id this session records under (provisional until {@link #bindRun}). */
    public String tapeRunId() {
        return run.runId;
    }

    /**
     * Late-bind the run identity minted by the RunRegistry. Steps buffered
     * before the bind are re-keyed under the bound id and released to the
     * writer. No-op when the run is already bound (pipeline / crash-resume
     * wraps, or a terminal that self-bound first).
     */
    public void bindRun(String runId, String userId) {
        Objects.requireNonNull(runId, "runId");
        synchronized (lock) {
            if (bound) {
                return;
            }
            run.runId = runId;
            if (userId != null) {
                run.userId = userId;
            }
            bound = true;
            drainPreBindLocked();
        }
    }

    @Override
    public void send(String text) {
        accumulate(text);
        super.send(text);
    }

    @Override
    public void sendMetadata(String key, Object value) {
        if (key == null || !key.startsWith(TOOL_CALL_DELTA_PREFIX)) {
            record("metadata", payload("key", key, "value", value), value);
        }
        super.sendMetadata(key, value);
    }

    @Override
    public void usage(TokenUsage usage) {
        if (usage != null) {
            // MODE PARITY: normalize the typed record into the SAME ai.tokens.*
            // metadata steps the endpoint chain produces when the interface
            // default converts usage() into sendMetadata calls one hop above
            // this decorator (StreamingSession.usage mapping).
            if (usage.input() > 0) {
                recordMetadata("ai.tokens.input", usage.input());
            }
            if (usage.output() > 0) {
                recordMetadata("ai.tokens.output", usage.output());
            }
            if (usage.cachedInput() > 0) {
                recordMetadata("ai.tokens.cached_input", usage.cachedInput());
            }
            if (usage.total() > 0) {
                recordMetadata("ai.tokens.total", usage.total());
            }
            if (usage.model() != null && !usage.model().isBlank()) {
                recordMetadata("ai.tokens.model", usage.model());
            }
        }
        // Forward TYPED and unchanged — normalization is tape-local.
        super.usage(usage);
    }

    @Override
    public void confidence(AiConfidence confidence) {
        if (confidence != null) {
            // Same parity normalization as usage(): mirror the interface
            // default's ai.confidence.* metadata mapping.
            if (confidence.aggregate().isPresent()) {
                recordMetadata(AiConfidence.AGGREGATE_METADATA_KEY,
                        confidence.aggregate().getAsDouble());
            }
            recordMetadata(AiConfidence.SOURCE_METADATA_KEY, confidence.source().name());
            if (!confidence.tokens().isEmpty()) {
                recordMetadata(AiConfidence.TOKENS_METADATA_KEY, confidence.tokens().size());
            }
        }
        super.confidence(confidence);
    }

    @Override
    public void toolCallDelta(String toolCallId, String argsChunk) {
        // Skipped by design: the endpoint form (ai.toolCall.delta.<id>
        // metadata) is on the skip-list; recording the typed form here would
        // break mode parity and flood the tape with per-token fragments.
        super.toolCallDelta(toolCallId, argsChunk);
    }

    @Override
    public void progress(String message) {
        record("progress", payload("message", message), message);
        super.progress(message);
    }

    @Override
    public void sendContent(Content content) {
        if (content != null) {
            record("content", contentDescriptor(content), content);
        }
        super.sendContent(content);
    }

    @Override
    public void complete() {
        recordTerminal(TapeStatus.COMPLETED, "complete", payload(), null);
        super.complete();
    }

    @Override
    public void complete(String summary) {
        recordTerminal(TapeStatus.COMPLETED, "complete", payload("summary", summary), summary);
        super.complete(summary);
    }

    @Override
    public void error(Throwable t) {
        String message = null;
        String type = null;
        if (t != null) {
            message = t.getMessage() != null ? t.getMessage() : t.toString();
            type = t.getClass().getName();
        }
        recordTerminal(TapeStatus.ERROR, "error", payload("message", message, "type", type), t);
        super.error(t);
    }

    @Override
    public void emit(AiEvent event) {
        // Exhaustive over the sealed AiEvent hierarchy — a new event type is a
        // compile error here, keeping capture completeness honest.
        switch (event) {
            case AiEvent.TextDelta delta -> accumulate(delta.text());
            case AiEvent.TextComplete tc -> subsumeText(tc.fullText());
            case AiEvent.Complete c -> recordTerminal(TapeStatus.COMPLETED, "complete",
                    payload("summary", c.summary(),
                            "usage", c.usage().isEmpty() ? null : c.usage()), c);
            case AiEvent.Error err -> recordTerminal(TapeStatus.ERROR, "error",
                    payload("message", err.message(), "code", err.code(),
                            "recoverable", err.recoverable()), err);
            case AiEvent.Progress p -> record(p.eventType(),
                    payload("message", p.message(), "percentage", p.percentage()), p);
            case AiEvent.StructuredField f -> record(f.eventType(),
                    payload("fieldName", f.fieldName(), "value", f.value(),
                            "schemaType", f.schemaType()), f);
            case AiEvent.EntityComplete ec -> record(ec.eventType(),
                    payload("typeName", ec.typeName(), "entity", ec.entity()), ec);
            case AiEvent.ToolStart toolStart -> recordBoundary(toolStart.eventType(),
                    payload("toolName", toolStart.toolName(),
                            "arguments", toolStart.arguments()), toolStart);
            case AiEvent.ToolResult toolResult -> recordBoundary(toolResult.eventType(),
                    payload("toolName", toolResult.toolName(),
                            "result", toolResult.result()), toolResult);
            case AiEvent.ToolError toolError -> recordBoundary(toolError.eventType(),
                    payload("toolName", toolError.toolName(),
                            "error", toolError.error()), toolError);
            case AiEvent.AgentStep step -> recordBoundary(step.eventType(),
                    payload("stepName", step.stepName(), "description", step.description(),
                            "data", step.data().isEmpty() ? null : step.data()), step);
            case AiEvent.Handoff handoff -> recordBoundary(handoff.eventType(),
                    payload("fromAgent", handoff.fromAgent(), "toAgent", handoff.toAgent(),
                            "reason", handoff.reason()), handoff);
            case AiEvent.EntityStart es -> recordBoundary(es.eventType(),
                    payload("typeName", es.typeName(), "jsonSchema", es.jsonSchema()), es);
            case AiEvent.RoutingDecision rd -> recordBoundary(rd.eventType(),
                    payload("fromBackend", rd.fromBackend(), "toBackend", rd.toBackend(),
                            "reason", rd.reason()), rd);
            case AiEvent.PlanUpdate plan -> recordBoundary(plan.eventType(),
                    payload("steps", plan.steps(), "goal", plan.goal(),
                            "conversationId", plan.conversationId(),
                            "agentId", plan.agentId()), plan);
            case AiEvent.ApprovalRequired approval -> recordBoundary(approval.eventType(),
                    payload("approvalId", approval.approvalId(), "toolName", approval.toolName(),
                            "arguments", approval.arguments(), "message", approval.message(),
                            "expiresIn", approval.expiresIn()), approval);
        }
        super.emit(event);
    }

    @Override
    public void close() {
        if (localTerminal.get() == null && !isClosed()) {
            // close() on a live session is a complete() — route it through the
            // terminal path so the tape reaches COMPLETED (Invariant #2).
            complete();
            return;
        }
        super.close();
    }

    // ------------------------------------------------------------------
    // Writer-tick seams (called by TapeRecorder)
    // ------------------------------------------------------------------

    /** Flush accumulated text older than the flush interval — writer tick. */
    void flushTextIfStale(Duration interval) {
        synchronized (lock) {
            if (!accumulator.isEmpty()
                    && System.nanoTime() - accumulatedSinceNanos >= interval.toNanos()) {
                flushTextLocked(false);
            }
        }
    }

    /**
     * Flush all pending state ahead of a recorder-initiated terminal
     * (disconnect cancel, idle sweep): self-bind an unbound run so its
     * pre-bind buffer is not lost, then flush the accumulator.
     */
    void terminalFlush() {
        synchronized (lock) {
            selfBindLocked();
            flushTextLocked(false);
        }
    }

    // ------------------------------------------------------------------
    // Recording internals
    // ------------------------------------------------------------------

    private void accumulate(String text) {
        if (text == null || text.isEmpty() || recorder.isClosed()) {
            return;
        }
        if (dropIfTerminal()) {
            return;
        }
        synchronized (lock) {
            if (!accumulator.isEmpty() && accumulator.length() == text.length()
                    && text.contentEquals(accumulator)) {
                // Embabel-style dedup: a send equal to the ENTIRE accumulated
                // content is subsumed like TextComplete (replace, not append).
                flushTextLocked(false);
                return;
            }
            if (accumulator.isEmpty()) {
                accumulatedSinceNanos = System.nanoTime();
            }
            accumulator.append(text);
            if (accumulator.length() >= maxTextChars) {
                // Size cap: force-flush the segment, marked truncated so
                // consumers can tell the fragmentation was size-driven.
                flushTextLocked(true);
            }
        }
    }

    private void subsumeText(String fullText) {
        if (recorder.isClosed() || dropIfTerminal()) {
            return;
        }
        var json = toJson(payload("text", fullText != null ? fullText : ""), fullText);
        synchronized (lock) {
            accumulator.setLength(0);
            offerLocked("text", json);
        }
    }

    private void record(String kind, Map<String, Object> payloadMap, Object source) {
        if (recorder.isClosed() || dropIfTerminal()) {
            return;
        }
        var json = toJson(payloadMap, source);
        synchronized (lock) {
            offerLocked(kind, json);
        }
    }

    private void recordBoundary(String kind, Map<String, Object> payloadMap, Object source) {
        if (recorder.isClosed() || dropIfTerminal()) {
            return;
        }
        var json = toJson(payloadMap, source);
        synchronized (lock) {
            // Semantic boundary: the pending text segment precedes the event.
            flushTextLocked(false);
            offerLocked(kind, json);
        }
    }

    private void recordTerminal(TapeStatus status, String kind,
                                Map<String, Object> payloadMap, Object source) {
        if (recorder.isClosed()) {
            return;
        }
        if (!localTerminal.compareAndSet(null, status)) {
            // Write-once: the first terminal won; count, never flip.
            recorder.countLateTerminal();
            return;
        }
        var json = toJson(payloadMap, source);
        synchronized (lock) {
            selfBindLocked();
            flushTextLocked(false);
            offerLocked(kind, json);
        }
        // Non-droppable control-slot signal — set AFTER the step offers so the
        // writer's pre-terminal drain observes them first.
        recorder.requestTerminal(run, status);
    }

    private void recordMetadata(String key, Object value) {
        record("metadata", payload("key", key, "value", value), value);
    }

    private boolean dropIfTerminal() {
        // requestedTerminal covers the disconnect / idle-sweep race: those set
        // the control slot (and flush the accumulator once) WITHOUT setting
        // localTerminal, so text arriving after would otherwise be appended to a
        // stranded accumulator the writer never flushes again. Drop-and-count it
        // here so trailing produced-but-undelivered text is accounted the same
        // way a discrete late step is (append-after-terminal in the writer).
        if (localTerminal.get() != null || run.terminalApplied
                || run.requestedTerminal.get() != null) {
            recorder.countDropped(run);
            return true;
        }
        return false;
    }

    /** Caller must hold {@code lock}. */
    private void flushTextLocked(boolean sizeCapped) {
        if (accumulator.isEmpty()) {
            return;
        }
        var text = accumulator.toString();
        accumulator.setLength(0);
        var map = payload("text", text);
        if (sizeCapped) {
            map.put("truncated", Boolean.TRUE);
        }
        // Serializing a plain string map here is pure CPU work — the monitor
        // is never held across store calls or delegate forwarding.
        offerLocked("text", toJson(map, text));
    }

    /** Caller must hold {@code lock}. */
    private void offerLocked(String kind, String json) {
        var ts = System.currentTimeMillis();
        if (!bound) {
            if (preBind.size() < PRE_BIND_CAP) {
                preBind.add(new PendingStep(kind, json, ts));
            } else {
                recorder.countDropped(run);
            }
            return;
        }
        recorder.enqueue(run, kind, json, ts);
    }

    /** Caller must hold {@code lock}. */
    private void selfBindLocked() {
        if (!bound) {
            // Terminal before bindRun: settle on the provisional id so the
            // buffered steps are not lost.
            bound = true;
            drainPreBindLocked();
        }
    }

    /** Caller must hold {@code lock}. */
    private void drainPreBindLocked() {
        for (var pending : preBind) {
            recorder.enqueue(run, pending.kind(), pending.payload(), pending.ts());
        }
        preBind.clear();
    }

    private Map<String, Object> contentDescriptor(Content content) {
        // DESCRIPTOR ONLY — mirror the StreamingSession binary breadcrumb
        // shape; NEVER raw bytes (the tape is not a blob store).
        return switch (content) {
            case Content.Text text -> {
                var bytes = text.text().getBytes(StandardCharsets.UTF_8);
                yield payload("contentType", "text",
                        "byteLength", bytes.length, "sha256", sha256(bytes));
            }
            case Content.Image image -> payload("contentType", "image",
                    "mimeType", image.mimeType(),
                    "byteLength", image.data().length, "sha256", sha256(image.data()));
            case Content.Audio audio -> payload("contentType", "audio",
                    "mimeType", audio.mimeType(),
                    "byteLength", audio.data().length, "sha256", sha256(audio.data()));
            case Content.File file -> payload("contentType", "file",
                    "mimeType", file.mimeType(), "fileName", file.fileName(),
                    "byteLength", file.data().length, "sha256", sha256(file.data()));
        };
    }

    private String toJson(Map<String, Object> payloadMap, Object source) {
        try {
            return MAPPER.writeValueAsString(payloadMap);
        } catch (RuntimeException e) {
            // Jackson 3 exceptions are unchecked (JacksonException extends
            // RuntimeException). Recording never throws (Invariant #4).
            recorder.countUnserializable();
            var cls = source != null ? source.getClass().getName() : payloadMap.getClass().getName();
            try {
                return MAPPER.writeValueAsString(Map.of("v", 1,
                        "_unserializable", cls, "_error", String.valueOf(e.getMessage())));
            } catch (RuntimeException fallback) {
                // Strings always serialize; unreachable in practice, but the
                // tape must never throw into the live stream.
                return "{\"v\":1,\"_unserializable\":\"" + cls + "\"}";
            }
        }
    }

    /** Versioned payload envelope: {@code {"v":1,...}}, null values skipped. */
    private static Map<String, Object> payload(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        map.put("v", 1);
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return map;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every conformant JRE; defensive only.
            return "unavailable";
        }
    }
}
