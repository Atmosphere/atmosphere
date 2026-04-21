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
 * Chat endpoint gated by Microsoft Agent Governance Toolkit YAML policies.
 * Every prompt passes through the MS-schema rule chain loaded by
 * {@link PoliciesConfig}; denials surface as a {@code SecurityException} on
 * the streaming session and the user sees the policy's {@code message} text.
 *
 * <p>The demo response path exists so the sample works without an LLM —
 * {@link PolicyAdmissionGate} still runs governance before the canned reply.</p>
 */
@AiEndpoint(path = "/atmosphere/ms-governance")
@AgentScope(unrestricted = true,
        justification = "Microsoft Agent Governance Toolkit YAML demo — scope enforcement is the point of this sample and is delivered via classpath:atmosphere-policies.yaml (MS schema) rather than @AgentScope. Adding @AgentScope would muddy the interop story — the whole demo is about MS-format YAML governance running unmodified on Atmosphere.")
public class MsGovernanceChat {

    private static final Logger logger = LoggerFactory.getLogger(MsGovernanceChat.class);

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
                session.progress("Admitted by MS Agent Governance policy chain");
                session.send("Got it — you said: \"" + effective + "\". ");
                session.send("This deployment enforces Microsoft Agent Governance Toolkit "
                        + "YAML policies unchanged; see `atmosphere-policies.yaml` for the rule set ");
                session.send("and `GET /api/admin/governance/policies` for the live chain.");
                session.complete();
            }
        }
    }
}
