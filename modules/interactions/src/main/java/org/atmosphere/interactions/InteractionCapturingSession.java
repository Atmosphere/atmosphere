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
package org.atmosphere.interactions;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.DelegatingStreamingSession;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link StreamingSession} decorator that mirrors the live event stream into a
 * durable {@link InteractionStep} log while forwarding every call to the wrapped
 * delegate unchanged.
 *
 * <p>This is the single capture point shared by both invocation modes: for a
 * synchronous {@code create} the delegate is the caller's live session, for a
 * {@code createBackground} run it is a headless {@code CollectingSession}. Because
 * the same session captures in both cases, the persisted {@code steps[]} and
 * {@code finalText} are identical regardless of mode (Correctness Invariant #7 —
 * Mode Parity).</p>
 *
 * <p>Capture is defensive on every axis: text deltas are coalesced into {@code text}
 * steps (one row per token would breach the {@code maxSteps} bound — Invariant #3,
 * Backpressure); the step log is bounded, with terminal {@code completion}/{@code error}/
 * {@code usage} steps always admitted so a capped run still records how it ended;
 * the terminal transition is CAS-guarded so racing {@code complete()}/{@code error()}
 * signals collapse to one terminal step (Invariant #2); and a {@code store} failure is
 * logged, never thrown out of the capture path. When {@code store} is {@code null} the
 * decorator performs no persistence at all — the {@code store=false} contract.</p>
 */
public final class InteractionCapturingSession extends DelegatingStreamingSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionCapturingSession.class);

    private final String interactionId;
    private final InteractionStore store;
    private final InteractionStepMapper mapper;
    private final int maxSteps;
    private final java.util.function.Consumer<InteractionStep> stepListener;

    private final AtomicLong seq = new AtomicLong();
    private final Object lock = new Object();
    private final List<InteractionStep> capturedSteps = new ArrayList<>();
    private final StringBuilder pendingText = new StringBuilder();
    private final StringBuilder finalText = new StringBuilder();
    private final CountDownLatch terminalLatch = new CountDownLatch(1);
    private final AtomicBoolean terminalDone = new AtomicBoolean();

    private int nonTerminalCount;
    private boolean capWarned;
    private boolean sawText;
    private volatile InteractionStatus terminalStatus;
    private volatile String errorMessage;
    private volatile TokenUsage usage;

    public InteractionCapturingSession(StreamingSession delegate, String interactionId,
                                       InteractionStore store, InteractionStepMapper mapper,
                                       int maxSteps) {
        this(delegate, interactionId, store, mapper, maxSteps, null);
    }

    /**
     * @param stepListener optional sink notified of every durable step as it is
     *                     captured (after coalescing), or {@code null}. The
     *                     live-stream broadcaster uses this so socket frames are
     *                     exactly the durable steps — same coalescing, no
     *                     divergence between the streamed and stored timelines.
     */
    public InteractionCapturingSession(StreamingSession delegate, String interactionId,
                                       InteractionStore store, InteractionStepMapper mapper,
                                       int maxSteps,
                                       java.util.function.Consumer<InteractionStep> stepListener) {
        super(delegate);
        this.interactionId = interactionId;
        this.store = store;
        this.mapper = mapper;
        this.maxSteps = maxSteps;
        this.stepListener = stepListener;
    }

    @Override
    public void send(String text) {
        if (text != null && !text.isEmpty()) {
            synchronized (lock) {
                pendingText.append(text);
                sawText = true;
            }
        }
        super.send(text);
    }

    @Override
    public void emit(AiEvent event) {
        switch (event) {
            case AiEvent.TextDelta delta -> {
                if (delta.text() != null && !delta.text().isEmpty()) {
                    synchronized (lock) {
                        pendingText.append(delta.text());
                        sawText = true;
                    }
                }
            }
            case AiEvent.TextComplete complete -> {
                synchronized (lock) {
                    // TextComplete carries the accumulated full text. Only adopt it
                    // when no deltas were streamed, otherwise the deltas already hold it.
                    if (!sawText && complete.fullText() != null) {
                        pendingText.append(complete.fullText());
                        sawText = true;
                    }
                }
            }
            case AiEvent.Complete complete -> finishTerminal(InteractionStatus.COMPLETED,
                    complete.summary(), null);
            case AiEvent.Error error -> finishTerminal(InteractionStatus.FAILED, null,
                    error.message());
            case AiEvent.ToolStart ignored -> captureStructured(event);
            case AiEvent.ToolResult ignored -> captureStructured(event);
            case AiEvent.ToolError ignored -> captureStructured(event);
            case AiEvent.AgentStep ignored -> captureStructured(event);
            case AiEvent.ApprovalRequired ignored -> captureStructured(event);
            default -> {
                // Transport/UI-only event (Progress, RoutingDecision, Handoff,
                // StructuredField, EntityStart, EntityComplete) — streamed live,
                // dropped from the durable log.
            }
        }
        super.emit(event);
    }

    @Override
    public void usage(TokenUsage tokenUsage) {
        if (tokenUsage != null && tokenUsage.hasCounts()) {
            this.usage = tokenUsage;
            append(mapper.usageStep(seq.getAndIncrement(), tokenUsage));
        }
        super.usage(tokenUsage);
    }

    @Override
    public void complete() {
        finishTerminal(InteractionStatus.COMPLETED, null, null);
        super.complete();
    }

    @Override
    public void complete(String summary) {
        finishTerminal(InteractionStatus.COMPLETED, summary, null);
        super.complete(summary);
    }

    @Override
    public void error(Throwable t) {
        finishTerminal(InteractionStatus.FAILED, null,
                t != null ? t.getMessage() : "unknown error");
        super.error(t);
    }

    /** Flush the pending text and capture a structured (tool/agent/approval) step. */
    private void captureStructured(AiEvent event) {
        flushText();
        mapper.toStep(event, seq.getAndIncrement()).ifPresent(this::append);
    }

    private void flushText() {
        String text = null;
        synchronized (lock) {
            if (pendingText.length() > 0) {
                text = pendingText.toString();
                finalText.append(text);
                pendingText.setLength(0);
            }
        }
        if (text != null) {
            append(mapper.textStep(seq.getAndIncrement(), text));
        }
    }

    private void finishTerminal(InteractionStatus status, String summary, String error) {
        if (!terminalDone.compareAndSet(false, true)) {
            return;
        }
        flushText();
        if (status == InteractionStatus.FAILED) {
            this.errorMessage = error;
            append(mapper.errorStep(seq.getAndIncrement(), error));
        } else {
            // Prefer the coalesced text; fall back to a summary-only completion.
            if (finalText.length() == 0 && summary != null) {
                finalText.append(summary);
            }
            append(mapper.completionStep(seq.getAndIncrement(), finalText.toString()));
        }
        this.terminalStatus = status;
        terminalLatch.countDown();
    }

    private void append(InteractionStep step) {
        boolean terminal = isTerminalType(step.type());
        synchronized (lock) {
            if (!terminal && nonTerminalCount >= maxSteps) {
                if (!capWarned) {
                    LOGGER.warn("Interaction {} reached step cap {}, dropping further "
                            + "non-terminal steps from the durable log", interactionId, maxSteps);
                    capWarned = true;
                }
                return;
            }
            capturedSteps.add(step);
            if (!terminal) {
                nonTerminalCount++;
            }
        }
        if (stepListener != null) {
            try {
                stepListener.accept(step);
            } catch (RuntimeException e) {
                // A live-broadcast failure must never break durable capture or
                // the terminal write (Correctness Invariant #2).
                LOGGER.debug("step listener failed for {}: {}", interactionId, e.getMessage(), e);
            }
        }
        if (store != null) {
            try {
                store.appendStep(interactionId, step);
            } catch (RuntimeException e) {
                // A persistence failure must not break the live stream or the
                // terminal write — the authoritative copy is the in-memory list,
                // which the service persists in one shot on the terminal path.
                LOGGER.debug("appendStep to store failed for {}: {}", interactionId, e.getMessage(), e);
            }
        }
    }

    private static boolean isTerminalType(String type) {
        return InteractionStepMapper.TYPE_COMPLETION.equals(type)
                || InteractionStepMapper.TYPE_ERROR.equals(type)
                || InteractionStepMapper.TYPE_USAGE.equals(type);
    }

    /** Whether the run has reached a terminal state (non-blocking). */
    public boolean isTerminated() {
        return terminalLatch.getCount() == 0;
    }

    /** Block until the run reaches a terminal state or the timeout elapses. */
    public boolean awaitTerminal(Duration timeout) {
        try {
            return terminalLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Snapshot of the captured steps in order. */
    public List<InteractionStep> steps() {
        synchronized (lock) {
            return List.copyOf(capturedSteps);
        }
    }

    /** The aggregated assistant text, empty string if none was produced. */
    public String finalText() {
        synchronized (lock) {
            return finalText.toString();
        }
    }

    /** The captured token usage, if the runtime reported any. */
    public Optional<TokenUsage> usage() {
        return Optional.ofNullable(usage);
    }

    /** Terminal status, or {@code null} if the run has not terminated yet. */
    public InteractionStatus terminalStatus() {
        return terminalStatus;
    }

    /** Failure message for a {@link InteractionStatus#FAILED} run, else {@code null}. */
    public String errorMessage() {
        return errorMessage;
    }
}
