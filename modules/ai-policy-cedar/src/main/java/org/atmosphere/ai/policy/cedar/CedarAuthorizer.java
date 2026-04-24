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
package org.atmosphere.ai.policy.cedar;

import java.util.Map;

/**
 * Strategy for evaluating a Cedar policy against a request. Mirrors
 * {@code RegoEvaluator} — pluggable CLI-subprocess default with room
 * for an embedded {@code com.cedarpolicy:cedar-java} impl or a remote
 * HTTP evaluator.
 *
 * <p>Cedar's native request shape is
 * {@code {principal, action, resource, context}}. The Atmosphere
 * request is mapped by {@link CedarPolicy#evaluate} —
 * {@code principal = "User::<userId>"}, {@code action = "Action::invoke"},
 * {@code resource = "Agent::<agentId>"}, {@code context = request metadata}.
 * Operators who need a different mapping subclass {@link CedarPolicy}.</p>
 */
public interface CedarAuthorizer {

    /**
     * Evaluate the given Cedar policy module against a request.
     *
     * @param cedarSource Cedar policy source ({@code permit}/{@code forbid} rules)
     * @param principal   principal entity reference (e.g. {@code "User::\"42\""})
     * @param action      action entity reference (e.g. {@code "Action::\"invoke\""})
     * @param resource    resource entity reference (e.g. {@code "Agent::\"billing\""})
     * @param context     flat context map flattened into Cedar's context record
     * @return the evaluation outcome
     */
    Result authorize(String cedarSource,
                     String principal,
                     String action,
                     String resource,
                     Map<String, Object> context);

    /** Outcome of a Cedar authorization check. */
    record Result(boolean allowed, String reason, java.util.List<String> matchedPolicies) {
        public Result {
            reason = reason == null ? "" : reason;
            matchedPolicies = matchedPolicies == null
                    ? java.util.List.of() : java.util.List.copyOf(matchedPolicies);
        }

        public static Result allow(java.util.List<String> matched) {
            return new Result(true, "", matched);
        }

        public static Result deny(String reason, java.util.List<String> matched) {
            return new Result(false, reason, matched);
        }
    }
}
