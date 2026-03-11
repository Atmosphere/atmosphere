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
package org.atmosphere.ai.filter;

import org.atmosphere.ai.DefaultStreamingSession;
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * A {@link AiStreamBroadcastFilter} that detects and redacts personally identifiable
 * information (PII) from AI-generated streaming text streams.
 *
 * <p>Since AI streaming texts arrive one or a few words at a time, the filter buffers
 * streaming texts per session until a sentence boundary ({@code .}, {@code !}, {@code ?},
 * or newline) is detected. At that point it scans the buffered sentence for PII patterns,
 * redacts any matches, and emits the cleaned text as a single streaming text message. On
 * stream completion, any remaining buffered text is flushed with redaction applied.</p>
 *
 * <h3>Default patterns</h3>
 * <ul>
 *   <li><b>email</b> — standard email addresses</li>
 *   <li><b>us-phone</b> — US phone numbers (10+ digits, various formats)</li>
 *   <li><b>ssn</b> — US Social Security Numbers (NNN-NN-NNNN)</li>
 *   <li><b>credit-card</b> — credit card numbers (13-19 digits with optional separators)</li>
 * </ul>
 *
 * <p>Custom patterns can be added via {@link #addPattern(String, Pattern)}.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * broadcaster.getBroadcasterConfig().addFilter(new PiiRedactionFilter());
 * }</pre>
 */
public class PiiRedactionFilter extends AiStreamBroadcastFilter {

    private static final Logger logger = LoggerFactory.getLogger(PiiRedactionFilter.class);

    private static final String DEFAULT_REPLACEMENT = "[REDACTED]";

    // Sentence boundary detection
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("[.!?\\n]");

    // Default PII patterns
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    private static final Pattern US_PHONE_PATTERN =
            Pattern.compile("(?:\\+?1[\\s.-]?)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}");
    private static final Pattern SSN_PATTERN =
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern CREDIT_CARD_PATTERN =
            Pattern.compile("\\b(?:\\d[\\s-]?){13,19}\\b");

    private final Map<String, Pattern> patterns = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, StringBuilder> buffers = new ConcurrentHashMap<>();
    private final String replacement;

    /**
     * Create a filter with default PII patterns and "[REDACTED]" replacement text.
     */
    public PiiRedactionFilter() {
        this(DEFAULT_REPLACEMENT);
    }

    /**
     * Create a filter with default PII patterns and a custom replacement string.
     *
     * @param replacement the text to substitute for detected PII
     */
    public PiiRedactionFilter(String replacement) {
        this.replacement = replacement;
        patterns.put("email", EMAIL_PATTERN);
        patterns.put("us-phone", US_PHONE_PATTERN);
        patterns.put("ssn", SSN_PATTERN);
        patterns.put("credit-card", CREDIT_CARD_PATTERN);
    }

    /**
     * Add a custom PII pattern.
     *
     * @param name    a descriptive name for the pattern (for logging)
     * @param pattern the regex pattern to match
     * @return this filter for chaining
     */
    public PiiRedactionFilter addPattern(String name, Pattern pattern) {
        patterns.put(name, pattern);
        return this;
    }

    /**
     * Remove a pattern by name.
     *
     * @param name the pattern name to remove
     * @return this filter for chaining
     */
    public PiiRedactionFilter removePattern(String name) {
        patterns.remove(name);
        return this;
    }

    @Override
    protected BroadcastAction filterAiMessage(
            String broadcasterId, AiStreamMessage msg, String originalJson, RawMessage rawMessage) {

        if (msg.isStreamingText() && msg.data() != null) {
            return handleStreamingText(msg);
        }

        if (msg.isComplete() || msg.isError()) {
            return handleStreamEnd(broadcasterId, msg, rawMessage);
        }

        // Pass through metadata, progress
        return new BroadcastAction(rawMessage);
    }

    private BroadcastAction handleStreamingText(AiStreamMessage msg) {
        var buffer = buffers.computeIfAbsent(msg.sessionId(), k -> new StringBuilder());
        buffer.append(msg.data());

        // Check for sentence boundary
        if (SENTENCE_BOUNDARY.matcher(msg.data()).find()) {
            var sentence = buffer.toString();
            buffer.setLength(0);

            var redacted = redact(sentence);
            if (!redacted.equals(sentence)) {
                logger.debug("PII redacted in session {}", msg.sessionId());
            }

            var modified = msg.withData(redacted);
            return new BroadcastAction(new RawMessage(modified.toJson()));
        }

        // Buffer the streaming text — ABORT so it doesn't reach the client yet
        return new BroadcastAction(BroadcastAction.ACTION.ABORT, rawMessageFor(msg));
    }

    private BroadcastAction handleStreamEnd(String broadcasterId, AiStreamMessage msg, RawMessage rawMessage) {
        var buffer = buffers.remove(msg.sessionId());
        if (buffer != null && !buffer.isEmpty()) {
            var redacted = redact(buffer.toString());
            var streamingTextMsg = new AiStreamMessage("streaming-text", redacted, msg.sessionId(), msg.seq(), null, null);

            // Emit the flushed streaming text as a proper "streaming-text" message to maintain protocol
            // invariant: all text arrives as "streaming-text" type, "complete" is always bare.
            // Bump the terminal message's seq to seq+1 so it doesn't collide with the
            // synthetic flush streaming text and the monotonic sequence invariant is preserved.
            var bumpedTerminal = msg.withSeq(msg.seq() + 1);
            deferBroadcast(broadcasterId, msg.sessionId(), new RawMessage(bumpedTerminal.toJson()));
            return new BroadcastAction(new RawMessage(streamingTextMsg.toJson()));
        }
        return new BroadcastAction(rawMessage);
    }

    private void deferBroadcast(String broadcasterId, String sessionId, RawMessage message) {
        var factory = broadcasterFactory();
        Thread.ofVirtual().name("pii-flush").start(() -> {
            try {
                // Wait for the current filter chain to complete and deliver the flushed streaming text
                Thread.sleep(50);
                if (factory != null) {
                    factory.findBroadcaster(broadcasterId).ifPresent(b -> {
                        var target = DefaultStreamingSession.resourceForSession(sessionId);
                        if (target.isPresent()) {
                            b.broadcast(message, Set.of(target.get()));
                        } else {
                            b.broadcast(message);
                        }
                    });
                }
            } catch (Exception e) {
                logger.warn("Failed to emit deferred stream-end message: {}", e.getMessage());
            }
        });
    }

    /**
     * Apply all PII patterns to the input text.
     *
     * @param text the text to scan
     * @return the text with PII replaced
     */
    String redact(String text) {
        var result = text;
        for (var entry : patterns.entrySet()) {
            result = entry.getValue().matcher(result).replaceAll(replacement);
        }
        return result;
    }

    private static RawMessage rawMessageFor(AiStreamMessage msg) {
        return new RawMessage(msg.toJson());
    }
}
