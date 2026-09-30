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
package org.atmosphere.ai;

/**
 * What a completed turn's {@link AiConfidence} says the application should do
 * with the answer. Ordered from least to most cautious — {@link ConfidenceRouting}
 * resolves an unknown signal to the most cautious route unless configured
 * otherwise.
 */
public enum ConfidenceRoute {
    /** Confidence is at or above the act threshold — safe to act on automatically. */
    ACT,
    /** Confidence is between the confirm and act thresholds — act only after a confirmation. */
    CONFIRM,
    /** Confidence is below the confirm threshold, or unknown — hand the turn to a human. */
    ESCALATE
}
