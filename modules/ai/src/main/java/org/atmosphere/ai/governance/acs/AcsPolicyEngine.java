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
package org.atmosphere.ai.governance.acs;

import java.nio.file.Path;
import java.util.Map;

/**
 * Engine that evaluates one ACS policy binding and returns an ACS verdict.
 *
 * <p>Discovered via {@link java.util.ServiceLoader}, keyed by
 * {@link AcsManifest.PolicyRef#type()}. The {@code atmosphere-ai-policy-rego}
 * module registers the {@code rego} engine backed by the real {@code opa}
 * binary; a manifest that binds a type with no registered engine is denied
 * fail-closed by {@link AcsManifestPolicy} with a reserved
 * {@code acs_runtime_error} reason — never admitted (Correctness
 * Invariant #6).</p>
 */
public interface AcsPolicyEngine {

    /** Reason prefix for every fail-closed runtime-error verdict. */
    String RUNTIME_ERROR = "acs_runtime_error";

    /**
     * The {@link AcsManifest.PolicyRef#type()} this engine evaluates,
     * e.g. {@code "rego"}. Case-sensitive, matching upstream manifests.
     */
    String type();

    /**
     * Evaluate one bound policy against the canonical ACS policy input.
     * Implementations must fail closed: any internal failure returns a
     * {@link Verdict#deny(String, String)} (or throws — the caller maps a
     * throw to a deny), never an allow.
     *
     * @param evaluation the binding, resolved paths and canonical input
     * @return the verdict, never {@code null}
     */
    Verdict evaluate(Evaluation evaluation);

    /**
     * One evaluation request.
     *
     * @param policyId          the manifest-local id of the bound policy
     * @param policy            the policy declaration
     * @param query             the resolved engine query (point override,
     *                          then policy query, then the engine default)
     * @param manifestDir       directory containing the manifest, for
     *                          resolving {@code bundle:} relative paths; may
     *                          be {@code null} when the manifest was parsed
     *                          from a non-filesystem source — engines must
     *                          then deny relative bundles fail-closed
     * @param interventionPoint the firing point name, e.g. {@code pre_tool_call}
     * @param input             canonical ACS policy input (intervention_point,
     *                          policy_target, tool, …)
     */
    record Evaluation(String policyId,
                      AcsManifest.PolicyRef policy,
                      String query,
                      Path manifestDir,
                      String interventionPoint,
                      Map<String, Object> input) {

        public Evaluation {
            input = Map.copyOf(input);
        }
    }

    /**
     * An ACS verdict: {@code allow}, {@code deny} or {@code transform}.
     *
     * @param decision       the decision
     * @param reason         machine-readable reason, {@code ""} when absent
     * @param message        human-readable message, {@code ""} when absent
     * @param transformPath  transform target path for {@code transform}
     *                       verdicts (e.g. {@code $policy_target}), else {@code ""}
     * @param transformValue replacement value for {@code transform} verdicts,
     *                       else {@code null}
     */
    record Verdict(Decision decision,
                   String reason,
                   String message,
                   String transformPath,
                   Object transformValue) {

        public Verdict {
            if (decision == null) {
                throw new IllegalArgumentException("decision must not be null");
            }
            reason = reason == null ? "" : reason;
            message = message == null ? "" : message;
            transformPath = transformPath == null ? "" : transformPath;
        }

        public static Verdict allow() {
            return new Verdict(Decision.ALLOW, "", "", "", null);
        }

        public static Verdict deny(String reason, String message) {
            return new Verdict(Decision.DENY, reason, message, "", null);
        }

        public static Verdict transform(String path, Object value) {
            return new Verdict(Decision.TRANSFORM, "", "", path, value);
        }
    }

    /** The three ACS decisions. */
    enum Decision { ALLOW, DENY, TRANSFORM }
}
