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
package org.atmosphere.ai.resume;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.AiException;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.DelegatingStreamingSession;
import org.atmosphere.ai.RunCancelledException;
import org.atmosphere.ai.StreamingSession;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@link StreamingSession} decorator that mirrors the text and the terminal
 * frame a client receives into the run's {@link RunEventReplayBuffer}. This is
 * the producer side of the reattach wire — without it the buffer stays empty in
 * production and {@link RunReattachSupport#replayPendingRun} has nothing to
 * drain on reconnect.
 *
 * <p>The endpoint handler installs it directly above the connection's leaf
 * session, below {@code AiStreamingSession}, where every outbound frame of the
 * run passes: what the {@code @Prompt} method sends itself and what the runtime
 * streams for {@code session.stream(...)} alike. Text arrives as
 * {@code send}, {@code sendContent} with a text part, or an
 * {@link AiEvent.TextDelta}; the terminal frame as {@code complete},
 * {@code error} (a {@link RunCancelledException} is captured as the
 * {@code cancelled} frame), {@link AiEvent.Complete} or {@link AiEvent.Error}. Other
 * frames (metadata, progress, tool events) are not captured.</p>
 *
 * <p>The capture happens BEFORE the delegate write so the client sees
 * the live stream unchanged and a disconnect between the buffer write
 * and the transport write still preserves the event for replay.
 * Complement / error paths are captured too so reconnecting clients
 * can reconstruct the complete dialog state, including terminal
 * markers.</p>
 *
 * <p>The run's end is decided once, by the replay buffer: the first terminal
 * event it records is the run's end, and the delegate is written exactly one
 * terminal frame, the one standing for that end. A terminal call made after
 * the run already ended (a timeout recorded its end first, or a disconnect
 * ending the run raced the {@code @Prompt} completing it) writes the end the
 * run recorded, so the client and a reattached client see the same end;
 * every later terminal call is dropped.</p>
 *
 * <p>While the delegate writes the frame of a captured event, the event's
 * place in the run is {@link #positionOfFrameBeingWritten(StreamingSession)}
 * for the delegate, on the writing thread: a long-polling reply records the
 * event as handed to its client once a poll wrote the frame
 * ({@link RunEventReplayBuffer#handed}), so a later poll of that client carrying
 * the run id is not handed the event again, unless an earlier event of the run
 * never reached the client.</p>
 *
 * <p>Extends {@link DelegatingStreamingSession} so every interface
 * method that this decorator does not explicitly override is forwarded
 * automatically. The class originally hand-wrote each delegation and
 * missed {@code handoff()} — the trap the base class exists to
 * prevent.</p>
 */
public final class RunEventCapturingSession extends DelegatingStreamingSession {

    /** The captured event whose frame a delegate writes on this thread, while it does. */
    private static final ThreadLocal<Writing> WRITING = new ThreadLocal<>();

    /** {@code session} is writing the frame of the run event at {@code position}. */
    private record Writing(StreamingSession session, RunEventReplayBuffer.Position position) {
    }

    private final RunEventReplayBuffer buffer;
    /** Set by the terminal call that writes the delegate its one terminal frame. */
    private final AtomicBoolean terminalWritten = new AtomicBoolean();

    public RunEventCapturingSession(StreamingSession delegate, RunEventReplayBuffer buffer) {
        super(delegate);
        this.buffer = Objects.requireNonNull(buffer, "buffer");
    }

    /**
     * The place in its run of the captured event whose frame {@code session},
     * the session a {@code RunEventCapturingSession} decorates, is writing on
     * the calling thread, or {@code null} when it writes none: what a client
     * that receives the frame was handed of the run.
     *
     * @param session the session writing a frame
     */
    public static RunEventReplayBuffer.Position positionOfFrameBeingWritten(StreamingSession session) {
        var writing = WRITING.get();
        return writing != null && writing.session() == session ? writing.position() : null;
    }

    @Override
    public void send(String text) {
        var position = captureText(text);
        if (delegate.isClosed()) {
            return;
        }
        writing(position, s -> s.send(text));
    }

    @Override
    public void sendContent(Content content) {
        if (content instanceof Content.Text text) {
            var position = captureText(text.text());
            if (delegate.isClosed()) {
                return;
            }
            writing(position, s -> s.sendContent(content));
            return;
        }
        delegate.sendContent(content);
    }

    @Override
    public void emit(AiEvent event) {
        RunEventReplayBuffer.Position position = null;
        switch (event) {
            case AiEvent.TextDelta delta -> {
                position = captureText(delta.text());
                if (delegate.isClosed()) {
                    return;
                }
            }
            case AiEvent.Complete complete -> {
                end("complete", complete.summary() != null ? complete.summary() : "", s -> s.emit(event));
                return;
            }
            case AiEvent.Error error -> {
                end("error", error.message() != null ? error.message() : "error", s -> s.emit(event));
                return;
            }
            default -> {
                // Not a text or terminal frame: forwarded, not captured.
            }
        }
        writing(position, s -> s.emit(event));
    }

    /**
     * Has the delegate write a frame, with {@code position}, the place in the
     * run of the event it stands for, as its
     * {@link #positionOfFrameBeingWritten(StreamingSession)} meanwhile; a frame
     * of no captured event ({@code null}) is written with none.
     */
    private void writing(RunEventReplayBuffer.Position position, Consumer<StreamingSession> write) {
        var previous = WRITING.get();
        if (position != null) {
            WRITING.set(new Writing(delegate, position));
        } else {
            WRITING.remove();
        }
        try {
            write.accept(delegate);
        } finally {
            if (previous != null) {
                WRITING.set(previous);
            } else {
                WRITING.remove();
            }
        }
    }

    /**
     * End the run with the terminal event {@code type} unless it already ended,
     * and write the delegate the run's one terminal frame unless it was written
     * already: {@code frame} when this call ended the run, or when the run
     * recorded an end of the same type; otherwise the frame of the end the run
     * recorded, so the client is written the end its replay ends with.
     */
    private void end(String type, String payload, Consumer<StreamingSession> frame) {
        var event = buffer.terminateEvent(type, payload);
        if (!terminalWritten.compareAndSet(false, true)) {
            // A terminal frame was written already: the session ended once.
            return;
        }
        if (event != null) {
            writing(new RunEventReplayBuffer.Position(buffer, event.sequence()), frame);
            return;
        }
        // Already ended (a timeout recorded its end first, or a racing terminal call did).
        var recorded = buffer.terminal().orElse(null);
        if (recorded == null) {
            var sequence = buffer.lastSequence();
            writing(sequence >= 0 ? new RunEventReplayBuffer.Position(buffer, sequence) : null, frame);
            return;
        }
        writing(new RunEventReplayBuffer.Position(buffer, recorded.sequence()),
                type.equals(recorded.type()) ? frame : frameOf(recorded));
    }

    /** The terminal frame standing for the run's recorded end {@code end}. */
    private static Consumer<StreamingSession> frameOf(RunEvent end) {
        var payload = end.payload();
        if ("complete".equals(end.type())) {
            return payload == null || payload.isEmpty() ? StreamingSession::complete : s -> s.complete(payload);
        }
        if (RunCancelledException.FRAME_TYPE.equals(end.type())) {
            var reason = RunCancelledException.Reason.fromWire(payload).orElse(RunCancelledException.Reason.CALLER);
            return s -> s.error(new RunCancelledException(reason));
        }
        var message = payload != null && !payload.isEmpty() ? payload : "error";
        return s -> s.error(new AiException(message));
    }

    /**
     * Capture a text frame under the wire-protocol type name, so the replay
     * path can emit a valid AiStreamMessage JSON frame without a type-name
     * translation step — "text" would not parse as a streaming-text event on
     * the client.
     *
     * <p>The caller does not write the text when the client this session wrote
     * to is gone ({@code delegate.isClosed()}): the event is in the buffer,
     * which is how a reattached client receives it, and writing to the closed
     * session would only log a WARN per remaining chunk.</p>
     *
     * <p>A text sent after the run ended (a {@code @Prompt} that writes on
     * after its run was cancelled) is not recorded: the replay ends with the
     * terminal frame its clients were sent.</p>
     *
     * @return the captured event's place in the run, or {@code null} for an
     * empty text or a run that already ended
     */
    private RunEventReplayBuffer.Position captureText(String text) {
        if (text != null && !text.isEmpty()) {
            var event = buffer.captureIfOpen("streaming-text", text);
            return event != null ? new RunEventReplayBuffer.Position(buffer, event.sequence()) : null;
        }
        return null;
    }

    // Terminal events go through end: the first one ends the run, so a run
    // that already timed out or completed does not record a second, and the
    // delegate is written one terminal frame, the one of the run's end.

    @Override
    public void complete() {
        end("complete", "", StreamingSession::complete);
    }

    @Override
    public void complete(String summary) {
        end("complete", summary != null ? summary : "", s -> s.complete(summary));
    }

    @Override
    public void error(Throwable t) {
        if (t instanceof RunCancelledException cancelled) {
            // The run was cut short: a reattached client gets the same
            // cancelled frame the live client got.
            end(RunCancelledException.FRAME_TYPE, cancelled.reason().wire(), s -> s.error(t));
        } else {
            end("error", t == null ? "error"
                    : t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName(), s -> s.error(t));
        }
    }
}
