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

import jakarta.annotation.PostConstruct;
import org.atmosphere.ai.governance.DenyListPolicy;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.KillSwitchPolicy;
import org.atmosphere.ai.governance.MessageLengthPolicy;
import org.atmosphere.ai.governance.RateLimitPolicy;
import org.atmosphere.coordinator.commitment.CommitmentRecordsFlag;
import org.atmosphere.coordinator.commitment.CommitmentSigner;
import org.atmosphere.coordinator.commitment.Ed25519CommitmentSigner;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * Wires the governance policy plane into this multi-agent sample.
 *
 * <ul>
 *   <li><b>MS YAML artifacts:</b> this sample carries a
 *       {@code classpath:atmosphere-policies.yaml} (loaded by Spring Boot's
 *       auto-config when present). Not enabled by default here to keep the
 *       demo focused on multi-agent orchestration; operators flip it on by
 *       adding the file.</li>
 *   <li><b>Scope + fleet interceptor:</b> {@link CeoCoordinator} declares
 *       {@code @AgentScope} (startup-advisory purpose) and runs
 *       {@code PolicyAdmissionGate.admit} at {@code @Prompt} entry. The
 *       coordinator also installs
 *       {@link org.atmosphere.coordinator.fleet.GovernanceFleetInterceptor}
 *       at the dispatch boundary so specialist agents receive governance
 *       coverage too.</li>
 *   <li><b>Commitment records:</b> we provide an
 *       {@link Ed25519CommitmentSigner} bean and flip the
 *       {@link CommitmentRecordsFlag} on at startup. Every cross-agent
 *       dispatch from the CEO to a specialist emits a signed
 *       {@code CommitmentRecord} on the coordination journal.</li>
 *   <li><b>OWASP evidence:</b> all four specialist dispatches travel
 *       through the same governance chain declared here. The
 *       {@code EvidenceConsumerGrepPinTest} already confirms
 *       {@code @AgentScope}, {@code PolicyAdmissionGate}, and
 *       {@code CommitmentSigner} have production consumers — this sample
 *       is one of them.</li>
 * </ul>
 */
@Configuration
public class GovernanceConfig {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceConfig.class);

    /**
     * Break-glass switch — an admin endpoint can arm this from the
     * governance HTTP surface (POST /api/admin/governance/kill-switch/arm)
     * to halt every dispatch without redeploying.
     */
    @Bean
    public KillSwitchPolicy killSwitch() {
        return new KillSwitchPolicy();
    }

    /** Per-session sliding-window cap — 30 requests per 60 seconds. */
    @Bean
    public RateLimitPolicy rateLimit() {
        return new RateLimitPolicy("startup-team-rate-limit",
                30, Duration.ofSeconds(60));
    }

    /** Cap per-turn prompt size so a pathological input can't explode cost. */
    @Bean
    public MessageLengthPolicy messageLength() {
        return new MessageLengthPolicy("startup-team-msg-cap", 8_000);
    }

    /**
     * Deny-list at the fleet-dispatch boundary. Catches the McDonald's
     * failure mode: if the CEO mistakenly dispatches "write Python code"
     * to the Research agent, this interceptor denies it before the
     * specialist even runs.
     */
    @Bean
    public DenyListPolicy dispatchDenyList() {
        return new DenyListPolicy("dispatch-deny", "write_code", "python script",
                "medical diagnosis", "legal opinion");
    }

    /**
     * Ed25519 signer for cross-agent commitment records. Generates a
     * fresh key on boot — production would persist / rotate via an
     * {@code AgentIdentity}-backed signer.
     */
    @Bean
    public CommitmentSigner commitmentSigner() {
        var signer = Ed25519CommitmentSigner.generate();
        logger.info("Commitment signer initialized — public key fingerprint: {}",
                signer.keyId());
        return signer;
    }

    /**
     * Enable commitment-record emission. Default per v4 is off — this
     * sample opts in because demonstrating the audit trail is the whole
     * point of a multi-agent demo.
     */
    @PostConstruct
    public void enableCommitmentRecords() {
        CommitmentRecordsFlag.override(Boolean.TRUE);
        logger.info("Commitment records ENABLED — every cross-agent "
                + "dispatch will emit a signed record on the journal");
    }

    /**
     * Publish the policies onto the framework property bag so
     * {@code AiEndpointProcessor} / {@code PolicyAdmissionGate} pick them
     * up at admission time.
     */
    @Bean
    public PolicyPlanePublisher policyPlanePublisher(AtmosphereFramework framework,
                                                      KillSwitchPolicy killSwitch,
                                                      RateLimitPolicy rateLimit,
                                                      MessageLengthPolicy messageLength,
                                                      DenyListPolicy dispatchDenyList) {
        List<GovernancePolicy> policies = List.of(
                killSwitch, rateLimit, messageLength, dispatchDenyList);
        framework.getAtmosphereConfig().properties()
                .put(GovernancePolicy.POLICIES_PROPERTY, policies);
        logger.info("Published {} governance policies to AiEndpointProcessor: {}",
                policies.size(),
                policies.stream().map(GovernancePolicy::name).toList());
        return new PolicyPlanePublisher(policies);
    }

    /** Marker record so Spring can track the publisher as a bean. */
    public record PolicyPlanePublisher(List<GovernancePolicy> policies) { }
}
