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
package org.atmosphere.samples.springboot.aichat;

import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PreferencePolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Wires the "governance as a learning signal" demo onto the {@code /atmosphere/ai-chat}
 * endpoint. Two pieces cooperate:
 *
 * <ol>
 *   <li>This {@link PreferencePolicy} bean — auto-configuration bridges any Spring
 *       {@link GovernancePolicy} bean onto the policy plane, where it is evaluated on every
 *       turn. When the user asks for broad standing/permanent access it returns a soft
 *       {@code Prefer} advisory (admits the turn, but records that a scoped, time-boxed
 *       credential is the preferred path) rather than a hard deny.</li>
 *   <li>The {@code GovernanceFeedbackInterceptor} declared on {@link AiChat} — it re-injects
 *       that recorded advisory into the next turn's system prompt, so the assistant sees the
 *       preferred path and recommends it. The loop closes with no model retraining.</li>
 * </ol>
 *
 * <p>The advisory is deliberately an <em>org-specific</em> process the base model cannot know
 * (Example Corp's production-release runbook), so the loop's effect is causally observable:
 * the model's answer names the Example Corp {@code release-bot} / {@code #prod-releases}
 * process only because the advisory was injected. Because those tokens are unknowable without
 * the injected guidance, an e2e that asserts them fails when the loop is off (it is not a
 * trivially-true assertion).</p>
 *
 * <p>Timing on the {@code @AiEndpoint} streaming path: the policy plane (which records the
 * {@code Prefer}) runs <em>before</em> the interceptor (which injects), so a {@code Prefer}
 * steers the <em>same</em> turn that triggered it — immediate soft-governance. (A hard
 * {@code Deny} terminates its turn, so a denial is surfaced on the <em>next</em> turn from the
 * decision-log ring buffer instead.)</p>
 *
 * <p>Try it: ask "how do I deploy the billing service to production?" — the answer names the
 * Example Corp release-bot / #prod-releases change-managed process, and the console's Decisions
 * tab shows a {@code PREFER} from {@code production-release-advisor}.</p>
 */
@Configuration
public class GovernanceFeedbackConfig {

    /** The org-specific process a matching turn advises (unknowable to the base model). */
    public static final String PREFERRED =
            "at Example Corp, production releases go through release-bot: open a CHG change "
            + "ticket, run `/release-bot deploy <service>` in the #prod-releases channel, and get "
            + "a second approver — direct kubectl/CLI deploys to production are blocked";

    /** Why the org process is preferred (recorded on the advisory). */
    public static final String REASON =
            "Example Corp change-management policy requires ticketed, peer-approved production "
            + "releases via release-bot";

    /**
     * A production-deploy advisor: a turn about deploying/releasing to production yields a
     * {@code Prefer} advisory carrying the Example Corp release process. Non-matching turns
     * admit unchanged, so ordinary chat is unaffected.
     *
     * @return the preference policy bridged onto the governance plane
     */
    @Bean
    public GovernancePolicy productionReleaseAdvisor() {
        return new PreferencePolicy(
                "production-release-advisor", "code:spring-boot-ai-chat", "1",
                List.of(
                        Pattern.compile("\\bdeploy\\w*\\b.*\\bprod\\w*\\b", Pattern.CASE_INSENSITIVE),
                        Pattern.compile("\\brelease\\w*\\b.*\\bprod\\w*\\b", Pattern.CASE_INSENSITIVE),
                        Pattern.compile("\\bprod\\w*\\b.*\\b(deploy|release)\\w*\\b",
                                Pattern.CASE_INSENSITIVE)),
                PREFERRED, REASON);
    }
}
