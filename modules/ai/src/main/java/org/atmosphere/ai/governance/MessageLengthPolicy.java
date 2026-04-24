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

/**
 * Cap the length of the incoming user message. Over-cap messages are denied
 * at admission so no tokens are spent on them. Two use cases:
 *
 * <ul>
 *   <li><b>Cost control</b>: unbounded prompt length is an unbounded bill.
 *       A 20 KB prompt to GPT-4 is ~5000 tokens of input alone. A modest
 *       5 KB cap caps the per-turn blast radius.</li>
 *   <li><b>Long-context attacks</b>: stuffing the prompt with "forget
 *       previous instructions" repeated 1000 times, or pasting entire
 *       documents as social engineering context, defeats many lightweight
 *       scope / classifier checks.</li>
 * </ul>
 *
 * <p>Counts {@link String#length()} (UTF-16 code units) rather than tokens
 * — tokenization requires runtime knowledge that doesn't belong in a
 * policy. The cap is in characters; operators choose a cap that leaves
 * comfortable headroom for their token budget.</p>
 *
 * <p>Post-response: always admits. Response truncation is a runtime concern
 * (already handled by {@code max_tokens} at the LLM layer), not a policy one.</p>
 */
public final class MessageLengthPolicy implements GovernancePolicy {

    private final String name;
    private final String source;
    private final String version;
    private final int maxChars;

    public MessageLengthPolicy(String name, int maxChars) {
        this(name, "code:" + MessageLengthPolicy.class.getName(), "1", maxChars);
    }

    public MessageLengthPolicy(String name, String source, String version, int maxChars) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be > 0, got: " + maxChars);
        }
        this.name = name;
        this.source = source;
        this.version = version;
        this.maxChars = maxChars;
    }

    @Override public String name() { return name; }
    @Override public String source() { return source; }
    @Override public String version() { return version; }

    public int maxChars() { return maxChars; }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        if (context.phase() == PolicyContext.Phase.POST_RESPONSE) {
            return PolicyDecision.admit();
        }
        var request = context.request();
        if (request == null || request.message() == null) {
            return PolicyDecision.admit();
        }
        int len = request.message().length();
        if (len > maxChars) {
            return PolicyDecision.deny("message length " + len
                    + " exceeds maximum " + maxChars + " characters");
        }
        return PolicyDecision.admit();
    }
}
