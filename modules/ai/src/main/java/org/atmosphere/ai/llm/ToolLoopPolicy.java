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
package org.atmosphere.ai.llm;

/**
 * Per-request policy for the iterative tool-call loop a runtime runs when the
 * model wants to call tools. Bounds the number of model→tool→model rounds and
 * decides what to do when the bound is hit.
 *
 * <p>Why a typed policy instead of a single integer: the previous
 * {@code MAX_TOOL_ROUNDS = 5} constant in {@link OpenAiCompatibleClient} was
 * unconditional and silent — when a model needed six rounds to converge on
 * the right tool sequence, the loop completed mid-stream with whatever it had
 * and only logged a warning. Callers had no way to (a) raise the cap for a
 * complex task, (b) lower it for a quick lookup that should never tool-call,
 * or (c) learn that the cap was hit at all. This policy exposes both knobs
 * and rides through {@link ChatCompletionRequest} so every tool-loop round
 * sees the same bound.</p>
 *
 * <h2>Cross-runtime applicability</h2>
 *
 * <p>The Built-in runtime owns its tool loop and honors this policy directly.
 * Framework runtimes (LangChain4j, Spring AI, ADK, Koog) defer to their
 * underlying framework's loop and translate this policy into the framework's
 * native cap when one exists — e.g. LangChain4j {@code AiServices} exposes
 * {@code .maxSequentialToolsInvocations(int)}. When a framework has no
 * mappable knob, it logs once at request time and inherits its own default;
 * the policy is honored as a hint rather than a hard contract there. The
 * cross-runtime view stays in
 * {@code modules/ai/README.md} (capability matrix).</p>
 *
 * @param maxIterations  maximum number of model→tool→model rounds. Must be
 *                       {@code >= 1}. The default is 5, matching the
 *                       previous {@code OpenAiCompatibleClient} constant.
 * @param onMaxIterations behavior when {@code maxIterations} is reached.
 *                       {@link OnMaxIterations#COMPLETE_WITHOUT_TOOLS}
 *                       preserves the historical behavior — the loop
 *                       finishes with whatever the model emitted last and
 *                       fires {@code session.complete()}. {@link
 *                       OnMaxIterations#FAIL} fires {@code session.error}
 *                       with a {@link ToolLoopExhaustedException}, so
 *                       callers see the cap was hit instead of receiving a
 *                       silently-truncated response.
 */
public record ToolLoopPolicy(int maxIterations, OnMaxIterations onMaxIterations) {

    /**
     * Process-wide default — preserves the pre-policy behavior so existing
     * callers see no semantic change. Pre-policy: hard-coded constant of 5,
     * silent log-warn-and-complete on overflow.
     */
    public static final ToolLoopPolicy DEFAULT =
            new ToolLoopPolicy(5, OnMaxIterations.COMPLETE_WITHOUT_TOOLS);

    public ToolLoopPolicy {
        if (maxIterations < 1) {
            throw new IllegalArgumentException(
                    "maxIterations must be >= 1, got " + maxIterations);
        }
        if (onMaxIterations == null) {
            throw new IllegalArgumentException("onMaxIterations is required");
        }
    }

    /**
     * Convenience factory: same overflow semantics as {@link #DEFAULT}, with
     * a custom iteration cap.
     */
    public static ToolLoopPolicy maxIterations(int maxIterations) {
        return new ToolLoopPolicy(maxIterations, OnMaxIterations.COMPLETE_WITHOUT_TOOLS);
    }

    /**
     * Convenience factory: fail-fast on overflow with a custom iteration cap.
     * Useful for tool-driven agents where a runaway loop is a bug, not an
     * acceptable termination — surfaces the cap hit as a session error so
     * caller code can react instead of receiving a silently-truncated stream.
     */
    public static ToolLoopPolicy strict(int maxIterations) {
        return new ToolLoopPolicy(maxIterations, OnMaxIterations.FAIL);
    }

    /**
     * What the runtime does when {@link #maxIterations} is reached.
     */
    public enum OnMaxIterations {
        /**
         * Complete the response with whatever text the model emitted last.
         * Matches the pre-policy behavior: a warning is logged, the
         * stream's pending text is flushed, {@code session.complete()}
         * fires. Choose this when partial output is preferable to a
         * hard error.
         */
        COMPLETE_WITHOUT_TOOLS,
        /**
         * Fail the request via {@code session.error(...)} with a
         * {@link ToolLoopExhaustedException}. Choose this when the caller
         * needs to know the cap was hit — e.g. to retry with a higher cap,
         * report an error to the user, or trigger an alert.
         */
        FAIL
    }

    /**
     * Thrown into {@link org.atmosphere.ai.StreamingSession#error} when the
     * policy is configured with {@link OnMaxIterations#FAIL} and the
     * iteration cap is hit. Carries the cap that was exceeded so callers
     * inspecting the cause can decide whether to retry with a higher bound.
     */
    public static final class ToolLoopExhaustedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int maxIterations;

        public ToolLoopExhaustedException(int maxIterations) {
            super("Tool loop exhausted after " + maxIterations + " iterations");
            this.maxIterations = maxIterations;
        }

        public int maxIterations() {
            return maxIterations;
        }
    }
}
