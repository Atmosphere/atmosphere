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
package org.atmosphere.verifier.policy;

/**
 * Selects which plan shapes a {@link Policy} admits — the pluggable seam
 * that lets a deployment trade expressiveness for the strongest static
 * guarantee.
 *
 * <p>A verified plan is normally a flat, linear sequence of tool calls.
 * That linearity is exactly what makes the static proof airtight: the
 * verifier reasons over the <em>only</em> sequence that can run. Admitting
 * data-dependent branches ({@code org.atmosphere.verifier.ast.ConditionalNode})
 * trades some of that strength for expressiveness — the verifier must now
 * prove every branch safe because it cannot know at verification time which
 * one a runtime value will select.</p>
 *
 * <p>The {@link org.atmosphere.verifier.checks.StructureVerifier} enforces
 * the chosen mode, so the same plan AST can be run through either posture
 * and tested both ways.</p>
 */
public enum ControlFlowMode {

    /**
     * Refuse any plan that contains a control-flow node — only a flat,
     * linear list of tool calls is admitted. The default: the static proof
     * covers the single sequence that runs, with no path explosion and no
     * runtime-data-dependent branching. This mirrors the linear-workflow
     * model of Meijer's "Guardians of the Agents".
     */
    LINEAR_ONLY,

    /**
     * Admit {@code ConditionalNode} branches. Every verifier descends into
     * <em>both</em> arms of each conditional, so a plan only passes when
     * every reachable branch is safe — the predicate that selects an arm at
     * runtime is never trusted to keep an unsafe branch from firing.
     */
    BRANCHING
}
