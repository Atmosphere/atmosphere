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
 * Workflow AST — the structured plan an LLM emits before any tool runs.
 *
 * <p>Two cardinal rules:</p>
 * <ol>
 *   <li>Tool arguments may be either literal values <em>or</em>
 *       {@link org.atmosphere.verifier.ast.SymRef} placeholders. SymRefs
 *       resolve to concrete values only at execution time, after the
 *       workflow has been verified. The LLM never sees attacker-controlled
 *       data while generating the plan — that is the central security
 *       invariant the foundation enforces.</li>
 *   <li>The AST is a closed (sealed) hierarchy. Verifiers exhaustively
 *       pattern-match on {@link org.atmosphere.verifier.ast.PlanNode};
 *       new node kinds (e.g. {@code Conditional}, {@code Loop}) require an
 *       SPI revision and a corresponding update to every verifier — by
 *       design.</li>
 * </ol>
 */
package org.atmosphere.verifier.ast;
