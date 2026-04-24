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
package org.atmosphere.samples.springboot.msgovernance;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.governance.PolicyAdmissionGate;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Customer-support chat gated by Microsoft Agent Governance Toolkit YAML
 * policies AND an Atmosphere-native {@link AgentScope}. The rule set in
 * {@code atmosphere-policies.yaml} mirrors the feature matrix from
 * Microsoft's {@code packages/agent-os/examples/customer-service/main.py}
 * canonical sample — escalation triggers, forbidden keywords, PII patterns,
 * discount-limit enforcement, audit-only probes — all expressed as MS-schema
 * rules. {@link AgentScope} layers the goal-hijacking defense on top (code
 * / medical / legal / financial probes rejected architecturally, not via
 * prompt engineering).
 *
 * <p>Defense-in-depth stack on every turn:</p>
 * <ol>
 *   <li>{@link AgentScope} scope classification (rule-based tier — sub-ms)</li>
 *   <li>MS-schema policies from YAML (first-match-by-priority)</li>
 *   <li>Framework-injected scope confinement preamble on the system prompt</li>
 *   <li>Every decision recorded to {@code GovernanceDecisionLog} for
 *       {@code GET /api/admin/governance/decisions} + an OpenTelemetry span</li>
 * </ol>
 */
@AiEndpoint(path = "/atmosphere/ms-governance",
        interceptors = { FaqRetrievalInterceptor.class, TicketClassifierInterceptor.class })
@AgentScope(
        purpose = "Customer support agent for Example Corp — orders, billing, "
                + "account questions, product information, refund and shipping status.",
        forbiddenTopics = {"legal advice", "medical advice", "financial advice",
                "competitor products"},
        onBreach = AgentScope.Breach.POLITE_REDIRECT,
        redirectMessage = "I can only help with Example Corp orders, billing, and "
                + "account questions. What can I help you with on that?",
        tier = AgentScope.Tier.RULE_BASED)
public class MsGovernanceChat {

    private static final Logger logger = LoggerFactory.getLogger(MsGovernanceChat.class);

    private static String policyRuleCountSummary() {
        // Quick descriptor of what's loaded; kept inline so the sample
        // narrative stays concrete. The real numbers are authoritative via
        // GET /api/admin/governance/summary.
        return "9 MS-schema rules (destructive / escalation / PII / discount / audit)";
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        logger.info("Incoming prompt: {}", message);
        var gate = PolicyAdmissionGate.admit(resource, new AiRequest(message));
        switch (gate) {
            case PolicyAdmissionGate.Result.Denied denied -> {
                logger.info("Denied by policy {}: {}", denied.policyName(), denied.reason());
                session.error(new SecurityException(
                        "Denied by policy '" + denied.policyName() + "': " + denied.reason()));
            }
            case PolicyAdmissionGate.Result.Admitted admitted -> {
                var effective = admitted.request().message();
                var metadata = admitted.request().metadata();
                var snippet = metadata == null ? null
                        : (String) metadata.get(FaqKnowledgeBase.RAG_SNIPPET_METADATA_KEY);
                var category = metadata == null ? null
                        : (String) metadata.get(FaqKnowledgeBase.RAG_CATEGORY_METADATA_KEY);
                session.progress("Admitted — "
                        + "@AgentScope + MS-schema YAML rules both passed");
                // Canned customer-support response that incorporates any FAQ
                // snippet retrieved by FaqRetrievalInterceptor. Real deployments
                // swap session.send for session.stream(message) once an LLM is
                // wired — the governance chain already ran, and the retrieved
                // snippet ({metadata.rag.snippet}) is available for the LLM
                // prompt builder to splice into a grounded response.
                if (snippet != null) {
                    session.send("Thanks for contacting Example Corp support. I see your "
                            + "message: \"" + effective + "\". ");
                    session.send("Matched FAQ (category=" + category + "): " + snippet + " ");
                    session.send("Every turn also passes through @AgentScope classification "
                            + "plus the " + policyRuleCountSummary()
                            + " from `atmosphere-policies.yaml`, audit-logged at "
                            + "`GET /api/admin/governance/decisions`.");
                } else {
                    session.send("Thanks for contacting Example Corp support. I see your "
                            + "message: \"" + effective + "\". ");
                    session.send("Every turn passes through @AgentScope classification "
                            + "plus the " + policyRuleCountSummary()
                            + " from `atmosphere-policies.yaml`, audit-logged at "
                            + "`GET /api/admin/governance/decisions`. ");
                    session.send("Try prompts listed in README.md to see each rule fire.");
                }
                session.complete();
            }
        }
    }
}
