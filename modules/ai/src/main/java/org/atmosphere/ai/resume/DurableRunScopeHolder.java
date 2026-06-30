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

import org.atmosphere.ai.StreamingSession;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of active {@link DurableRunContext}s keyed by
 * {@code runId}, mirroring {@link RunRegistryHolder} but per-run rather than
 * singleton. The durable-run spine {@linkplain #install installs} a context when
 * it begins driving a run and {@linkplain #remove removes} it on the terminal
 * path; every {@code executeWithApproval} call site resolves the context for the
 * current run via {@link #current(StreamingSession)} using the one handle that
 * reaches all runtimes — {@code session.runId()}.
 *
 * <p>When durable runs are disabled no context is ever installed, so
 * {@link #current} returns {@code null} and the journaled seams take their
 * byte-identical fast path.</p>
 *
 * @since 4.0
 */
public final class DurableRunScopeHolder {

    private static final ConcurrentHashMap<String, DurableRunContext> SCOPES = new ConcurrentHashMap<>();

    private DurableRunScopeHolder() {
        // static holder
    }

    /** Install the durable scope for a run. */
    public static void install(String runId, DurableRunContext context) {
        if (runId != null && context != null) {
            SCOPES.put(runId, context);
        }
    }

    /** The scope for {@code runId}, or {@code null} if none is installed. */
    public static DurableRunContext get(String runId) {
        return runId == null ? null : SCOPES.get(runId);
    }

    /**
     * The scope for the run the session belongs to, resolved from
     * {@code session.runId()}, or {@code null} when the session carries no
     * run id or no scope is installed (the non-durable fast path).
     */
    public static DurableRunContext current(StreamingSession session) {
        if (session == null) {
            return null;
        }
        return session.runId().map(DurableRunScopeHolder::get).orElse(null);
    }

    /** Remove the scope for a run on the terminal path. */
    public static void remove(String runId) {
        if (runId != null) {
            SCOPES.remove(runId);
        }
    }

    /** Drop all scopes. Primarily for test isolation. */
    public static void clear() {
        SCOPES.clear();
    }
}
