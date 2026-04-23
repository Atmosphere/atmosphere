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
package org.atmosphere.samples.springboot.checkpoint;

import jakarta.annotation.PostConstruct;
import org.atmosphere.coordinator.commitment.CommitmentRecordsFlag;
import org.atmosphere.coordinator.commitment.CommitmentSigner;
import org.atmosphere.coordinator.commitment.Ed25519CommitmentSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * v4 Goal 3 applied to the checkpoint-agent sample: every cross-agent
 * dispatch (analyzer → approver) emits a signed {@code CommitmentRecord}
 * on the coordination journal, paired with the checkpoint snapshot.
 *
 * <p>The HITL pause is the exact use case commitment records were
 * designed for — a reviewer (possibly days later) reading
 * {@code GET /api/checkpoints/{id}/approve} needs cryptographic proof of
 * who requested the analysis, what the analyzer returned, and what the
 * approver decided. The snapshot alone doesn't carry that; the signed
 * record does.</p>
 *
 * <p>Atmosphere-unique differentiator: durable session + signed audit
 * trail across the pause. MS Agent Framework drops in-flight state when
 * a workflow pauses; LangChain has no checkpoint primitive; both mean
 * the signed record can't be bound to a pause/resume boundary.</p>
 */
@Configuration
public class CommitmentConfig {

    private static final Logger logger = LoggerFactory.getLogger(CommitmentConfig.class);

    /**
     * Ed25519 signer for dispatch commitment records. Production would
     * persist + rotate through an {@code AgentIdentity}-backed signer —
     * this sample generates a fresh in-memory key on boot.
     */
    @Bean
    public CommitmentSigner commitmentSigner() {
        var signer = Ed25519CommitmentSigner.generate();
        logger.info("Checkpoint-agent commitment signer: {} / {}",
                signer.scheme(), signer.keyId());
        return signer;
    }

    /**
     * v4 Phase B1 says commitment records are flag-off by default. This
     * sample opts in because the audit trail is the whole demonstration:
     * an operator walking through the checkpoint/approve flow should see
     * signed records arrive on the journal as each step fires.
     */
    @PostConstruct
    public void enableCommitmentRecords() {
        CommitmentRecordsFlag.override(Boolean.TRUE);
        logger.info("Commitment records ENABLED for checkpoint-agent sample "
                + "— every analyzer/approver dispatch emits a signed VC-subtype record");
    }
}
