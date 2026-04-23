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

/**
 * Runtime gate for {@link CommitmentRecord} emission — the flag-off-default
 * contract called for by the v4 roadmap (Phase B1 schema-leakage resolution).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Default: <b>disabled</b>. Operators who install a
 *       {@link CommitmentSigner} but don't flip this flag do not emit
 *       records — protecting existing deployments against a silent
 *       dependency on an experimental schema.</li>
 *   <li>Operators opting in acknowledge the {@code @Experimental} contract:
 *       the record shape may migrate by 2026-Q4 when Phase B2 standards
 *       convergence with W3C CCG + AP2 + Visa TAP resolves.</li>
 * </ul>
 *
 * <h2>Sources of truth (checked in order)</h2>
 * <ol>
 *   <li>The in-process override set via {@link #override(Boolean)} — primarily
 *       for tests that need deterministic behavior without leaking system
 *       properties across cases.</li>
 *   <li>System property {@link #PROPERTY_NAME}. Value {@code "true"}
 *       (case-insensitive) enables; anything else (including unset) leaves
 *       the flag off.</li>
 * </ol>
 *
 * <p>Env-var / Spring-config integration belongs in framework adapters;
 * keeping the contract at system-property + static override level keeps
 * {@code modules/coordinator} framework-agnostic.</p>
 */
public final class CommitmentRecordsFlag {

    /**
     * System-property name gating commitment-record emission. Default off
     * per v4 Phase B1 schema-leakage resolution. Operators enable with
     * {@code -Datmosphere.ai.governance.commitment-records.enabled=true}.
     */
    public static final String PROPERTY_NAME = "atmosphere.ai.governance.commitment-records.enabled";

    private static volatile Boolean override;

    private CommitmentRecordsFlag() { }

    /** True when the operator has explicitly opted in (via property or override). */
    public static boolean isEnabled() {
        var localOverride = override;
        if (localOverride != null) {
            return localOverride;
        }
        return Boolean.parseBoolean(System.getProperty(PROPERTY_NAME, "false"));
    }

    /**
     * Test / runbook hook — force the flag on or off regardless of system
     * property. Pass {@code null} to clear the override and fall back to
     * the system property. Intentionally static to match the {@code static
     * holder} pattern used by other governance primitives
     * (e.g. {@code GovernanceDecisionLog.install}).
     */
    public static void override(Boolean enabled) {
        override = enabled;
    }
}
