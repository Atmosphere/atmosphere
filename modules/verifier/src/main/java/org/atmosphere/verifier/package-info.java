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
 * Plan-and-verify SPI for AI agent workflows.
 *
 * <p>Implements the architecture proposed in Erik Meijer's
 * <em>"Guardians of the Agents: Formal Verification of AI Workflows"</em>
 * (Communications of the ACM, January 2026): instead of letting an LLM call
 * tools one at a time and decide what to do after each result, the LLM
 * generates a structured plan upfront using symbolic references (placeholders,
 * not real data). A static verifier checks the plan against a security policy
 * before any tool runs. Only verified plans execute.</p>
 *
 * <p>Module structure:</p>
 * <ul>
 *   <li>{@link org.atmosphere.verifier.ast} — Workflow AST records
 *       ({@code Workflow}, {@code WorkflowStep}, {@code ToolCallNode},
 *       {@code SymRef}).</li>
 *   <li>{@link org.atmosphere.verifier.policy} — Policy declarative records
 *       ({@code Policy}, {@code TaintRule}, {@code SecurityAutomaton}).</li>
 *   <li>{@link org.atmosphere.verifier.spi} — {@code PlanVerifier} interface
 *       (ServiceLoader-discovered) plus {@code VerificationResult} and
 *       {@code Violation}.</li>
 *   <li>{@link org.atmosphere.verifier.checks} — Built-in verifier
 *       implementations: {@code AllowlistVerifier},
 *       {@code WellFormednessVerifier}.</li>
 *   <li>{@link org.atmosphere.verifier.execute} — {@code WorkflowExecutor}
 *       resolving symbolic references and dispatching through a pluggable
 *       {@code ToolDispatcher} (default wraps
 *       {@link org.atmosphere.ai.tool.ToolRegistry}).</li>
 *   <li>{@link org.atmosphere.verifier.prompt} — System-prompt builders that
 *       coax LLMs into producing well-formed {@code Workflow} JSON. Filled in
 *       Phase 2.</li>
 * </ul>
 */
package org.atmosphere.verifier;
