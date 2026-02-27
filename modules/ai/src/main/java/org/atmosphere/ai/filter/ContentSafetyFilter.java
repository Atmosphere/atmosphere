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

import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * A {@link AiStreamBroadcastFilter} that scans AI-generated content for harmful
 * patterns and blocks or replaces unsafe content mid-stream.
 *
 * <p>Like {@link PiiRedactionFilter}, this filter buffers tokens into sentence-sized
 * chunks for context-aware scanning. A pluggable {@link SafetyChecker} interface
 * allows custom safety logic — from simple keyword lists to external moderation APIs.</p>
 *
 * <h3>Safety outcomes</h3>
 * <ul>
 *   <li><b>Safe</b> — pass through unchanged</li>
 *   <li><b>Unsafe</b> — abort the entire stream and send an error to the client</li>
 *   <li><b>Redacted</b> — replace the text with a cleaned version and continue streaming</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var checker = ContentSafetyFilter.keywordChecker(Set.of("harmful-term"));
 * broadcaster.getBroadcasterConfig().addFilter(new ContentSafetyFilter(checker));
 * }</pre>
 */
public class ContentSafetyFilter extends AiStreamBroadcastFilter {

    private static final Logger logger = LoggerFactory.getLogger(ContentSafetyFilter.class);
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("[.!?\\n]");

    private final SafetyChecker checker;
    private final ConcurrentHashMap<String, StringBuilder> buffers = new ConcurrentHashMap<>();

    /**
     * Create a content safety filter with the given checker.
     *
     * @param checker the safety checker to evaluate content
     */
    public ContentSafetyFilter(SafetyChecker checker) {
        this.checker = checker;
    }

    /**
     * Pluggable safety check interface. Implementations can use keyword lists,
     * regex patterns, or external moderation APIs.
     */
    public interface SafetyChecker {
        /**
         * Check text for safety.
         *
         * @param text the text to evaluate
         * @return the safety result
         */
        SafetyResult check(String text);
    }

    /**
     * Result of a safety check.
     */
    public sealed interface SafetyResult
            permits SafetyResult.Safe, SafetyResult.Unsafe, SafetyResult.Redacted {

        /** Content is safe — pass through unchanged. */
        record Safe() implements SafetyResult {}

        /** Content is unsafe — abort the stream. */
        record Unsafe(String reason) implements SafetyResult {}

        /** Content has been cleaned — use the replacement text. */
        record Redacted(String cleanText) implements SafetyResult {}
    }

    /**
     * Create a keyword-based safety checker that flags content containing any of
     * the given terms (case-insensitive).
     *
     * @param blockedTerms the set of terms to block
     * @return a safety checker
     */
    public static SafetyChecker keywordChecker(Set<String> blockedTerms) {
        return text -> {
            var lower = text.toLowerCase();
            for (var term : blockedTerms) {
                if (lower.contains(term.toLowerCase())) {
                    return new SafetyResult.Unsafe("Blocked term detected: " + term);
                }
            }
            return new SafetyResult.Safe();
        };
    }

