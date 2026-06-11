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
package org.atmosphere.ai;

import org.atmosphere.ai.devinspector.DevInspectorEntry;
import org.atmosphere.ai.devinspector.DevInspectorRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link StreamingSession} decorator that records the turn (prompt, response,
 * tool calls, usage, terminal status) into a {@link DevInspectorRecorder} for
 * the inner-loop dev inspector. Wrapped into the live decorator chain only when
 * a non-NOOP recorder is installed (zero cost when the inspector is off), and
 * fully exception-isolated: a recorder failure is logged and never breaks the
 * user's stream.
 */
final class DevInspectorCapturingSession extends DelegatingStreamingSession {

    private static final Logger logger =
            LoggerFactory.getLogger(DevInspectorCapturingSession.class);

    private final DevInspectorRecorder recorder;
    private final String model;
    private final String promptPreview;
    private final StringBuilder response = new StringBuilder();
    private final List<String> toolCalls = new ArrayList<>();
    private volatile long tokensIn;
    private volatile long tokensOut;
    private final AtomicBoolean recorded = new AtomicBoolean();

    DevInspectorCapturingSession(StreamingSession delegate, DevInspectorRecorder recorder,
                                 String model, String promptPreview) {
        super(delegate);
        this.recorder = recorder;
        this.model = model;
        this.promptPreview = promptPreview;
    }

    @Override
    public void send(String text) {
        if (text != null) {
            synchronized (response) {
                response.append(text);
            }
        }
        delegate.send(text);
    }

    @Override
    public void toolCallDelta(String toolCallId, String argsChunk) {
        if (toolCallId != null && argsChunk != null && !argsChunk.isEmpty()) {
            synchronized (toolCalls) {
                toolCalls.add(toolCallId + ": " + argsChunk);
            }
        }
        delegate.toolCallDelta(toolCallId, argsChunk);
    }

    @Override
    public void usage(TokenUsage usage) {
        if (usage != null) {
            tokensIn = usage.input();
            tokensOut = usage.output();
        }
        delegate.usage(usage);
    }

    @Override
    public void complete() {
        record("OK", "");
        delegate.complete();
    }

    @Override
    public void complete(String summary) {
        if (summary != null) {
            synchronized (response) {
                response.append(summary);
            }
        }
        record("OK", "");
        delegate.complete(summary);
    }

    @Override
    public void error(Throwable t) {
        record("ERROR", t != null ? String.valueOf(t.getMessage()) : "error");
        delegate.error(t);
    }

    private void record(String status, String error) {
        if (!recorded.compareAndSet(false, true)) {
            return;
        }
        try {
            String responseText;
            synchronized (response) {
                responseText = response.toString();
            }
            List<String> calls;
            synchronized (toolCalls) {
                calls = List.copyOf(toolCalls);
            }
            recorder.record(new DevInspectorEntry(Instant.now(), sessionId(), model,
                    promptPreview, responseText, calls, tokensIn, tokensOut, status, error));
        } catch (RuntimeException e) {
            // A recorder failure must never break the user's stream.
            logger.warn("Dev inspector record failed: {}", e.toString());
        }
    }
}
