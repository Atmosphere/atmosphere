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
package org.atmosphere.checkpoint.coordinator;

import org.atmosphere.coordinator.journal.CoordinationEvent;

/**
 * Extracts the application-specific workflow state to persist when a
 * {@link CoordinationEvent} triggers a checkpoint save. The returned value
 * is stored as the {@code state} of a {@code WorkflowSnapshot}.
 *
 * <p>Keep extractors cheap and side-effect-free: they run on the coordinator's
 * event-recording thread.</p>
 *
 * @param <S> application-owned workflow state type
 */
@FunctionalInterface
public interface CoordinationStateExtractor<S> {

    /** Extract the workflow state to persist, given the triggering event. */
    S extract(CoordinationEvent event);
}
