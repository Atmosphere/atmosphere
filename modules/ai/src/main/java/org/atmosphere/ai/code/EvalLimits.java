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
package org.atmosphere.ai.code;

/**
 * The per-evaluation ceilings an {@link EvalEngine} must enforce, derived from
 * {@link EvalConfig}. Every engine honours the same bounds so isolation is
 * uniform regardless of which interpreter is resolved.
 *
 * @param instructionBudget interpreted-instruction ceiling per call (CPU guard)
 * @param timeoutMillis      wall-clock ceiling per call, in milliseconds
 * @param maxOutputChars    cap on the serialized result length
 */
public record EvalLimits(int instructionBudget, long timeoutMillis, int maxOutputChars) {

    /** Build the limits an engine must enforce from the resolved configuration. */
    public static EvalLimits from(EvalConfig config) {
        return new EvalLimits(config.instructionBudget(),
                config.timeoutMillis(), config.maxOutputChars());
    }
}
