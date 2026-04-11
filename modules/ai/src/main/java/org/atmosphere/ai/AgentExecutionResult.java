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

import java.time.Duration;
import java.util.Optional;

/**
 * Typed result of a synchronous {@link AgentRuntime#generateResult} call. D-1
 * of the phased roadmap follow-ups: the original Phase 1 plan assumed an
 * {@code AgentExecutionResult} existed; it didn't. This record adds it
 * without disturbing the existing {@link AgentRuntime#generate} String-based
 * API so legacy callers keep working.
 *
 * @param text     the collected response text (may be empty on error)
 * @param usage    typed token counts reported by the provider, if any
 * @param duration wall-clock time from {@code generateResult} entry to
 *                 completion (including network latency)
 * @param model    the model identifier the runtime ultimately used, if known
 */
public record AgentExecutionResult(
        String text,
        Optional<TokenUsage> usage,
        Duration duration,
        Optional<String> model
) {

    public AgentExecutionResult {
        if (text == null) {
            text = "";
        }
        if (usage == null) {
            usage = Optional.empty();
        }
        if (duration == null) {
            duration = Duration.ZERO;
        }
        if (model == null) {
            model = Optional.empty();
        }
    }

    /** Convenience factory for runtimes that only have text + duration. */
    public static AgentExecutionResult of(String text, Duration duration) {
        return new AgentExecutionResult(text, Optional.empty(), duration, Optional.empty());
    }

    /** Convenience factory that attaches token usage. */
    public static AgentExecutionResult of(String text, TokenUsage usage, Duration duration) {
        return new AgentExecutionResult(text, Optional.ofNullable(usage), duration, Optional.empty());
    }
}
