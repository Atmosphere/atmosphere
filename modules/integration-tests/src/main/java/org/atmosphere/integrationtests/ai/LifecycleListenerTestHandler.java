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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentLifecycleListener;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test handler for AgentLifecycleListener events (Wave 3).
 *
 * <p>Prompt "listen" → fires onToolCall + onToolResult via static helpers,
 * emits captured data as metadata.</p>
 * <p>Prompt "error-listener" → adds a listener that throws, verifies the
 * recording listener still captures.</p>
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

        var context = new AgentExecutionContext(
                prompt, null, "test-model", "agent-1",
                session.sessionId(), "user-1", "conv-1",
                List.of(), null, null, List.of(),
                Map.of(), List.of(), null, null)
                .withListeners(listeners);

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
            session.sendMetadata("listener.toolResult.preview", recorder.toolResultPreviews.getFirst());
        }

        session.emit(new AiEvent.Complete(null, Map.of()));
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
    }

    @Override
    public void destroy() {
    }

    private static class RecordingListener implements AgentLifecycleListener {
        final List<String> toolCallNames = new ArrayList<>();
        final List<String> toolResultNames = new ArrayList<>();
        final List<String> toolResultPreviews = new ArrayList<>();

        @Override
        public void onToolCall(String toolName, Map<String, Object> arguments) {
            toolCallNames.add(toolName);
        }

        @Override
        public void onToolResult(String toolName, String resultPreview) {
            toolResultNames.add(toolName);
            toolResultPreviews.add(resultPreview);
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
}
