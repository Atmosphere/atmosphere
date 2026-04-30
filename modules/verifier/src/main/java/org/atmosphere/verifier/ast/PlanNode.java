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
package org.atmosphere.verifier.ast;

/**
 * Sealed root of the workflow plan AST.
 *
 * <p>Phase 1 admits a single concrete shape — {@link ToolCallNode}. Phase 5
 * extends the {@code permits} list with control-flow nodes (conditionals,
 * loops). Verifiers exhaustively pattern-match on this interface, so adding
 * a new node kind is intentionally a typed-source-incompatible change: every
 * verifier sees the new branch at compile time and must decide how to handle
 * it.</p>
 */
public sealed interface PlanNode permits ToolCallNode {
}
