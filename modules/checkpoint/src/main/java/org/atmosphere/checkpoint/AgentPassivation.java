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
package org.atmosphere.checkpoint;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AgentSnapshot;
import org.atmosphere.ai.StreamingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bonér's "agent saves state and vanishes from RAM while awaiting human
 * approval (days), then instantly resumes when signaled" pattern,
 * implemented on top of Atmosphere's {@link CheckpointStore}.
 *
 * <p>Universal — works for every {@link AgentRuntime} that streams through
 * Atmosphere's pipeline because {@link AgentExecutionContext} is the
 * shared state shape. Runtime-specific in-memory caches that do not
 * survive the JVM are intentionally dropped; the {@link AgentSnapshot}
 * captures only the bits that round-trip through the durable store.</p>
 *
 * <p>This class lives in {@code atmosphere-checkpoint} (not
 * {@code atmosphere-ai}) to break the dependency cycle between
 * {@code ai → checkpoint → coordinator → ai}. Callers that already pull
 * {@code atmosphere-checkpoint} (the natural durable-execution dep)
 * automatically get this helper.</p>
 *
 * <p>Capability advertisement: every runtime that honors
 * {@link org.atmosphere.ai.AiCapability#PASSIVATION} commits to driving
 * its execution through {@code context.history()} so the snapshot is
 * meaningful — that is, the resumed call observes the same conversation
 * the paused call was seeing. Runtimes that declare {@code PASSIVATION}
 * meet this contract by delegating to
 * {@code AbstractAgentRuntime.assembleMessages} or by using an equivalent
 * history-threading path in their native bridge.</p>
 *
 * <p><b>@Experimental</b> — no production {@code @AiEndpoint} request path invokes passivate/resume yet; the only callers today are the durable-execution integration test and the ai-passivation e2e spec. Runtimes that declare {@code PASSIVATION} satisfy the history-threading contract, but wiring a user-facing pause/resume endpoint is left to the application. This status will be revisited by 2026-Q4.</p>
 */
public final class AgentPassivation {

    private static final Logger logger = LoggerFactory.getLogger(AgentPassivation.class);

    private AgentPassivation() { /* static helper */ }

    /**
     * Passivate an in-flight agent — capture the persistable subset of
     * {@code context} into a {@link AgentSnapshot} and persist it to
     * {@code store}. The returned id is the durable handle the caller
     * passes back to {@link #resume} when the awaited signal arrives
     * (human approval, scheduled tick, upstream event).
     *
     * @param runtime the runtime the agent was running on (only its
     *                {@link AgentRuntime#name()} is captured)
     * @param context the execution context to snapshot
     * @param store   the checkpoint store
     * @param reason  human-readable pause reason (audit / observability)
     * @return the {@code CheckpointId} as a string; pass back to {@link #resume}
     */
    public static String passivate(AgentRuntime runtime,
                                   AgentExecutionContext context,
                                   CheckpointStore store,
                                   String reason) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(reason, "reason");
        var snapshot = AgentSnapshot.from(runtime.name(), context, reason);
        var coordinationId = context.sessionId() != null
                ? context.sessionId()
                : "passivated:" + runtime.name();
        var saved = store.save(WorkflowSnapshot.root(coordinationId, snapshot));
        return saved.id().value();
    }

    /**
     * Resume a passivated agent — load the {@link AgentSnapshot} from
     * {@code store} and re-execute {@code runtime} with the snapshot's
     * history merged into {@code base} and {@code externalSignal} as the
     * new in-flight message.
     *
     * <p>Runtime references on {@code base} (tools, memory provider,
     * listeners, retry policy) are preserved. Persistent fields are
     * overridden by the snapshot so the resumed call sees the same
     * systemPrompt / model / identity columns / history the agent paused
     * with.</p>
     *
     * @param runtime         the runtime to resume — typically the same
     *                        runtime that captured the snapshot, although
     *                        nothing prevents resuming on a different one
     * @param store           the checkpoint store the snapshot was written to
     * @param checkpointId    the id returned by {@link #passivate}
     * @param externalSignal  message that triggered resume (typically the
     *                        human's approval text, the event payload, or
     *                        the next user turn). When {@code null} or
     *                        empty, the snapshot's pending message is
     *                        replayed.
     * @param base            a freshly-constructed context carrying the
     *                        runtime references that should be re-injected
     * @param session         the streaming session to push the resumed run through
     * @throws IllegalStateException when the checkpoint id cannot be loaded
     */
    public static void resume(AgentRuntime runtime,
                              CheckpointStore store,
                              String checkpointId,
                              String externalSignal,
                              AgentExecutionContext base,
                              StreamingSession session) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(checkpointId, "checkpointId");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(session, "session");
        var id = CheckpointId.of(checkpointId);
        var loaded = store.<AgentSnapshot>load(id)
                .orElseThrow(() -> new IllegalStateException(
                        "Passivated checkpoint not found: " + checkpointId));
        var snapshot = loaded.state();
        var resumedMessage = externalSignal != null && !externalSignal.isEmpty()
                ? externalSignal
                : snapshot.pendingMessage();
        var rebuilt = new AgentExecutionContext(
                resumedMessage,
                snapshot.systemPrompt() != null ? snapshot.systemPrompt() : base.systemPrompt(),
                snapshot.model() != null ? snapshot.model() : base.model(),
                snapshot.agentId() != null ? snapshot.agentId() : base.agentId(),
                snapshot.sessionId() != null ? snapshot.sessionId() : base.sessionId(),
                snapshot.userId() != null ? snapshot.userId() : base.userId(),
                snapshot.conversationId() != null ? snapshot.conversationId() : base.conversationId(),
                base.tools(),
                base.toolTarget(),
                base.memory(),
                base.contextProviders(),
                mergeMetadata(base.metadata(), snapshot.metadata()),
                snapshot.history(),
                resolveResponseType(snapshot, base),
                base.approvalStrategy(),
                base.listeners(),
                base.parts(),
                base.approvalPolicy(),
                base.retryPolicy());
        runtime.execute(rebuilt, session);
    }

    /**
     * Read the {@link AgentSnapshot} for a checkpoint without resuming —
     * useful for inspection, audit, or building bespoke resume flows.
     *
     * @param store        the checkpoint store
     * @param checkpointId the id returned by {@link #passivate}
     * @return the snapshot
     * @throws IllegalStateException when the checkpoint cannot be loaded
     */
    public static AgentSnapshot loadSnapshot(CheckpointStore store, String checkpointId) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(checkpointId, "checkpointId");
        var id = CheckpointId.of(checkpointId);
        return store.<AgentSnapshot>load(id)
                .orElseThrow(() -> new IllegalStateException(
                        "Passivated checkpoint not found: " + checkpointId))
                .state();
    }

    /**
     * Restore the structured-output response type from the snapshot when
     * possible. {@link AgentSnapshot} stores the type as a fully-qualified
     * class name (the actual {@code Class<?>} doesn't survive the durable
     * round-trip), so we resolve it back via the thread-context classloader.
     * If the class is not on the resumer's classpath, fall back to
     * {@code base.responseType()} and log a single WARN — silently dropping
     * the typed parser for a structured-output agent would surprise the
     * caller, who is paying the snapshot bytes for the type metadata.
     */
    private static Class<?> resolveResponseType(AgentSnapshot snapshot,
                                                AgentExecutionContext base) {
        var name = snapshot.responseTypeName();
        if (name == null || name.isBlank()) {
            return base.responseType();
        }
        try {
            var loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                loader = AgentPassivation.class.getClassLoader();
            }
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError ex) {
            logger.warn("Passivated response type '{}' not loadable on resumer classpath; "
                    + "falling back to base.responseType() (which may be null). "
                    + "Structured-output agents resumed in an environment that lacks the "
                    + "type will lose typed parsing.", name, ex);
            return base.responseType();
        }
    }

    private static Map<String, Object> mergeMetadata(Map<String, Object> base,
                                                     Map<String, String> fromSnapshot) {
        if (fromSnapshot == null || fromSnapshot.isEmpty()) {
            return base;
        }
        var merged = new LinkedHashMap<String, Object>(fromSnapshot);
        if (base != null) {
            merged.putAll(base);
        }
        return Map.copyOf(merged);
    }
}
