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
package org.atmosphere.ai.guardrails;

import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Regex-based PII guardrail. Ships with a conservative default pattern
 * set — email, phone, credit-card, US SSN, IPv4. Applications extend
 * with their own via {@link #withPatterns(List)}; for NLP-grade PII
 * detection plug in a model-backed guardrail (Presidio, AWS Comprehend)
 * that implements the same {@link AiGuardrail} SPI.
 *
 * <h2>Request path — redact (Modify)</h2>
 *
 * Matches are substituted with a {@code [redacted-*]} token and the
 * modified {@link AiRequest} is handed to the pipeline; the model sees
 * only the redacted form. Substitutions:
 *
 * <ul>
 *   <li>Email → {@code [redacted-email]}, except an address at a domain
 *       reserved for documentation and testing (RFC 2606 / RFC 6761:
 *       {@code example.com}/{@code .net}/{@code .org} and their subdomains,
 *       {@code *.example}, {@code *.test}, {@code *.invalid},
 *       {@code *.localhost}): it belongs to no one, so a code answer using
 *       {@code alice@example.com} is neither redacted nor blocked</li>
 *   <li>Phone (NA / international E.164) → {@code [redacted-phone]}: a
 *       number written as a phone — after a {@code +} ({@code +15145551234},
 *       {@code +44 20 7946 0958}, {@code +33 1 23 45 67 89}), or with
 *       separators or parentheses between its groups ({@code (514) 555-1234},
 *       {@code 514-555-1234}, {@code 514.555.1234}). A bare run of digits is
 *       not a phone, so a math result ({@code 2^31 = 2147483648}), a quantity,
 *       a year, an order number or the digits of a decimal
 *       ({@code 3.14159265358}) in an answer is neither redacted nor blocked.
 *       The trade-off: an unformatted phone number with no {@code +}
 *       ({@code 5145551234}) is not detected either. See
 *       {@code PiiPatterns.PHONE}.</li>
 *   <li>Credit card → {@code [redacted-cc]}: 13–19 digits, optionally grouped
 *       by single spaces or hyphens ({@code 4111 1111 1111 1111},
 *       {@code 3782 822463 10005}), standing on their own and passing the
 *       Luhn checksum. A large integer in an answer that fails Luhn
 *       ({@code 2^50 = 1125899906842624}, a factorial, {@code 1234567890123456})
 *       is neither redacted nor blocked. The trade-off: a random 13–19 digit
 *       number passes Luhn one time in ten, so about one in ten such integers
 *       is still taken for a card. See {@code PiiPatterns.CREDIT_CARD}.</li>
 *   <li>US SSN-shaped → {@code [redacted-ssn]}</li>
 *   <li>IPv4 → {@code [redacted-ip]}</li>
 * </ul>
 *
 * <p>Call {@link #blocking()} to switch the request path to {@code Block}
 * instead (useful for compliance environments where PII must not leave
 * the process even redacted).</p>
 *
 * <h2>Response path — early termination (Block)</h2>
 *
 * The guardrail inspects accumulated response text AFTER tokens have
 * already streamed to the client, so substitution is impossible
 * retroactively. On a PII hit the guardrail returns {@code Block},
 * which halts subsequent tokens and surfaces a {@code SecurityException}
 * on the session — earlier bytes are already on the wire. Both default
 * and blocking modes Block on the response path; this is a safety net
 * that limits the leak window and writes the hit to the audit log, not
 * a retroactive redactor. For synchronous per-token scrubbing use
 * {@code org.atmosphere.ai.filter.PiiRedactionFilter} (extends
 * {@code AiStreamBroadcastFilter}) — it runs inside the broadcaster's
 * filter chain and rewrites each text frame before it reaches the
 * client.
 */
public final class PiiRedactionGuardrail implements AiGuardrail {

    private static final Logger logger = LoggerFactory.getLogger(PiiRedactionGuardrail.class);

    /** One compiled regex + its replacement. */
    public record Pattern(java.util.regex.Pattern regex, String replacement, String kind) { }

    private static final List<Pattern> DEFAULT_PATTERNS = List.of(
            // The shared definition, so every PII surface agrees on what an email is.
            new Pattern(org.atmosphere.ai.filter.PiiPatterns.EMAIL,
                    "[redacted-email]", "email"),
            // The shared definition: a number written as a phone, never a bare digit run.
            new Pattern(org.atmosphere.ai.filter.PiiPatterns.PHONE,
                    "[redacted-phone]", "phone"),
            // The shared definition: 13-19 digits that pass the Luhn check, never any large integer.
            new Pattern(org.atmosphere.ai.filter.PiiPatterns.CREDIT_CARD,
                    "[redacted-cc]", "credit-card"),
            new Pattern(java.util.regex.Pattern.compile(
                    "\\b\\d{3}-\\d{2}-\\d{4}\\b"),
                    "[redacted-ssn]", "us-ssn"),
            new Pattern(java.util.regex.Pattern.compile(
                    "\\b(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)){3}\\b"),
                    "[redacted-ip]", "ipv4")
    );

    private final List<Pattern> patterns;
    private final boolean blockOnMatch;

    public PiiRedactionGuardrail() {
        this(DEFAULT_PATTERNS, false);
    }

    public PiiRedactionGuardrail(List<Pattern> patterns, boolean blockOnMatch) {
        this.patterns = List.copyOf(patterns);
        this.blockOnMatch = blockOnMatch;
    }

    /** Extend the default set with additional application-specific patterns. */
    public PiiRedactionGuardrail withPatterns(List<Pattern> additional) {
        var merged = new ArrayList<>(this.patterns);
        merged.addAll(additional);
        return new PiiRedactionGuardrail(merged, blockOnMatch);
    }

    /** Switch behaviour to block-on-match instead of redact-and-pass. */
    public PiiRedactionGuardrail blocking() {
        return new PiiRedactionGuardrail(patterns, true);
    }

    @Override
    public GuardrailResult inspectResponse(String accumulatedResponse) {
        if (accumulatedResponse == null || accumulatedResponse.isEmpty()) {
            return GuardrailResult.pass();
        }
        var kinds = new ArrayList<String>();
        for (var p : patterns) {
            // PiiPatterns.find applies the Luhn check to a credit-card candidate.
            if (org.atmosphere.ai.filter.PiiPatterns.find(p.regex, accumulatedResponse)) {
                kinds.add(p.kind);
            }
        }
        if (kinds.isEmpty()) {
            return GuardrailResult.pass();
        }
        // Both default and blocking modes produce a Block on the response
        // path. The Guardrail SPI inspects the accumulated response text
        // AFTER tokens have already been streamed to the client, so a Block
        // here is an *early termination* — subsequent tokens are suppressed
        // and the session surfaces a SecurityException. It does NOT undo
        // bytes already flushed to the wire (stream writes are
        // irreversible); callers that need synchronous per-token scrubbing
        // register org.atmosphere.ai.filter.PiiRedactionFilter (extends
        // AiStreamBroadcastFilter) — that filter runs inside the
        // broadcaster chain and rewrites each text frame before it is
        // broadcast. Blocking is still strictly better than a pure log
        // signal because it halts the response before more PII can leak
        // and surfaces the hit to the client + audit trail.
        var reason = "response contains PII of kinds: " + kinds;
        if (blockOnMatch) {
            logger.warn("PII detected in response (kinds={}), blocking (explicit blocking mode)", kinds);
        } else {
            logger.warn("PII detected in response (kinds={}), blocking — "
                    + "default behaviour (cannot redact an already-emitted stream)", kinds);
        }
        return GuardrailResult.block(reason);
    }

    @Override
    public GuardrailResult inspectRequest(AiRequest request) {
        if (request == null || request.message() == null) {
            return GuardrailResult.pass();
        }
        var redacted = request.message();
        var kinds = new ArrayList<String>();
        for (var p : patterns) {
            if (org.atmosphere.ai.filter.PiiPatterns.find(p.regex, redacted)) {
                kinds.add(p.kind);
                redacted = org.atmosphere.ai.filter.PiiPatterns.replaceAll(p.regex, redacted, p.replacement);
            }
        }
        if (kinds.isEmpty()) {
            return GuardrailResult.pass();
        }
        if (blockOnMatch) {
            logger.warn("PII detected in request (kinds={}), blocking", kinds);
            return GuardrailResult.block("request contains PII of kinds: " + kinds);
        }
        logger.info("Redacted PII in request (kinds={})", kinds);
        return new GuardrailResult.Modify(request.withMessage(redacted));
    }
}