    /**
     * Create a keyword-based safety checker that redacts (replaces) blocked terms
     * instead of aborting the stream.
     *
     * @param blockedTerms the set of terms to redact
     * @param replacement  the text to substitute
     * @return a safety checker
     */
    public static SafetyChecker redactingChecker(Set<String> blockedTerms, String replacement) {
        return text -> {
            var result = text;
            var found = false;
            for (var term : blockedTerms) {
                var pattern = Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE);
                var replaced = pattern.matcher(result).replaceAll(replacement);
                if (!replaced.equals(result)) {
                    found = true;
                    result = replaced;
                }
            }
            return found ? new SafetyResult.Redacted(result) : new SafetyResult.Safe();
        };
    }

    @Override
    protected BroadcastAction filterAiMessage(
            String broadcasterId, AiStreamMessage msg, String originalJson, RawMessage rawMessage) {

        if (msg.isToken() && msg.data() != null) {
            return handleToken(msg);
        }

        if (msg.isComplete() || msg.isError()) {
            return handleStreamEnd(broadcasterId, msg, rawMessage);
        }

        return new BroadcastAction(rawMessage);
    }

    private BroadcastAction handleToken(AiStreamMessage msg) {
        var buffer = buffers.computeIfAbsent(msg.sessionId(), k -> new StringBuilder());
        buffer.append(msg.data());

        if (SENTENCE_BOUNDARY.matcher(msg.data()).find()) {
            var sentence = buffer.toString();
            buffer.setLength(0);
            return evaluateAndEmit(msg, sentence);
        }

        // Buffer — hold the token
        return new BroadcastAction(BroadcastAction.ACTION.ABORT, new RawMessage(msg.toJson()));
    }

    private BroadcastAction evaluateAndEmit(AiStreamMessage msg, String text) {
        var result = checker.check(text);

        return switch (result) {
            case SafetyResult.Safe() -> {
                var modified = msg.withData(text);
                yield new BroadcastAction(new RawMessage(modified.toJson()));
            }
            case SafetyResult.Unsafe(var reason) -> {
                logger.warn("Content safety violation in session {}: {}", msg.sessionId(), reason);
                buffers.remove(msg.sessionId());
                var errorMsg = new AiStreamMessage("error",
                        "Content blocked: " + reason,
                        msg.sessionId(), msg.seq(), null, null);
                yield new BroadcastAction(BroadcastAction.ACTION.SKIP, new RawMessage(errorMsg.toJson()));
            }
            case SafetyResult.Redacted(var cleanText) -> {
                logger.debug("Content redacted in session {}", msg.sessionId());
                var modified = msg.withData(cleanText);
                yield new BroadcastAction(new RawMessage(modified.toJson()));
            }
        };
    }

    private BroadcastAction handleStreamEnd(String broadcasterId, AiStreamMessage msg, RawMessage rawMessage) {
        var buffer = buffers.remove(msg.sessionId());
        if (buffer != null && !buffer.isEmpty()) {
            var text = buffer.toString();
            var result = checker.check(text);

            return switch (result) {
                case SafetyResult.Safe() -> {
                    // Emit buffered text as a proper "token" message, defer the complete
                    var tokenMsg = new AiStreamMessage("token", text, msg.sessionId(), msg.seq(), null, null);
                    deferBroadcast(broadcasterId, rawMessage);
                    yield new BroadcastAction(new RawMessage(tokenMsg.toJson()));
                }
                case SafetyResult.Unsafe(var reason) -> {
                    logger.warn("Content safety violation in buffered text for session {}: {}", msg.sessionId(), reason);
                    var errorMsg = new AiStreamMessage("error",
                            "Content blocked: " + reason,
                            msg.sessionId(), msg.seq(), null, null);
                    yield new BroadcastAction(BroadcastAction.ACTION.SKIP, new RawMessage(errorMsg.toJson()));
                }
                case SafetyResult.Redacted(var cleanText) -> {
                    // Emit redacted text as a proper "token" message, defer the complete
                    var tokenMsg = new AiStreamMessage("token", cleanText, msg.sessionId(), msg.seq(), null, null);
                    deferBroadcast(broadcasterId, rawMessage);
                    yield new BroadcastAction(new RawMessage(tokenMsg.toJson()));
                }
            };
        }
        return new BroadcastAction(rawMessage);
    }

    private void deferBroadcast(String broadcasterId, RawMessage message) {
        var factory = broadcasterFactory();
        Thread.ofVirtual().name("safety-flush").start(() -> {
            try {
                Thread.sleep(50);
                if (factory != null) {
                    var broadcaster = factory.lookup(broadcasterId);
                    if (broadcaster != null) {
                        broadcaster.broadcast(message);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to emit deferred stream-end message: {}", e.getMessage());
            }
        });
    }
}
