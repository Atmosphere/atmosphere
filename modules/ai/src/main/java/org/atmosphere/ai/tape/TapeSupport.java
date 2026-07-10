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
package org.atmosphere.ai.tape;

import org.atmosphere.ai.StreamingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide holder and wrap seam for the session tape. Default state is
 * uninstalled: {@link #wrap} returns the session unchanged at zero cost, so
 * the wrap sites in {@code AiEndpointHandler} and {@code AiPipeline} are
 * no-ops until an installer opts in.
 *
 * <p>Fidelity contract: "As-produced at the session boundary, post-decorator."
 * The tape is NOT delivered truth — on disconnect the leaf drops late writes
 * while the tape (above the leaf) keeps recording until cancel lands, so
 * {@link TapeStatus#CANCELLED} means trailing steps may be
 * produced-but-undelivered. StructuredOutputRetry failed attempts are excluded
 * because they are pipeline-internal retries that never reach the session
 * boundary.</p>
 *
 * <h2>Day-one exclusions (Mode Parity, Invariant #7 — intentionally different,
 * documented here)</h2>
 * <ul>
 *   <li>Sync {@code generate}/{@code generateResult} turns
 *       ({@code CollectingSession}) are not taped.</li>
 *   <li>Coordinator fan-out branch sessions are not taped (only the root
 *       endpoint session is).</li>
 *   <li>{@code StructuredOutputRetry} failed attempts never reach the session
 *       boundary and are not taped.</li>
 *   <li>{@code modules/interactions} runs already persist typed
 *       {@code InteractionStep} streams keyed by interactionId — joinable,
 *       not duplicated here.</li>
 *   <li>Reattach replay ({@code RunReattachSupport} writing frames directly
 *       to the resource) is not taped — the content was already recorded at
 *       production time; taping the replay would double-record.</li>
 * </ul>
 */
public final class TapeSupport {

    private static final Logger logger = LoggerFactory.getLogger(TapeSupport.class);

    private static final AtomicReference<TapeRecorder> HOLDER = new AtomicReference<>();

    private TapeSupport() {
    }

    /** Install a recorder over {@code store} with default tuning. */
    public static TapeRecorder install(TapeStore store) {
        return install(store, TapeRecorder.Config.defaults());
    }

    /**
     * Install a recorder over {@code store}. Refuses a double-install: when a
     * recorder is already installed, the new one is stopped, a WARN is logged
     * and the existing recorder is returned (the {@code @SpringBootTest}
     * context-caching scenario must not stack recorders).
     *
     * <p>The installer keeps ownership of the store: {@link #uninstall} stops
     * the recorder but never closes the store (Invariant #1).</p>
     *
     * @throws IllegalArgumentException for a {@code null} or {@link TapeStore#NOOP} store
     */
    public static TapeRecorder install(TapeStore store, TapeRecorder.Config config) {
        var recorder = new TapeRecorder(store, config);
        while (true) {
            if (HOLDER.compareAndSet(null, recorder)) {
                logger.info("Session tape installed (store '{}', durable={})",
                        store.name(), store.durable());
                return recorder;
            }
            var existing = HOLDER.get();
            if (existing != null) {
                // Refuse the double-install; stop the writer we just started
                // (we created it, we stop it — Invariant #1).
                recorder.close();
                logger.warn("Session tape already installed (store '{}') — refusing "
                                + "double-install of store '{}', returning the existing recorder",
                        existing.store().name(), store.name());
                return existing;
            }
            // A concurrent uninstall raced us between the CAS and the read;
            // retry the install.
        }
    }

    /**
     * Uninstall {@code recorder}: compare-and-reset the holder only if it
     * still points at that instance, and always stop the recorder's own
     * writer (bounded drain — see {@link TapeRecorder#close()}).
     */
    public static void uninstall(TapeRecorder recorder) {
        Objects.requireNonNull(recorder, "recorder");
        HOLDER.compareAndSet(recorder, null);
        recorder.close();
    }

    /** Whether a recorder is installed — cheap pre-check for wrap sites. */
    public static boolean installed() {
        return HOLDER.get() != null;
    }

    /**
     * The installed recorder's store — the read-side accessor for admin
     * surfaces (the {@code atmosphere_read_tape} MCP tool). Empty when no
     * recorder is installed, so callers report the tape as disabled rather
     * than failing. Reading does not transfer ownership: callers must never
     * close the returned store (Invariant #1).
     */
    public static Optional<TapeStore> installedStore() {
        var recorder = HOLDER.get();
        return recorder == null ? Optional.empty() : Optional.of(recorder.store());
    }

    /**
     * Wrap {@code session} so everything crossing it is taped. Returns the
     * session unchanged when nothing is installed (zero cost). The recorder
     * reference is captured at wrap time — a concurrent uninstall degrades
     * this run's recording to a no-op rather than re-routing it.
     */
    public static StreamingSession wrap(StreamingSession session, TapeRunInfo info) {
        var recorder = HOLDER.get();
        if (recorder == null || recorder.isClosed()) {
            return session;
        }
        return new TapeRecordingSession(recorder, session, info);
    }

    /**
     * Disconnect signal from the endpoint handler: cancel-marks every open
     * run of the resource (reconnects get a new uuid, so this is safe), after
     * the writer drains the steps already queued for those runs. No-op when
     * nothing is installed.
     */
    public static void resourceDisconnected(String resourceUuid) {
        var recorder = HOLDER.get();
        if (recorder != null) {
            recorder.resourceDisconnected(resourceUuid);
        }
    }
}
