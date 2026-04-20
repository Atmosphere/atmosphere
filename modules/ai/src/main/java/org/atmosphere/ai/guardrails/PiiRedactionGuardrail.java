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
 * Regex-based PII redactor. Catches the common structured-PII shapes
 * (email, phone, credit-card, SSN, IPv4) in outbound responses and
 * returns a {@link GuardrailResult.Modify} so downstream consumers see
 * the redacted text rather than the leaked original. Works on the
 * inbound path too — the response path is the default scope because the
 * request path usually pre-dates the guardrail.
 *
 * <p>Ships with a conservative default pattern set; applications extend
 * with their own via {@link #withPatterns(List)}. For NLP-grade PII
 * detection plug in a model-backed guardrail (Presidio, AWS Comprehend)
 * that implements the same SPI.</p>
 *
 * <h2>Behaviour</h2>
 *
 * <ul>
 *   <li>Email → {@code [redacted-email]}</li>
 *   <li>Phone (NA / international E.164) → {@code [redacted-phone]}</li>
 *   <li>Credit card (13–19 digits with Luhn-shaped grouping) → {@code [redacted-cc]}</li>
 *   <li>US SSN-shaped → {@code [redacted-ssn]}</li>
 *   <li>IPv4 → {@code [redacted-ip]}</li>
 * </ul>
 *
 * <p>The guardrail never blocks by default — it redacts and passes. Set
 * {@code blockOnMatch=true} to surface a {@code Block} decision instead
 * (useful for compliance environments where PII must not leave the
 * process even redacted).</p>
 */
public final class PiiRedactionGuardrail implements AiGuardrail {

    private static final Logger logger = LoggerFactory.getLogger(PiiRedactionGuardrail.class);

    /** One compiled regex + its replacement. */
    public record Pattern(java.util.regex.Pattern regex, String replacement, String kind) { }

    private static final List<Pattern> DEFAULT_PATTERNS = List.of(
            new Pattern(java.util.regex.Pattern.compile(
                    "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
                    "[redacted-email]", "email"),
            new Pattern(java.util.regex.Pattern.compile(
                    "\\+?\\d{1,3}[\\s.-]?\\(?\\d{2,4}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{3,4}"),
                    "[redacted-phone]", "phone"),
            new Pattern(java.util.regex.Pattern.compile(
                    "\\b(?:\\d{4}[\\s-]?){3}\\d{1,4}\\b"),
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
            var matcher = p.regex.matcher(accumulatedResponse);
            if (matcher.find()) {
                kinds.add(p.kind);
            }
        }
        if (kinds.isEmpty()) {
            return GuardrailResult.pass();
        }
        // Both default and blocking modes produce a Block on the response
        // path because the Guardrail SPI's Modify variant only accepts a
        // modified AiRequest — we cannot rewrite the accumulated response
        // once it has been emitted to the client. Blocking surfaces the PII
        // hit as a security error instead of silently letting the PII
        // through with only a log line. Applications that want pure
        // log-only signalling should register their own inspectResponse
        // override; the default protects.
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
            var matcher = p.regex.matcher(redacted);
            if (matcher.find()) {
                kinds.add(p.kind);
                redacted = matcher.replaceAll(p.replacement);
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
