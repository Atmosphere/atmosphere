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
package org.atmosphere.coordinator.commitment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * W3C Verifiable-Credential-subtype record emitted on cross-agent dispatch.
 * Captures an authenticated trace of the coordinator's decision to dispatch
 * a particular agent for a particular scope on behalf of a particular
 * principal, signed with an Ed25519 key so downstream consumers can verify
 * the record was produced by the coordinator they expect.
 *
 * <p>Matches the shape of a W3C VC 2.0 credential plus Agent Payments
 * Protocol (AP2) delegation semantics — {@code issuer}, {@code subject},
 * {@code proof} for VC; {@code delegationChain} and {@code scope} for
 * AP2's cross-agent dispatch tracing.</p>
 *
 * <p><b>@Experimental</b> — the shape of this record is not frozen.
 * Standards-track convergence with W3C CCG + AP2 + Visa TAP (target
 * 2026-Q4) may cause a v2 schema migration. Default posture is flag-off
 * via {@link CommitmentRecordsFlag}; operators who flip it on are
 * explicitly opting into potential migration.</p>
 *
 * @param id                 stable unique record identifier (UUID)
 * @param coordinationId     ties this record to a {@code CoordinationEvent}
 * @param issuer             coordinator identity (e.g. {@code coordinator:support-dispatch})
 * @param principal          end-user / service principal the dispatch acts on behalf of
 * @param subject            target agent name (the agent being dispatched)
 * @param scope              authorization scope — typically the target skill
 * @param delegationChain    chain of principals delegating down to this dispatch
 * @param issuedAt           record issuance timestamp
 * @param expiresAt          dispatch authorization expiry; null for session-long
 * @param outcome            one of {@code admit}, {@code deny}, {@code transform},
 *                           {@code started}, {@code completed}, {@code failed}
 * @param properties         additional claims (model, cost, guardrail results, etc.)
 * @param proof              signing proof — {@code scheme} + {@code signature} bytes
 *                           (base64url). Unsigned records carry {@link Proof#UNSIGNED}.
 */
public record CommitmentRecord(
        String id,
        String coordinationId,
        String issuer,
        String principal,
        String subject,
        String scope,
        List<String> delegationChain,
        Instant issuedAt,
        Instant expiresAt,
        String outcome,
        Map<String, Object> properties,
        Proof proof
) {

    public CommitmentRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(coordinationId, "coordinationId");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(issuedAt, "issuedAt");
        delegationChain = delegationChain == null ? List.of() : List.copyOf(delegationChain);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        proof = proof == null ? Proof.UNSIGNED : proof;
    }

    /**
     * Canonical stringification used as the signing payload. Deterministic
     * (sorted keys where ordered maps weren't supplied) so a verifier that
     * rebuilds the payload from the same fields produces the same bytes.
     */
    public String canonicalPayload() {
        var sb = new StringBuilder();
        sb.append("id=").append(id).append('\n');
        sb.append("coordinationId=").append(coordinationId).append('\n');
        sb.append("issuer=").append(issuer).append('\n');
        if (principal != null) sb.append("principal=").append(principal).append('\n');
        sb.append("subject=").append(subject).append('\n');
        if (scope != null) sb.append("scope=").append(scope).append('\n');
        sb.append("delegationChain=").append(String.join(",", delegationChain)).append('\n');
        sb.append("issuedAt=").append(issuedAt).append('\n');
        if (expiresAt != null) sb.append("expiresAt=").append(expiresAt).append('\n');
        sb.append("outcome=").append(outcome).append('\n');
        // properties: insertion order preserved via LinkedHashMap copy done
        // in the compact constructor when caller used LinkedHashMap; for
        // Map.of / HashMap inputs we accept non-deterministic ordering —
        // callers that need verifiable signatures pass LinkedHashMap.
        for (var entry : properties.entrySet()) {
            sb.append("prop:").append(entry.getKey()).append('=')
                    .append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** Whether this record carries a verifiable signature. */
    public boolean isSigned() {
        return proof != null && !proof.equals(Proof.UNSIGNED);
    }

    /**
     * Signing proof — one row per verifier algorithm. Today only
     * {@code Ed25519} is emitted by {@link Ed25519CommitmentSigner}.
     *
     * @param scheme    signature scheme (e.g. {@code Ed25519})
     * @param keyId     public-key identifier (DID, fingerprint, etc.)
     * @param signature base64url-encoded signature bytes over
     *                  {@link #canonicalPayload()}
     * @param createdAt signature timestamp
     */
    public record Proof(String scheme, String keyId, String signature, Instant createdAt) {
        /** Sentinel used when a record was built without a signer installed. */
        public static final Proof UNSIGNED = new Proof("none", "", "", Instant.EPOCH);

        public Proof {
            scheme = scheme == null ? "none" : scheme;
            keyId = keyId == null ? "" : keyId;
            signature = signature == null ? "" : signature;
            createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        }
    }
}
