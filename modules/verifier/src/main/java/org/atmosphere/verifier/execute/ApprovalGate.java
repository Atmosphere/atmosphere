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

import java.util.Map;

/**
 * Human-in-the-loop decision point consulted by {@link GatedToolDispatcher}
 * before a verified plan's tool fires. Returning {@code true} permits the
 * call; returning {@code false} — or throwing — denies it.
 *
 * <p>This is the seam an application bridges to its real approval channel:
 * a console prompt, a chat-ops message, or Atmosphere's own
 * {@code @RequiresApproval} flow over a {@code StreamingSession}. Keeping
 * the gate this small means the synchronous {@link WorkflowExecutor} stays
 * decoupled from any particular streaming or UI stack — the bridge lives in
 * the application, not in the verifier.</p>
 *
 * <p>Implementations should be side-effect-free with respect to the
 * arguments map (it is the already-resolved call about to run) and must be
 * safe to call from the executor's thread.</p>
 */
@FunctionalInterface
public interface ApprovalGate {

    /**
     * Decide whether {@code toolName} may be invoked with {@code args}.
     *
     * @param toolName the tool about to fire.
     * @param args     the fully-resolved argument map (no symbolic
     *                 references remain).
     * @return {@code true} to allow the call, {@code false} to deny it.
     */
    boolean approve(String toolName, Map<String, Object> args);
}
