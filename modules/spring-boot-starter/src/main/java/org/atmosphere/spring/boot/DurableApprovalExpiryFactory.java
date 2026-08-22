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

import org.atmosphere.ai.approval.ApprovalExpiry;
import org.atmosphere.ai.resume.EffectJournal;
import org.atmosphere.checkpoint.DurableApprovalExpiry;
import org.atmosphere.checkpoint.DurableTimerService;
import org.atmosphere.checkpoint.SqliteDurableTimerStore;

import java.nio.file.Path;

/**
 * Isolated construction of the durable approval-expiry backstop
 * ({@code DurableTimerService} over the SQLite timer store feeding
 * {@code DurableApprovalExpiry}, registre#26). Kept in its own class so it is
 * class-loaded only after the autoconfig has confirmed the optional
 * {@code atmosphere-checkpoint} module is on the classpath — same rationale
 * as {@link SqliteEffectJournalFactory}. Package-private: an implementation
 * detail of {@code AtmosphereAiAutoConfiguration}'s durable-run wiring.
 */
final class DurableApprovalExpiryFactory {

    private DurableApprovalExpiryFactory() {
    }

    /**
     * Handle owning the store and service this factory created — closed by
     * the spine installer on shutdown (Correctness Invariant #1).
     */
    record Handle(DurableTimerService service, SqliteDurableTimerStore store,
                  ApprovalExpiry expiry) implements AutoCloseable {
        @Override
        public void close() {
            service.close();
            store.close();
        }
    }

    /**
     * Builds, starts, and installs the backstop into
     * {@code ApprovalExpiryHolder}; the caller resets the holder and closes
     * the returned handle on shutdown.
     *
     * @param journalPath the effect journal's database path; the timer store
     *                    lives beside it as {@code <journalPath>.timers}
     */
    static AutoCloseable start(String journalPath, EffectJournal journal) {
        var store = new SqliteDurableTimerStore(Path.of(journalPath + ".timers"));
        var service = new DurableTimerService(store);
        var expiry = new DurableApprovalExpiry(service, journal);
        service.start();
        org.atmosphere.ai.approval.ApprovalExpiryHolder.install(expiry);
        return new Handle(service, store, expiry);
    }
}
