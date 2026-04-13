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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.AbstractAgentRuntime;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentLifecycleListener;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test handler for AgentLifecycleListener events (Wave 3 + Gap #6).
 *
 * <p>Prompt "listen" → fires onToolCall + onToolResult via static helpers,
 * emits captured data as metadata.</p>
 * <p>Prompt "error-listener" → adds a listener that throws on tool events,
 * verifies the recording listener still captures.</p>
 *
 * <p>Gap #6 prompts drive a {@link FakeAgentRuntime} that extends
 * {@link AbstractAgentRuntime}, so the real
 * {@code fireStart}/{@code fireCompletion}/{@code fireError} helpers in
 * {@link AbstractAgentRuntime#execute} run against the attached listeners:
 * <ul>
 *   <li>{@code fire-start-complete} — doExecute succeeds; assert onStart=1,
 *       onCompletion=1, onError=0, and onStart fires strictly before
 *       onCompletion.</li>
 *   <li>{@code fire-error} — doExecute throws; assert onStart=1,
 *       onCompletion=0, onError=1.</li>
 *   <li>{@code throwing-start} — a listener throws from onStart; assert
 *       execution still completes and the recording listener still sees
 *       onCompletion.</li>
 * </ul>
 */
public class LifecycleListenerTestHandler implements AtmosphereHandler {

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("lifecycle-test").start(() ->
                    handlePrompt(trimmed, resource));
        }
    }

    private void handlePrompt(String prompt, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);

        var recorder = new RecordingListener();
        var listeners = new ArrayList<AgentLifecycleListener>();
        listeners.add(recorder);

        if ("error-listener".equals(prompt)) {
            listeners.add(new ThrowingListener());
        }
        if ("throwing-start".equals(prompt)) {
            // Throws on onStart AFTER the recording listener has seen onStart,
            // so we can assert the pipeline kept going and onCompletion still
            // fires on the recording listener despite the sibling explosion.
            listeners.add(new ThrowingStartListener());
        }

        var context = new AgentExecutionContext(
                prompt, null, "test-model", "agent-1",
                session.sessionId(), "user-1", "conv-1",
                List.of(), null, null, List.of(),
                Map.of(), List.of(), null, null)
                .withListeners(listeners);

        switch (prompt) {
            case "fire-start-complete", "throwing-start" ->
                    runFakeRuntime(context, session, recorder, /* shouldThrow */ false);
            case "fire-error" ->
                    runFakeRuntime(context, session, recorder, /* shouldThrow */ true);
            default -> {
                var toolArgs = Map.<String, Object>of("city", "Montreal");
                fireToolCall(context, "get_weather", toolArgs);
                fireToolResult(context, "get_weather", "{\"temp\":22}");

                session.sendMetadata("listener.toolCall.count", recorder.toolCallNames.size());
                if (!recorder.toolCallNames.isEmpty()) {
                    session.sendMetadata("listener.toolCall.name", recorder.toolCallNames.getFirst());
                }
                session.sendMetadata("listener.toolResult.count", recorder.toolResultNames.size());
                if (!recorder.toolResultNames.isEmpty()) {
                    session.sendMetadata("listener.toolResult.name", recorder.toolResultNames.getFirst());
                    session.sendMetadata("listener.toolResult.preview",
                            recorder.toolResultPreviews.getFirst());
                }
                session.emit(new AiEvent.Complete(null, Map.of()));
            }
        }
    }

    private void runFakeRuntime(AgentExecutionContext context, StreamingSession session,
                                 RecordingListener recorder, boolean shouldThrow) {
        var runtime = new FakeAgentRuntime(shouldThrow);
        runtime.configure(new AiConfig.LlmSettings(null, "fake-model", "fake", null));
        RuntimeException caught = null;
        try {
            runtime.execute(context, session);
        } catch (RuntimeException e) {
            caught = e;
        }

        session.sendMetadata("listener.onStart.count", recorder.startCount);
        session.sendMetadata("listener.onCompletion.count", recorder.completionCount);
        session.sendMetadata("listener.onError.count", recorder.errorCount);
        var startBeforeCompletion = recorder.completionCount == 0
                || (recorder.firstStartNanos > 0
                        && recorder.firstStartNanos < recorder.firstCompletionNanos);
        session.sendMetadata("listener.startBeforeCompletion", startBeforeCompletion);
        session.sendMetadata("listener.caught", caught != null);
        if (!session.isClosed()) {
            session.emit(new AiEvent.Complete(null, Map.of()));
        }
    }

    private static void fireToolCall(AgentExecutionContext context,
                                     String toolName, Map<String, Object> args) {
        for (var listener : context.listeners()) {
            try {
                listener.onToolCall(toolName, args);
            } catch (Exception ignored) {
                // resilient dispatch
            }
        }
    }

    private static void fireToolResult(AgentExecutionContext context,
                                       String toolName, String result) {
        for (var listener : context.listeners()) {
            try {
                listener.onToolResult(toolName, result);
            } catch (Exception ignored) {
                // resilient dispatch
            }
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()
                || event.isClosedByClient() || event.isClosedByApplication()) {
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw && raw.message() instanceof String json) {
            event.getResource().getResponse().write(json);
            event.getResource().getResponse().flushBuffer();
        }
    }

    @Override
    public void destroy() {
    }

    private static class RecordingListener implements AgentLifecycleListener {
        final List<String> toolCallNames = new ArrayList<>();
        final List<String> toolResultNames = new ArrayList<>();
        final List<String> toolResultPreviews = new ArrayList<>();
        int startCount;
        int completionCount;
        int errorCount;
        long firstStartNanos;
        long firstCompletionNanos;

        @Override
        public void onStart(AgentExecutionContext context) {
            startCount++;
            if (firstStartNanos == 0) {
                firstStartNanos = System.nanoTime();
            }
        }

        @Override
        public void onToolCall(String toolName, Map<String, Object> arguments) {
            toolCallNames.add(toolName);
        }

        @Override
        public void onToolResult(String toolName, String resultPreview) {
            toolResultNames.add(toolName);
            toolResultPreviews.add(resultPreview);
        }

        @Override
        public void onCompletion(AgentExecutionContext context) {
            completionCount++;
            if (firstCompletionNanos == 0) {
                firstCompletionNanos = System.nanoTime();
            }
        }

        @Override
        public void onError(AgentExecutionContext context, Throwable error) {
            errorCount++;
        }
    }

    private static class ThrowingListener implements AgentLifecycleListener {
        @Override
        public void onToolCall(String toolName, Map<String, Object> arguments) {
            throw new RuntimeException("intentional test explosion");
        }

        @Override
        public void onToolResult(String toolName, String resultPreview) {
            throw new RuntimeException("intentional test explosion");
        }
    }

    private static class ThrowingStartListener implements AgentLifecycleListener {
        @Override
        public void onStart(AgentExecutionContext context) {
            throw new RuntimeException("intentional onStart explosion");
        }
    }

    /**
     * Minimal {@link AbstractAgentRuntime} subclass used only to drive the
     * lifecycle firing helpers in {@link AbstractAgentRuntime#execute} against
     * a test listener list. Configured with a sentinel native client so the
     * retry wrapper reaches {@code doExecute}.
     */
    private static final class FakeAgentRuntime extends AbstractAgentRuntime<Object> {

        private static final Object SENTINEL = new Object();
        private final boolean shouldThrow;

        FakeAgentRuntime(boolean shouldThrow) {
            this.shouldThrow = shouldThrow;
        }

        @Override
        public String name() {
            return "lifecycle-fake";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        protected String nativeClientClassName() {
            return "java.lang.Object";
        }

        @Override
        protected Object createNativeClient(AiConfig.LlmSettings settings) {
            return SENTINEL;
        }

        @Override
        protected String clientDescription() {
            return "fake lifecycle runtime";
        }

        @Override
        protected void doExecute(Object client, AgentExecutionContext context,
                                  StreamingSession session) {
            if (shouldThrow) {
                throw new RuntimeException("intentional doExecute failure");
            }
            // Emit a token but intentionally do NOT call session.complete() —
            // AbstractAgentRuntime.execute fires onCompletion on its own and
            // the outer handler still wants to push metadata after the
            // runtime returns. Calling complete() here would close the
            // session and drop the subsequent sendMetadata frames.
            session.send("ok");
        }
    }
}
