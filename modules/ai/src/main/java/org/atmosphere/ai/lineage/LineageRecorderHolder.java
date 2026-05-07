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
package org.atmosphere.ai.lineage;

/**
 * Framework-wide holder for the active {@link LineageRecorder}. Mirrors the
 * {@code CostAccountantHolder} / {@code GovernanceDecisionLog.installed()}
 * pattern — static accessor with a {@link LineageRecorder#NOOP} default so
 * unmounted code paths (tests, ad-hoc helpers) cost nothing.
 *
 * <p>Spring Boot / Quarkus auto-configurations install a recorder at startup;
 * direct callers can also install one programmatically. {@link #reset()} is
 * test-only.</p>
 */
public final class LineageRecorderHolder {

    private static volatile LineageRecorder installed = LineageRecorder.NOOP;

    private LineageRecorderHolder() {
    }

    /** Install the framework-wide recorder. Subsequent calls overwrite. */
    public static void install(LineageRecorder recorder) {
        installed = recorder == null ? LineageRecorder.NOOP : recorder;
    }

    /** @return the currently installed recorder, never null. */
    public static LineageRecorder get() {
        return installed;
    }

    /** Reset to {@link LineageRecorder#NOOP}. Test-only. */
    public static void reset() {
        installed = LineageRecorder.NOOP;
    }
}
