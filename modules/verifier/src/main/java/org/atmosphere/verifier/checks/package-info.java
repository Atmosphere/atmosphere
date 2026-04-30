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

/**
 * Built-in {@link org.atmosphere.verifier.spi.PlanVerifier} implementations.
 *
 * <p>Phase 1 ships the two cheapest checks — running them upfront filters
 * out the bulk of malformed or off-policy plans before more expensive
 * verifiers (taint analysis, automata, Z3) ever look at the AST:</p>
 *
 * <ul>
 *   <li>{@link org.atmosphere.verifier.checks.AllowlistVerifier} — every
 *       {@code ToolCallNode.toolName} appears in
 *       {@code Policy.allowedTools()} <em>and</em> resolves to a registered
 *       {@link org.atmosphere.ai.tool.ToolDefinition}.</li>
 *   <li>{@link org.atmosphere.verifier.checks.WellFormednessVerifier} —
 *       every {@link org.atmosphere.verifier.ast.SymRef} resolves to a
 *       binding produced by an earlier step (no forward references, no
 *       dangling refs).</li>
 * </ul>
 *
 * <p>Both verifiers are pure functions and make no IO. They are safe to
 * invoke in any context — including from policy-authoring tools that want
 * to validate a plan without instantiating the full execution stack.</p>
 */
package org.atmosphere.verifier.checks;
