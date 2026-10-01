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
package org.atmosphere.ai.governance;

import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.governance.memory.GovernanceMemoryConfig;
import org.atmosphere.ai.governance.memory.GovernanceMemorySink;
import org.atmosphere.ai.governance.memory.GovernanceProvenanceMemory;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Closes the governance learning loop: re-injects recent governance decisions
 * ({@link PolicyDecision.Deny} and {@link PolicyDecision.Prefer}) scoped to the current
 * turn's subject back into the system prompt, so the agent sees the reasoning and the
 * preferred alternative on its next turn instead of hitting the same wall repeatedly.
 *
 * <p>Governance decisions already flow <em>outward</em> — every admit/deny/transform/prefer
 * is recorded to {@link GovernanceDecisionLog} and fanned out to audit sinks and the admin
 * console. This interceptor turns that one-way signal into a feedback loop, the thesis of
 * <a href="https://jasonstanley.substack.com/p/governance-as-a-learning-signal">Governance
 * as a Learning Signal</a>: reshape the control-plane signal from
 * <em>negative + logged-only</em> to <em>contrastive + back-in-the-loop</em>. No model
 * retraining — the signal re-enters the prompt each turn, so even a non-learning agent
 * gets the lesson.</p>
 *
 * <p>Configure on {@code @AiEndpoint}:</p>
 * <pre>{@code
 * @AiEndpoint(path = "/chat", interceptors = GovernanceFeedbackInterceptor.class)
 * }</pre>
 *
 * <h2>Scoping (no cross-subject leakage)</h2>
 * Decisions are matched to the request on the tightest available identity dimension —
 * {@code conversation_id}, else {@code session_id}, else {@code user_id} — comparing the
 * <em>same</em> dimension recorded in the {@link AuditEntry#contextSnapshot()}. A request
 * with no identity injects nothing (an anonymous turn cannot be scoped, so surfacing
 * another subject's guidance is never risked). Dry-run shadow entries (decision prefixed
 * {@code dry-run:}) are excluded — shadow advisories are not enforced and must not steer
 * the agent.
 *
 * <h2>Durable recall (opt-in)</h2>
 * By default the source is the in-memory {@link GovernanceDecisionLog} ring buffer, so the
 * loop closes within a session and within the buffer window and governance lessons never
 * touch long-term memory — the article's "supervision of learned lessons" hazard is
 * side-stepped, not merely defended. Passing a {@link GovernanceProvenanceMemory} — or
 * enabling it app-wide via {@link org.atmosphere.ai.governance.memory.GovernanceMemoryConfig},
 * which publishes the store the no-arg interceptor reads — makes recall <em>durable</em>:
 * guidance persisted by {@link GovernanceMemorySink} (keyed by {@code user_id}) is merged in,
 * surviving restarts and the ring window. The provenance gate on that store drops expired /
 * low-confidence lessons on read, so a stale or wrong lesson cannot compound. Ephemeral
 * (this-session) guidance is listed first; durable guidance fills the remainder up to
 * {@link #maxItems}.
 *
 * <h2>Bounds</h2>
 * The scan is bounded to {@link #scanWindow} recent entries and the injected block to
 * {@link #maxItems} distinct guidance lines (deduplicated by rendered line), keeping the
 * prompt focused and the work O(scanWindow). A failure while building the block is logged and
 * the original request is returned unchanged — governance feedback is advisory and must never
 * break the turn.
 *
 * <h2>Client-visible signal</h2>
 * Injection into the system prompt is otherwise invisible to the client, so a turn that
 * carried guidance reports it on the wire: {@link #preProcess} records the injected lines on
 * the request under {@link #LINES_METADATA_KEY}, and {@link #beforeCompletion} — immediately
 * before the terminal {@code complete} frame — sends two metadata frames:
 * <ul>
 *   <li>{@value #INJECTED_METADATA_KEY} — the number of guidance lines injected into this
 *       turn's system prompt (an {@code int}, at most {@link #maxItems});</li>
 *   <li>{@value #LINES_METADATA_KEY} — those lines in injection order, each capped at
 *       {@value #MAX_SIGNAL_LINE_CHARS} characters.</li>
 * </ul>
 * Both are sent only when something was injected, so a turn without guidance emits nothing
 * (Runtime Truth: the frame reports the injection that happened, never configuration). The
 * lines are this subject's own guidance — the same scoping that governs injection governs the
 * signal. Like every {@code beforeCompletion} frame, the signal is not sent on the
 * {@code error()} terminal path, and a failure while sending it is logged and swallowed so it
 * can never suppress the terminal frame. The Atmosphere Console renders it under the turn as
 * the "Governance guidance applied" panel.
 *
 * <h2>Dispatch paths</h2>
 * {@link AiInterceptor}s run only on the {@code @AiEndpoint} dispatch path. The resource-free
 * {@link org.atmosphere.ai.AiPipeline} ({@code @Agent}, {@code @Coordinator}, AG-UI, channel
 * bridge) runs no interceptor, so on that path guidance is neither injected nor signalled —
 * the two are never out of step.
 */
public class GovernanceFeedbackInterceptor implements AiInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceFeedbackInterceptor.class);

    /** Default cap on distinct guidance lines injected per turn. */
    public static final int DEFAULT_MAX_ITEMS = 5;

    /** Default cap on how many recent decisions are scanned per turn. */
    public static final int DEFAULT_SCAN_WINDOW = 100;

    /**
     * Metadata key carrying the injected guidance lines: set on the {@link AiRequest} by
     * {@link #preProcess} (an unmodifiable {@code List<String>}) and sent to the client as a
     * metadata frame by {@link #beforeCompletion}.
     */
    public static final String LINES_METADATA_KEY = "ai.governance.feedback.lines";

    /** Metadata frame key carrying the number of guidance lines injected into this turn. */
    public static final String INJECTED_METADATA_KEY = "ai.governance.feedback.injected";

    /** Per-line cap on the client-visible signal (the system prompt keeps the full line). */
    public static final int MAX_SIGNAL_LINE_CHARS = 512;

    private final int maxItems;
    private final int scanWindow;
    private final GovernanceProvenanceMemory governanceStore;

    public GovernanceFeedbackInterceptor() {
        this(DEFAULT_MAX_ITEMS, DEFAULT_SCAN_WINDOW, null);
    }

    /** Ephemeral-only: recall from the {@link GovernanceDecisionLog} ring buffer. */
    public GovernanceFeedbackInterceptor(int maxItems, int scanWindow) {
        this(maxItems, scanWindow, null);
    }

    /**
     * @param maxItems        max distinct guidance lines injected into the system prompt
     * @param scanWindow      how many recent {@link GovernanceDecisionLog} entries to scan
     * @param governanceStore optional durable store (its provenance gate applies on read);
     *                        {@code null} keeps the ephemeral-only, zero-persistence default
     */
    public GovernanceFeedbackInterceptor(int maxItems, int scanWindow,
                                         GovernanceProvenanceMemory governanceStore) {
        if (maxItems <= 0) {
            throw new IllegalArgumentException("maxItems must be > 0, got: " + maxItems);
        }
        if (scanWindow <= 0) {
            throw new IllegalArgumentException("scanWindow must be > 0, got: " + scanWindow);
        }
        this.maxItems = maxItems;
        this.scanWindow = scanWindow;
        this.governanceStore = governanceStore;
    }

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        try {
            var dimension = subjectDimension(request);
            if (dimension == null) {
                // Anonymous turn — cannot scope safely (also implies no user_id, so no
                // durable recall either); inject nothing rather than risk cross-subject leak.
                return request;
            }
            // LinkedHashSet: preserves order (ephemeral first, then durable) and dedups by
            // rendered line across both sources.
            var guidance = new LinkedHashSet<String>();
            collectEphemeralInto(dimension.key(), dimension.value(), guidance);
            collectDurableInto(request, guidance);
            if (guidance.isEmpty()) {
                return request;
            }
            var block = new StringBuilder(
                    "Governance guidance from earlier in this session — follow it:");
            for (var line : guidance) {
                block.append("\n- ").append(line);
            }
            var augmented = (request.systemPrompt() != null && !request.systemPrompt().isBlank()
                    ? request.systemPrompt() + "\n\n" : "") + block;
            if (logger.isDebugEnabled()) {
                logger.debug("Injected {} governance-feedback line(s) scoped by {}={}",
                        guidance.size(), dimension.key(), dimension.value());
            }
            return recordInjected(request.withSystemPrompt(augmented), List.copyOf(guidance));
        } catch (RuntimeException e) {
            // Advisory only — a feedback failure must never break the turn.
            logger.warn("Governance feedback injection failed — proceeding without it: {}",
                    e.toString());
            return request;
        }
    }

    /**
     * Record the injected lines on the request so {@link #beforeCompletion} can report them.
     * The injection is the contract and the record only its signal: if recording fails, the
     * augmented request is returned without it rather than dropping the guidance.
     */
    private static AiRequest recordInjected(AiRequest augmented, List<String> lines) {
        try {
            // HashMap + unmodifiable view, not Map.copyOf: tolerate null metadata and null
            // values another component may have placed on the request.
            var merged = new HashMap<String, Object>(
                    augmented.metadata() != null ? augmented.metadata() : Map.of());
            merged.put(LINES_METADATA_KEY, lines);
            return new AiRequest(augmented.message(), augmented.systemPrompt(),
                    augmented.model(), augmented.userId(), augmented.sessionId(),
                    augmented.agentId(), augmented.conversationId(),
                    Collections.unmodifiableMap(merged), augmented.history());
        } catch (RuntimeException e) {
            logger.warn("Governance feedback injected but its client signal could not be "
                    + "recorded: {}", e.toString());
            return augmented;
        }
    }

    /**
     * Report the guidance {@link #preProcess} injected into this turn, while the session still
     * accepts frames: {@value #INJECTED_METADATA_KEY} (count) then {@value #LINES_METADATA_KEY}
     * (the lines). Sends nothing when the turn carried no guidance.
     */
    @Override
    public void beforeCompletion(AiRequest request, StreamingSession session,
                                 AtmosphereResource resource) {
        try {
            var lines = injectedLines(request);
            if (lines.isEmpty()) {
                return;
            }
            session.sendMetadata(INJECTED_METADATA_KEY, lines.size());
            session.sendMetadata(LINES_METADATA_KEY, lines);
        } catch (RuntimeException e) {
            // Advisory only — the signal must never break the turn or its terminal frame.
            logger.warn("Governance feedback signal failed — turn completes without it: {}",
                    e.toString());
        }
    }

    /**
     * The guidance lines {@link #preProcess} recorded on {@code request}, each capped at
     * {@value #MAX_SIGNAL_LINE_CHARS} characters and at most {@link #maxItems} of them; empty
     * when nothing was injected.
     */
    List<String> injectedLines(AiRequest request) {
        if (request == null || request.metadata() == null
                || !(request.metadata().get(LINES_METADATA_KEY) instanceof List<?> raw)) {
            return List.of();
        }
        var lines = new ArrayList<String>(Math.min(raw.size(), maxItems));
        for (var item : raw) {
            if (lines.size() >= maxItems) {
                break;
            }
            if (item instanceof String line && !line.isBlank()) {
                lines.add(line.length() > MAX_SIGNAL_LINE_CHARS
                        ? line.substring(0, MAX_SIGNAL_LINE_CHARS - 1) + "…"
                        : line);
            }
        }
        return List.copyOf(lines);
    }

    /**
     * Add ephemeral guidance for the scoped subject from the {@link GovernanceDecisionLog}
     * ring buffer, newest first, up to {@link #maxItems}. The set dedups by rendered line.
     */
    private void collectEphemeralInto(String dimKey, String dimValue, Set<String> out) {
        var recent = GovernanceDecisionLog.installed().recent(scanWindow);
        for (var entry : recent) {
            if (out.size() >= maxItems) {
                break;
            }
            if (!dimValue.equals(entry.contextSnapshot().get(dimKey))) {
                continue;
            }
            var line = guidanceLine(entry);
            if (line != null) {
                out.add(line);
            }
        }
    }

    /**
     * Add durable, user-scoped guidance from the optional {@link GovernanceProvenanceMemory}.
     * The store's provenance gate has already dropped expired / low-confidence lessons and
     * stripped the marker, so the returned strings are clean guidance lines. No-op when no
     * durable store is configured or the request carries no {@code user_id}.
     */
    private void collectDurableInto(AiRequest request, Set<String> out) {
        // Explicit store (programmatic / tests) wins; otherwise fall back to the store the
        // wiring published when the durable flag is on (GovernanceMemoryConfig.installStore).
        var store = governanceStore != null ? governanceStore : GovernanceMemoryConfig.installedStore();
        if (store == null || out.size() >= maxItems || !notBlank(request.userId())) {
            return;
        }
        var durable = store.getFacts(
                GovernanceMemorySink.namespaceKey(request.userId()), maxItems);
        for (var line : durable) {
            if (out.size() >= maxItems) {
                break;
            }
            if (notBlank(line)) {
                out.add(line.strip());
            }
        }
    }

    /**
     * Render one contrastive guidance line, or {@code null} when the entry is not a
     * feedback-eligible decision. Delegates to {@link GovernanceGuidance#line} so this
     * ephemeral path and the durable {@link GovernanceMemorySink} path render identically
     * (Correctness Invariant #7 — mode parity). {@code "dry-run:*"} shadow entries and
     * {@code admit}/{@code transform} render to {@code null} and are skipped.
     */
    private static String guidanceLine(AuditEntry entry) {
        return GovernanceGuidance.line(
                entry.decision(),
                entry.reason(),
                asText(entry.contextSnapshot().get(GovernanceDecisionLog.PREFERRED_KEY)));
    }

    private static String asText(Object value) {
        return value == null ? "" : value.toString().strip();
    }

    /** The tightest identity dimension the request carries, or {@code null} if anonymous. */
    private static Dimension subjectDimension(AiRequest request) {
        if (request == null) {
            return null;
        }
        if (notBlank(request.conversationId())) {
            return new Dimension("conversation_id", request.conversationId());
        }
        if (notBlank(request.sessionId())) {
            return new Dimension("session_id", request.sessionId());
        }
        if (notBlank(request.userId())) {
            return new Dimension("user_id", request.userId());
        }
        return null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private record Dimension(String key, String value) { }

    /** Test/introspection accessors. */
    public int maxItems() {
        return maxItems;
    }

    public int scanWindow() {
        return scanWindow;
    }
}
