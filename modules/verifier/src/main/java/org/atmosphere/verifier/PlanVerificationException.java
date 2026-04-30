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
package org.atmosphere.verifier;

import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.spi.VerificationResult;

import java.util.Objects;

/**
 * Thrown by {@link PlanAndVerify} when the verifier chain rejects an
 * LLM-emitted plan. Carries the offending {@link Workflow} alongside the
 * full {@link VerificationResult} so callers can log, report, or render
 * the violations without re-running the verifier chain.
 *
 * <p>This exception is the boundary signal that an unsafe plan was
 * <strong>refused before any tool fired</strong> — the headline guarantee
 * Meijer's plan-and-verify pattern provides over plain ReAct loops.</p>
 */
public class PlanVerificationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Workflow workflow;
    private final VerificationResult result;

    public PlanVerificationException(Workflow workflow, VerificationResult result) {
        super(buildMessage(result));
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.result = Objects.requireNonNull(result, "result");
    }

    public Workflow workflow() {
        return workflow;
    }

    public VerificationResult result() {
        return result;
    }

    private static String buildMessage(VerificationResult result) {
        Objects.requireNonNull(result, "result");
        var sb = new StringBuilder("Plan rejected by verifier chain (")
                .append(result.violations().size())
                .append(" violation(s)):");
        for (var v : result.violations()) {
            sb.append("\n  - [").append(v.category()).append("] ").append(v.message());
            if (v.astPath() != null) {
                sb.append(" (at ").append(v.astPath()).append(')');
            }
        }
        return sb.toString();
    }
}
