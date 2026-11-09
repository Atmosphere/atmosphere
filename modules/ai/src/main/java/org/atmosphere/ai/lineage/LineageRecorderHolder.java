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

import java.util.concurrent.atomic.AtomicReference;

/**
 * Framework-wide holder for the active {@link LineageRecorder}. Mirrors the
 * {@code CostAccountantHolder} / {@code GovernanceDecisionLog.installed()}
 * pattern — static accessor with a {@link LineageRecorder#NOOP} default so
 * unmounted code paths (tests, ad-hoc helpers) cost nothing.
 *
 * <p>The Spring Boot starters and the Quarkus extension install a bounded,
 * content-redacting {@link InMemoryLineageRecorder} at startup through
 * {@link #installIfAbsent} ({@code atmosphere.ai.lineage.enabled}, default
 * {@code true}) and take it back out on shutdown through {@link #resetIf}, so
 * a recorder an application installed itself is never replaced or removed.
 * Direct callers can also {@link #install} one programmatically.
 * {@link #reset()} is test-only.</p>
 */
public final class LineageRecorderHolder {

    private static final AtomicReference<LineageRecorder> INSTALLED =
            new AtomicReference<>(LineageRecorder.NOOP);

    private LineageRecorderHolder() {
    }

    /** Install the framework-wide recorder. Subsequent calls overwrite. */
    public static void install(LineageRecorder recorder) {
        INSTALLED.set(recorder == null ? LineageRecorder.NOOP : recorder);
    }

    /**
     * Install {@code recorder} only when no recorder is installed yet, so an
     * integration's default never replaces one the application chose.
     *
     * @param recorder the recorder to install; {@code null} installs nothing
     * @return {@code true} when {@code recorder} is now the installed one
     */
    public static boolean installIfAbsent(LineageRecorder recorder) {
        if (recorder == null) {
            return false;
        }
        return INSTALLED.compareAndSet(LineageRecorder.NOOP, recorder);
    }

    /**
     * Reset to {@link LineageRecorder#NOOP} only when {@code expected} is
     * still the installed recorder: whoever installed it takes it back out,
     * and a recorder installed by someone else since stays (Ownership).
     *
     * @param expected the recorder the caller installed
     * @return {@code true} when the holder was reset
     */
    public static boolean resetIf(LineageRecorder expected) {
        if (expected == null || expected == LineageRecorder.NOOP) {
            return false;
        }
        return INSTALLED.compareAndSet(expected, LineageRecorder.NOOP);
    }

    /** @return the currently installed recorder, never null. */
    public static LineageRecorder get() {
        return INSTALLED.get();
    }

    /** Reset to {@link LineageRecorder#NOOP}. Test-only. */
    public static void reset() {
        INSTALLED.set(LineageRecorder.NOOP);
    }
}
