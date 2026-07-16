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
package org.atmosphere.checkpoint.temporal;

/**
 * Activity return value describing how one step ended. Mirrors
 * {@code StepOutcome} minus the state payload (state stays in the session):
 * {@code ADVANCE}/{@code HIBERNATED}/{@code COMPLETED} report progress,
 * {@code FAILED} carries the step's explicit failure reason. A step that
 * throws does not produce a report — the activity fails and Temporal's
 * per-step retry policy decides.
 */
public record StepReport(Kind kind, String reason) {

    /** How the step ended. */
    public enum Kind { ADVANCE, HIBERNATED, COMPLETED, FAILED }
}
