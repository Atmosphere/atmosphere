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
 * Plan-mode prompt + parser plumbing — coaxes the LLM into emitting a
 * {@link org.atmosphere.verifier.ast.Workflow} JSON instead of free-form
 * tool calls.
 *
 * <ul>
 *   <li>{@link org.atmosphere.verifier.prompt.PlanPromptBuilder} — builds
 *       the system prompt (tool list filtered by {@code Policy} +
 *       schema instructions + worked example).</li>
 *   <li>{@link org.atmosphere.verifier.prompt.WorkflowJsonParser} —
 *       parses the LLM's JSON response into the AST, converting
 *       {@code "@name"} markers into {@code SymRef}s.</li>
 * </ul>
 *
 * <p>Wire format is intentionally flat (no Jackson polymorphism) so the
 * verifier compile path stays Jackson-free; parsing is delegated to the
 * {@code StructuredOutputParser} resolved on the classpath.</p>
 */
package org.atmosphere.verifier.prompt;
