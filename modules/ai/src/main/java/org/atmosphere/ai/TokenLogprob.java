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

import java.util.Objects;

/**
 * One token's log probability as reported by a provider that exposes
 * native logprobs (e.g. OpenAI's {@code logprobs: true} option).
 * Used to populate {@link AiConfidence#tokens()} on the
 * {@link AiConfidence.Source#LOGPROBS_NATIVE} path.
 *
 * <p>{@code logprob} is the natural log of the token probability — always
 * non-positive, where {@code 0.0} means probability {@code 1.0} (perfect
 * confidence) and increasingly negative values mean lower confidence.
 * Convert to linear probability with {@code Math.exp(logprob)}.</p>
 *
 * @param token   the token text as returned by the provider
 * @param logprob natural log of the token probability ({@code (-∞, 0]})
 */
public record TokenLogprob(String token, double logprob) {

    public TokenLogprob {
        Objects.requireNonNull(token, "token");
        if (Double.isNaN(logprob)) {
            throw new IllegalArgumentException("logprob must not be NaN");
        }
        if (logprob > 0.0) {
            throw new IllegalArgumentException(
                    "logprob must be <= 0 (got " + logprob + " — natural log of a probability)");
        }
    }

    /** Convenience: linear probability {@code exp(logprob)} in {@code [0, 1]}. */
    public double linearProbability() {
        return Math.exp(logprob);
    }
}
