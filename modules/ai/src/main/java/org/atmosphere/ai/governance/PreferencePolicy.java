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
package org.atmosphere.ai.governance;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emit a {@link PolicyDecision.Prefer} advisory when a request message matches any
 * configured phrase or regex — the "soft governance" tier that admits the turn but
 * records that a different, preferred path exists. The declarative counterpart to
 * {@link DenyListPolicy}: same phrase/regex matching, but the match yields an
 * <em>advisory</em> (allowed-but-discouraged) rather than a hard block.
 *
 * <p>Use it to express least-privilege preferences the article
 * <a href="https://jasonstanley.substack.com/p/governance-as-a-learning-signal">Governance
 * as a Learning Signal</a> calls the missing middle tier — "scoped access is preferred
 * over standing access" — where both paths are permitted but one is better. The recorded
 * advisory (its {@code preferred} alternative and {@code reason}) can be fed back to the
 * agent on a later turn by {@link GovernanceFeedbackInterceptor}, closing the loop so the
 * agent learns the preferred path in-context.</p>
 *
 * <p>Phrase matching is <b>case-insensitive substring</b>; regex entries are compiled
 * with {@link Pattern#CASE_INSENSITIVE}. Matching runs on the {@code PRE_ADMISSION} phase
 * only — a preference steers the <em>next</em> action, so there is nothing to advise once
 * the response has streamed; {@code POST_RESPONSE} always admits.</p>
 */
public final class PreferencePolicy implements GovernancePolicy {

    private static final Logger logger = LoggerFactory.getLogger(PreferencePolicy.class);

    private final String name;
    private final String source;
    private final String version;
    private final List<Pattern> patterns;
    private final String preferred;
    private final String reason;

    public PreferencePolicy(String name, String source, String version,
                            List<Pattern> patterns, String preferred, String reason) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        Objects.requireNonNull(patterns, "patterns");
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("preference requires at least one phrase or pattern");
        }
        for (var pattern : patterns) {
            if (pattern == null) {
                throw new IllegalArgumentException("patterns must not contain null");
            }
        }
        if (preferred == null || preferred.isBlank()) {
            throw new IllegalArgumentException("preferred must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        this.name = name;
        this.source = source;
        this.version = version;
        this.patterns = List.copyOf(patterns);
        this.preferred = preferred;
        this.reason = reason;
    }

    @Override public String name() { return name; }
    @Override public String source() { return source; }
    @Override public String version() { return version; }

    /** The preferred alternative this policy advises when a pattern matches. */
    public String preferred() { return preferred; }

    /** Why the preferred alternative is better than what the request proposed. */
    public String reason() { return reason; }

    /** Exposed so admin surfaces can render the trigger list (literals shown un-quoted). */
    public List<String> patternStrings() {
        var out = new ArrayList<String>(patterns.size());
        for (var p : patterns) {
            out.add(displayForm(p));
        }
        return List.copyOf(out);
    }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        // A preference steers the next action; once the response has streamed there is
        // nothing to advise, so the post-response phase always admits.
        if (context.phase() == PolicyContext.Phase.POST_RESPONSE) {
            return PolicyDecision.admit();
        }
        var request = context.request();
        if (request == null || request.message() == null || request.message().isBlank()) {
            return PolicyDecision.admit();
        }
        var message = request.message();
        for (var pattern : patterns) {
            if (pattern.matcher(message).find()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("preference '{}' advising on rule '{}': prefer '{}'",
                            name, displayForm(pattern), preferred);
                }
                return PolicyDecision.prefer(preferred, reason);
            }
        }
        return PolicyDecision.admit();
    }

    /**
     * Human-readable form of a compiled pattern: literal phrases compiled via
     * {@link Pattern#quote(String)} are unwrapped from their {@code \Q..\E}
     * envelope; genuine regexes are returned verbatim.
     */
    private static String displayForm(Pattern pattern) {
        var raw = pattern.pattern();
        if (raw.startsWith("\\Q") && raw.endsWith("\\E")
                && raw.indexOf("\\E") == raw.length() - 2) {
            return raw.substring(2, raw.length() - 2);
        }
        return raw;
    }
}
