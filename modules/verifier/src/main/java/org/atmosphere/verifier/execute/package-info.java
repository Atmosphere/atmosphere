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
 * Verified-plan execution.
 *
 * <p>{@link org.atmosphere.verifier.execute.WorkflowExecutor} walks the
 * verified {@link org.atmosphere.verifier.ast.Workflow} step-by-step,
 * resolving {@link org.atmosphere.verifier.ast.SymRef} arguments against an
 * environment of bound results, and dispatches each
 * {@link org.atmosphere.verifier.ast.ToolCallNode} through a pluggable
 * {@link org.atmosphere.verifier.execute.ToolDispatcher}.</p>
 *
 * <p>The default dispatcher
 * ({@link org.atmosphere.verifier.execute.RegistryToolDispatcher}) delegates
 * straight to {@link org.atmosphere.ai.tool.ToolRegistry#execute}. Phase 2
 * adds a gated dispatcher that routes through
 * {@link org.atmosphere.ai.tool.ToolExecutionHelper#executeWithApproval} so
 * verified plans still honor {@code @RequiresApproval} gates and the existing
 * audit trail.</p>
 *
 * <p><strong>Terminal-path completeness (correctness invariant #2):</strong>
 * on any tool failure, {@code WorkflowExecutor} throws
 * {@link org.atmosphere.verifier.execute.WorkflowExecutionException} carrying
 * an immutable snapshot of the partial environment built up to (but not
 * including) the failing step. Callers that need to recover or audit can
 * inspect {@code partialEnv()} and {@code failedAtBinding()}.</p>
 */
package org.atmosphere.verifier.execute;
