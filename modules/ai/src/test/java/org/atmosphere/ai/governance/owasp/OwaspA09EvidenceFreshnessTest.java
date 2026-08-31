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
package org.atmosphere.ai.governance.owasp;

import org.atmosphere.ai.gateway.GatewayProfiles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The A09 rate-limit evidence must describe the limiter that actually ships.
 *
 * <p>It claimed a "permissive 1M-calls/hour backstop" long after
 * {@link GatewayProfiles#safeDefault()} replaced that limiter — its own Javadoc
 * says it "replaces a one-million-calls-per-hour limiter that was, in practice,
 * no limiter at all". A security self-assessment describing a control the code
 * deleted is worse than a generous default: a reader auditing the framework is
 * told the wrong thing about the one number that bounds their spend.</p>
 *
 * <p>Asserting against the live constants rather than a copied string is the
 * point — a future retune of the defaults updates the matrix text automatically,
 * and cannot silently desynchronise it.</p>
 */
class OwaspA09EvidenceFreshnessTest {

    private static String a09RateLimitEvidence() {
        return OwaspAgenticMatrix.MATRIX.stream()
                .filter(r -> "A09".equals(r.id()))
                .flatMap(r -> r.evidence().stream())
                .filter(e -> e.evidenceClass().contains("PerUserRateLimiter"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "A09 no longer cites PerUserRateLimiter — the DoS/cost claim lost its "
                                + "load-bearing evidence"))
                .description();
    }

    @Test
    void a09QuotesTheLimitsThatActuallyShip() {
        var note = a09RateLimitEvidence();
        assertTrue(note.contains(String.valueOf(GatewayProfiles.SAFE_DEFAULT_MAX_REQUESTS_PER_WINDOW)),
                "A09 evidence must state the real per-principal limit ("
                        + GatewayProfiles.SAFE_DEFAULT_MAX_REQUESTS_PER_WINDOW + "); got: " + note);
        assertTrue(note.contains(String.valueOf(GatewayProfiles.SAFE_DEFAULT_ANONYMOUS_MAX_REQUESTS)),
                "A09 evidence must state the shared anonymous ceiling ("
                        + GatewayProfiles.SAFE_DEFAULT_ANONYMOUS_MAX_REQUESTS + "); got: " + note);
    }

    @Test
    void a09DoesNotCiteTheDeletedMillionCallLimiter() {
        var note = a09RateLimitEvidence().toLowerCase();
        assertFalse(note.contains("1m-calls") || note.contains("1m calls")
                        || note.contains("million"),
                "A09 still cites the 1M-calls/hour limiter that safeDefault() replaced: " + note);
    }

    @Test
    void a09SaysTheAnonymousBucketIsSharedNotPerPerson() {
        // The number alone reads like a generous per-user allowance. It is a
        // whole-deployment ceiling, and an auditor who misreads that under-sizes
        // their own protection.
        var note = a09RateLimitEvidence().toLowerCase();
        assertTrue(note.contains("share") || note.contains("shared")
                        || note.contains("whole-deployment"),
                "A09 must say the anonymous bucket is shared across all callers; got: " + note);
    }
}
