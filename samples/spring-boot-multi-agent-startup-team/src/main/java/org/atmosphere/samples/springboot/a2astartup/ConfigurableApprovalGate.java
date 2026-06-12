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
package org.atmosphere.samples.springboot.a2astartup;

import org.atmosphere.verifier.execute.ApprovalGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Human-in-the-loop {@link ApprovalGate} consulted by the
 * {@code GatedToolDispatcher} before the CEO's consequential action
 * ({@code commit_budget}) fires — even though the plan already passed static
 * verification. This is defense in depth: the static proof bounds <em>what the
 * plan may do</em>; the gate adds a runtime <em>who approved this</em> step.
 *
 * <p>A real deployment would block here on a human decision pushed over the
 * {@code StreamingSession}. For a deterministic, headless demo this gate reads
 * a single property:</p>
 * <pre>{@code startup.approvals.auto-approve}  (default true)</pre>
 *
 * <ul>
 *   <li><b>true</b> (default) — the action is approved and commits, so the demo
 *       flows end to end.</li>
 *   <li><b>false</b> — the action is denied; the {@code GatedToolDispatcher}
 *       raises {@code ApprovalDeniedException} and the tool never fires
 *       (fail-closed, Correctness Invariant #6).</li>
 * </ul>
 */
public class ConfigurableApprovalGate implements ApprovalGate {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurableApprovalGate.class);

    private final boolean autoApprove;

    public ConfigurableApprovalGate(boolean autoApprove) {
        this.autoApprove = autoApprove;
    }

    @Override
    public boolean approve(String toolName, Map<String, Object> args) {
        logger.info("Approval requested for '{}' {} -> {}", toolName, args,
                autoApprove ? "APPROVED" : "DENIED (no human approval)");
        return autoApprove;
    }
}
