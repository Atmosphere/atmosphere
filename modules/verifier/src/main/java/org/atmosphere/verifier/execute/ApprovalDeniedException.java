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
package org.atmosphere.verifier.execute;

/**
 * Raised by {@link GatedToolDispatcher} when an {@link ApprovalGate}
 * refuses (or fails to reach a verdict on) a tool call. The
 * {@link WorkflowExecutor} wraps it in a {@link WorkflowExecutionException}
 * that carries the partial environment, so a denial is a clean terminal
 * path — the tool never runs and the bindings produced before it are
 * preserved for audit.
 */
public final class ApprovalDeniedException extends RuntimeException {

    private final String toolName;

    public ApprovalDeniedException(String toolName, String reason) {
        super("Tool '" + toolName + "' was not approved: " + reason);
        this.toolName = toolName;
    }

    public ApprovalDeniedException(String toolName, String reason, Throwable cause) {
        super("Tool '" + toolName + "' was not approved: " + reason, cause);
        this.toolName = toolName;
    }

    /** @return the tool whose invocation was denied. */
    public String toolName() {
        return toolName;
    }
}
