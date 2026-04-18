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
package org.atmosphere.ai.facts;

/**
 * Stable well-known fact keys the framework ships with. Applications add
 * their own keys freely — this list is the baseline every resolver is
 * expected to understand (or no-op on).
 */
public final class FactKeys {

    private FactKeys() {
        // constants holder
    }

    // --- time -------------------------------------------------------------

    /** Current wall-clock instant, ISO-8601. Server time, not request-relative. */
    public static final String TIME_NOW = "time.now";

    /** IANA timezone for rendering dates/times back to the user. */
    public static final String TIME_TIMEZONE = "time.timezone";

    // --- user -------------------------------------------------------------

    /** Canonical user id — same string the rate limiter / PermissionMode use. */
    public static final String USER_ID = "user.id";

    /** Display name / handle. */
    public static final String USER_NAME = "user.name";

    /** Locale tag (e.g. {@code en-CA}) for response formatting. */
    public static final String USER_LOCALE = "user.locale";

    /** Plan tier (enterprise / starter / trial) — shapes LLM behaviour. */
    public static final String USER_PLAN_TIER = "user.plan_tier";

    // --- feature flags ----------------------------------------------------

    /**
     * Prefix for feature-flag facts. Any key under {@code featureflag.<name>}
     * resolves to a boolean (or equivalent string) indicating the flag state
     * for the current user.
     */
    public static final String FEATUREFLAG_PREFIX = "featureflag.";

    // --- recent actions ---------------------------------------------------

    /**
     * Compact summary of the user's last N actions as an
     * {@code Optional<List<String>>}. Keeps the LLM grounded in what
     * actually happened, not what it remembers.
     */
    public static final String AUDIT_RECENT_ACTIONS = "audit.recent_actions";

    /** Last-login instant, ISO-8601. Useful for returning-user flows. */
    public static final String USER_LAST_LOGIN = "user.last_login";
}
