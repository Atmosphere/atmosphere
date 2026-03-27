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
package org.atmosphere.coordinator.journal;

/**
 * Inspects coordination events before they are recorded in a
 * {@link CoordinationJournal}. Returning {@code false} prevents the event
 * from being stored.
 *
 * <p>Follows the same pattern as
 * {@code org.atmosphere.cache.BroadcasterCacheInspector}.</p>
 */
public interface CoordinationJournalInspector {

    /**
     * Inspect an event before it is recorded.
     *
     * @param event the event about to be recorded
     * @return {@code true} to record, {@code false} to discard
     */
    boolean shouldRecord(CoordinationEvent event);
}
