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
package org.atmosphere.ai.policy.rego;

import java.util.Map;

/**
 * Strategy for evaluating a Rego policy against an input document. Pluggable
 * so operators can choose between:
 *
 * <ul>
 *   <li><b>Subprocess</b> (default {@link OpaSubprocessEvaluator}) — shell
 *       out to the {@code opa} binary via {@code ProcessBuilder}. Requires
 *       OPA on the operator's PATH. Well-trodden in ops: OPA is the
 *       reference implementation and typical ops orgs already have it.</li>
 *   <li><b>Embedded</b> — a JVM Rego evaluator (e.g. {@code jregorus}) wired
 *       by the operator. The Atmosphere framework does not ship one because
 *       no maintained pure-Java Rego evaluator exists as of 2026-04.</li>
 *   <li><b>Remote</b> — HTTP call to an OPA sidecar. Thin wrapper over
 *       {@code java.net.http.HttpClient}.</li>
 * </ul>
 *
 * <p>The {@link Result} shape follows the OPA decision shape:
 * {@code result.allow} boolean + optional {@code result.reason} string +
 * optional {@code result.matched_rule}. Evaluators normalize their backend's
 * shape into this record.</p>
 */
public interface RegoEvaluator {

    /**
     * Evaluate the given Rego module against the input document.
     *
     * @param regoSource  the Rego policy source (caller already read the bytes)
     * @param query       the Rego query string (e.g.
     *                    {@code "data.atmosphere.governance.allow"})
     * @param input       the input document; typically a map of
     *                    {@code request} fields the policy examines
     * @return the evaluation outcome
     */
    Result evaluate(String regoSource, String query, Map<String, Object> input);

    /** Outcome of a Rego evaluation. */
    record Result(boolean allowed, String reason, String matchedRule) {
        public Result {
            reason = reason == null ? "" : reason;
            matchedRule = matchedRule == null ? "" : matchedRule;
        }

        public static Result allow() {
            return new Result(true, "", "");
        }

        public static Result deny(String reason, String matchedRule) {
            return new Result(false, reason == null ? "" : reason,
                    matchedRule == null ? "" : matchedRule);
        }
    }
}
