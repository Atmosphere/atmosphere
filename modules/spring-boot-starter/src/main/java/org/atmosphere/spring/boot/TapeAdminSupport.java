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
package org.atmosphere.spring.boot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.atmosphere.ai.tape.TapeQuery;
import org.atmosphere.ai.tape.TapeRun;
import org.atmosphere.ai.tape.TapeStatus;
import org.atmosphere.ai.tape.TapeStep;
import org.atmosphere.ai.tape.TapeSupport;

/**
 * Read helpers for the session-tape admin endpoints, isolated from
 * {@link AtmosphereAdminEndpoint} so the optional {@code atmosphere-ai} tape
 * types never appear in the controller's method signatures.
 *
 * <p>{@code atmosphere-ai} is an <em>optional</em> dependency of the starter, so
 * samples that don't use AI (e.g. {@code spring-boot-durable-sessions}) run
 * without the {@code org.atmosphere.ai.tape} package on the classpath. Spring
 * reflects over every method of an active {@code @RestController} at bean
 * registration ({@code getDeclaredMethods()} force-loads each method's
 * parameter and return types); a tape type in a controller signature therefore
 * throws {@link NoClassDefFoundError} at startup on those samples. Keeping the
 * tape types here — reached only from guarded controller bodies once the
 * classpath probe confirms the package is present — lets the always-active
 * admin controller register everywhere while the tape endpoints report empty
 * where the tape isn't installed (Runtime Truth). Mirrors the optional-classpath
 * helper pattern used for the a2a types.
 */
final class TapeAdminSupport {

    private TapeAdminSupport() {
    }

    /**
     * Recorded AI runs (newest first), optionally filtered by {@code tapeId} /
     * {@code status}; empty when no tape store is installed at runtime.
     */
    static List<Map<String, Object>> runs(String tapeId, String status, int limit) {
        var store = TapeSupport.installedStore();
        if (store.isEmpty()) {
            return List.of();
        }
        var query = new TapeQuery(
                tapeId != null && !tapeId.isBlank() ? tapeId : null, parseStatus(status), limit);
        return store.get().listRuns(query).stream().map(TapeAdminSupport::runToMap).toList();
    }

    /**
     * The ordered steps of one run, from {@code fromSeq} up to {@code max};
     * empty when no tape store is installed at runtime.
     */
    static Map<String, Object> steps(String runId, long fromSeq, int max) {
        var store = TapeSupport.installedStore();
        if (store.isEmpty()) {
            return Map.of("runId", runId, "steps", List.of());
        }
        var steps = store.get().readSteps(runId, fromSeq, max).stream()
                .map(TapeAdminSupport::stepToMap).toList();
        return Map.of("runId", runId, "steps", steps, "count", steps.size());
    }

    /**
     * Whether a tape store is actually installed at runtime — the truthful
     * signal for the console's {@code hasTape} flag, distinct from mere
     * classpath presence (Runtime Truth — Invariant #5).
     */
    static boolean installed() {
        return TapeSupport.installed();
    }

    private static TapeStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return TapeStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null; // lenient read: an unknown status filters nothing
        }
    }

    private static Map<String, Object> runToMap(TapeRun r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("runId", r.runId());
        m.put("tapeId", r.tapeId());
        m.put("status", r.status().name());
        m.put("model", r.model());
        m.put("runtime", r.runtimeName());
        m.put("endpoint", r.endpoint());
        m.put("startedAt", r.startedAt());
        m.put("endedAt", r.endedAt());
        m.put("stepCount", r.stepCount());
        m.put("droppedSteps", r.droppedSteps());
        return m;
    }

    private static Map<String, Object> stepToMap(TapeStep s) {
        var m = new LinkedHashMap<String, Object>();
        m.put("seq", s.seq());
        m.put("kind", s.kind());
        m.put("payload", s.payload());
        m.put("ts", s.ts());
        return m;
    }
}
