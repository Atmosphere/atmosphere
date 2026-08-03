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

import java.util.List;
import java.util.Map;

/**
 * Parsed model of a Microsoft Agent Control Specification (ACS) manifest —
 * the YAML document rooted at {@code agent_control_specification_version}
 * that binds named policies (Rego bundles, built-in types) to intervention
 * points such as {@code input}, {@code pre_tool_call} and {@code output}.
 *
 * <p>The shape mirrors the upstream contract fixtures at
 * {@code policy-engine/core/tests/fixtures/manifests/} in
 * microsoft/agent-governance-toolkit; {@code AcsManifestConformanceTest}
 * parses verbatim copies of those fixtures, and the weekly
 * {@code ms-yaml-conformance} workflow diffs our copies against upstream
 * {@code main} so a shape change upstream fails loudly here.</p>
 *
 * @param version           the {@code agent_control_specification_version} value
 * @param name              {@code metadata.name}
 * @param description       {@code metadata.description}, or {@code ""}
 * @param extendsRefs       the {@code extends:} chain, unresolved (see
 *                          {@link AcsManifestPolicy} for the fail-closed
 *                          semantics of a non-empty chain)
 * @param policies          named policy declarations, insertion-ordered
 * @param interventionPoints intervention-point bindings, insertion-ordered
 * @param tools             tool declarations, insertion-ordered
 * @param annotators        annotator declarations, insertion-ordered
 */
public record AcsManifest(String version,
                          String name,
                          String description,
                          List<String> extendsRefs,
                          Map<String, PolicyRef> policies,
                          Map<String, InterventionPoint> interventionPoints,
                          Map<String, Tool> tools,
                          Map<String, Annotator> annotators) {

    public AcsManifest {
        extendsRefs = List.copyOf(extendsRefs);
        policies = Map.copyOf(policies);
        interventionPoints = Map.copyOf(interventionPoints);
        tools = Map.copyOf(tools);
        annotators = Map.copyOf(annotators);
    }

    /**
     * A named policy declaration.
     *
     * @param type   engine type, e.g. {@code rego} or upstream's internal
     *               {@code test} stub — dispatched via {@link AcsPolicyEngine}
     * @param bundle bundle location relative to the manifest ({@code .rego}
     *               file, directory, or {@code .tar.gz}), or {@code null}
     * @param query  engine query, or {@code null} when the intervention point
     *               or the engine default supplies it
     */
    public record PolicyRef(String type, String bundle, String query) {

        public PolicyRef {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("policy type must not be blank");
            }
        }
    }

    /**
     * One intervention-point binding.
     *
     * @param policyTarget     snapshot selector, e.g. {@code $snap.tool_call.args}
     * @param policyTargetKind canonical target kind, e.g. {@code tool_args}
     * @param toolNameFrom     selector for the tool name on tool points, or {@code null}
     * @param policyId         id of the bound policy in {@link AcsManifest#policies()}
     * @param query            point-level query override, or {@code null}
     * @param annotations      annotation name → source selector, insertion-ordered
     */
    public record InterventionPoint(String policyTarget,
                                    String policyTargetKind,
                                    String toolNameFrom,
                                    String policyId,
                                    String query,
                                    Map<String, String> annotations) {

        public InterventionPoint {
            if (policyId == null || policyId.isBlank()) {
                throw new IllegalArgumentException("intervention point must bind a policy id");
            }
            annotations = Map.copyOf(annotations);
        }
    }

    /**
     * A tool declaration.
     *
     * @param type           declaration type, upstream constant {@code Tool}
     * @param id             tool id
     * @param clearance      clearance labels; upstream accepts a scalar or a
     *                       sequence — normalized to a list here
     * @param securityLabels security labels, possibly empty
     */
    public record Tool(String type, String id, List<String> clearance, List<String> securityLabels) {

        public Tool {
            clearance = List.copyOf(clearance);
            securityLabels = List.copyOf(securityLabels);
        }
    }

    /**
     * An annotator declaration ({@code endpoint}, {@code llm} or
     * {@code classifier} typed).
     *
     * @param type       annotator type
     * @param endpoint   endpoint URL for {@code endpoint} annotators, else {@code null}
     * @param model      model name for {@code llm} annotators, else {@code null}
     * @param classifier classifier id for {@code classifier} annotators, else {@code null}
     */
    public record Annotator(String type, String endpoint, String model, String classifier) {

        public Annotator {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("annotator type must not be blank");
            }
        }
    }
}
