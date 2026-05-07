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
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.checkpoint.AgentPassivation;
import org.atmosphere.checkpoint.CheckpointStore;
import org.atmosphere.checkpoint.InMemoryCheckpointStore;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * E2E test handler exercising {@link AgentPassivation} — paint the
 * conversation history into a snapshot on prompt {@code pause}, hold the
 * checkpoint id in a static store, and resume on a follow-up prompt
 * {@code resume:&lt;id&gt;:&lt;signal&gt;}.
 *
 * <p>The store is process-local (an
 * {@link InMemoryCheckpointStore} held in a static field) so two
 * sequential WebSocket connections see the same snapshot — exactly the
 * "agent vanishes from RAM and rehydrates" pattern Bonér's deck
 * describes, scoped down to a single JVM for the e2e harness.</p>
 *
 * <p>Prompt forms:</p>
 * <ul>
 *   <li>{@code pause} — captures a synthetic 2-turn history into a
 *       snapshot, emits {@code passivation.id} metadata, and completes.</li>
 *   <li>{@code resume:ID:SIGNAL} — loads snapshot {@code ID}, executes a
 *       stub runtime that echoes the in-flight message and the size of
 *       the loaded history; emits {@code resumed.message},
 *       {@code resumed.history.size}, and {@code resumed.session.id}.</li>
 *   <li>{@code load:ID} — reads the snapshot back without resuming;
 *       emits {@code loaded.reason} and {@code loaded.pending} so a spec
 *       can verify the snapshot's contents independently of the resume
 *       flow.</li>
 *   <li>{@code missing:ID} — proves the not-found path raises
 *       {@code IllegalStateException} → routed through {@code session.error}.</li>
 * </ul>
 */
public class PassivationTestHandler implements AtmosphereHandler {

    private static final CheckpointStore STORE = new InMemoryCheckpointStore();
    private static final AgentRuntime RUNTIME = new EchoRuntime();

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();
        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("passivation-test").start(() ->
                    handlePrompt(trimmed, resource));
        }
    }

    private void handlePrompt(String prompt, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);
        try {
            if (prompt.equals("pause")) {
                var paused = pausedContext("draft contract for legal");
                var id = AgentPassivation.passivate(RUNTIME, paused, STORE,
                        "awaiting human approval");
                session.sendMetadata("passivation.id", id);
                session.sendMetadata("passivation.history.size",
                        paused.history().size());
                session.complete();
                return;
            }
            if (prompt.startsWith("resume:")) {
                var rest = prompt.substring("resume:".length());
                var sep = rest.indexOf(':');
                var id = rest.substring(0, sep);
                var signal = rest.substring(sep + 1);
                AgentPassivation.resume(RUNTIME, STORE, id, signal,
                        baseContext(""), session);
                return;
            }
            if (prompt.startsWith("load:")) {
                var id = prompt.substring("load:".length());
                var snap = AgentPassivation.loadSnapshot(STORE, id);
                session.sendMetadata("loaded.reason", snap.reason());
                session.sendMetadata("loaded.pending", snap.pendingMessage());
                session.sendMetadata("loaded.history.size", snap.history().size());
                session.complete();
                return;
            }
            if (prompt.startsWith("missing:")) {
                var id = prompt.substring("missing:".length());
                AgentPassivation.loadSnapshot(STORE, id); // throws
                return;
            }
            session.error(new IllegalArgumentException("unknown prompt: " + prompt));
        } catch (RuntimeException e) {
            session.error(e);
        }
    }

    private static AgentExecutionContext pausedContext(String pendingMessage) {
        return new AgentExecutionContext(
                pendingMessage, "system", "test-model",
                "agent-1", "session-paused", "user-1", "conv-1",
                List.of(), null, null, List.of(),
                new HashMap<>(),
                List.of(
                        new ChatMessage("user", "earlier turn", null, null, List.of()),
                        new ChatMessage("assistant", "earlier reply", null, null, List.of())),
                null, null);
    }

    private static AgentExecutionContext baseContext(String message) {
        return new AgentExecutionContext(
                message, "system", "test-model",
                "agent-1", "session-fresh", "user-1", "conv-1",
                List.of(), null, null, List.of(),
                new HashMap<>(), List.of(), null, null);
    }

    /** Echoes the resumed message + observed history size as metadata so
     * the spec can prove resume merged the snapshot into the dispatch. */
    private static final class EchoRuntime implements AgentRuntime {
        @Override public String name() { return "passivation-echo"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING, AiCapability.PASSIVATION);
        }
        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.sendMetadata("resumed.message", context.message());
            session.sendMetadata("resumed.history.size", context.history().size());
            session.sendMetadata("resumed.session.id", context.sessionId());
            session.send("resumed: " + context.message());
            session.complete();
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException { /* no-op */ }

    @Override
    public void destroy() { /* no-op */ }
}
