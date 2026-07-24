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
package org.atmosphere.ai.tool;

import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.governance.AuditEntry;
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.governance.rag.InjectionClassifier;
import org.atmosphere.ai.governance.rag.InjectionClassifierResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;

/**
 * Opt-in indirect-prompt-injection screen for tool output (OWASP Agentic
 * Top-10 A04). Tool results are untrusted wire content re-entering the model
 * — the lethal-trifecta vector — yet unlike RAG documents (screened
 * default-on by {@code SafetyContextProvider}) they historically reached the
 * model unscreened. {@link ToolExecutionHelper#finishAndEmit} runs every tool
 * result through {@link #screen} at the single cross-runtime tool-result seam,
 * so enabling the knob covers all runtime bridges and transports at once
 * (Correctness Invariant #7, Mode Parity).
 *
 * <p><b>Off by default</b>, gated behind {@link #ENABLED_PROPERTY} /
 * {@link #ENABLED_ENV} (sysprop wins, mirroring the tool-output offload
 * knobs). It is off out of the box because a tool that legitimately returns
 * text quoting an injection string — a security blog, a search hit — would be
 * withheld: the same false-positive concern that keeps response-side PII
 * redaction opt-in.</p>
 *
 * <p>When enabled, the result runs through the rule-based
 * {@link InjectionClassifier} (zero dependencies, sub-millisecond). A flagged
 * result is replaced with {@link #SANITIZED_PLACEHOLDER} and the enforcement
 * is audited to the {@link GovernanceDecisionLog} (phase {@code tool_output}).
 * The screen is fail-closed — a classifier error withholds the output rather
 * than admitting unscreened wire content — and never throws into the tool
 * path (Correctness Invariants #4 Boundary Safety, #2 Terminal Path).</p>
 */
public final class ToolOutputSafetyScreen {

    private static final Logger logger = LoggerFactory.getLogger(ToolOutputSafetyScreen.class);

    /**
     * System property enabling the opt-in tool-output injection screen.
     * Default OFF; resolved sysprop-first, then {@link #ENABLED_ENV}.
     */
    public static final String ENABLED_PROPERTY =
            "org.atmosphere.ai.tool.injectionScreen.enabled";

    /**
     * Environment variable enabling the opt-in tool-output injection screen.
     * See {@link #ENABLED_PROPERTY}.
     */
    public static final String ENABLED_ENV = "LLM_TOOL_OUTPUT_INJECTION_SCREEN";

    /**
     * Non-actionable marker substituted for a tool result the screen flags —
     * mirrors {@code SafetyContextProvider.SANITIZED_PLACEHOLDER} so the model
     * sees a neutral marker instead of the raw payload.
     */
    public static final String SANITIZED_PLACEHOLDER =
            "[tool output was flagged as potential prompt injection and withheld]";

    private ToolOutputSafetyScreen() {
    }

    /**
     * Screen one tool result. A {@code null}/blank result, or the screen
     * disabled (the default), returns the result unchanged — byte-identical to
     * the pre-screen behavior. Never throws.
     *
     * @param toolName the tool that produced the result (for audit / logging)
     * @param result   the raw tool result about to re-enter the model
     * @return the result, or {@link #SANITIZED_PLACEHOLDER} when flagged
     */
    public static String screen(String toolName, String result) {
        if (result == null || result.isBlank() || !enabled()) {
            return result;
        }
        try {
            var doc = new ContextProvider.Document(
                    result, "tool:" + (toolName == null ? "unknown" : toolName), 1.0);
            var decision = InjectionClassifierResolver
                    .resolve(InjectionClassifier.Tier.RULE_BASED)
                    .evaluate(doc);
            if (decision.outcome() != InjectionClassifier.Outcome.SAFE) {
                logger.info("Tool {} output flagged as potential prompt injection — withholding it "
                        + "from the model: {}", toolName, decision.reason());
                recordAudit(toolName, decision.reason(), decision.confidence(),
                        decision.outcome().name());
                return SANITIZED_PLACEHOLDER;
            }
            return result;
        } catch (RuntimeException e) {
            // Fail-closed: an errored screen withholds rather than admits
            // unscreened wire content back into the model. Never propagate.
            logger.warn("Tool {} output injection screen errored — withholding the output "
                    + "(fail-closed): {}", toolName, e.toString());
            recordAudit(toolName, "screen error: " + e.getMessage(), Double.NaN, "ERROR");
            return SANITIZED_PLACEHOLDER;
        }
    }

    /**
     * Record a screen enforcement to the {@link GovernanceDecisionLog},
     * mirroring {@code SafetyContextProvider.recordAudit}. Best-effort — an
     * audit failure never affects the tool result.
     */
    private static void recordAudit(String toolName, String reason,
                                    double confidence, String outcome) {
        try {
            var snapshot = new LinkedHashMap<String, Object>();
            snapshot.put("phase", "tool_output");
            snapshot.put("tool", toolName == null ? "unknown" : toolName);
            snapshot.put("classifier", "RuleBasedInjectionClassifier");
            snapshot.put("outcome", outcome);
            snapshot.put("confidence", confidence);
            snapshot.put("breach_action", "sanitize");
            var entry = new AuditEntry(
                    Instant.now(), "tool-output-safety", "code:ToolOutputSafetyScreen", "1.0",
                    "deny", reason, snapshot, 0.0);
            GovernanceDecisionLog.installed().record(entry);
        } catch (RuntimeException e) {
            logger.debug("tool-output screen audit record failed: {}", e.toString());
        }
    }

    /**
     * Resolve the enable flag from {@link #ENABLED_PROPERTY}, falling back to
     * {@link #ENABLED_ENV}. The sysprop wins, mirroring
     * {@code ToolExecutionHelper.resolveOffloadThreshold}. Default OFF.
     */
    private static boolean enabled() {
        var raw = System.getProperty(ENABLED_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(ENABLED_ENV);
        }
        return raw != null && Boolean.parseBoolean(raw.trim());
    }
}
