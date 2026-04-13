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
package org.atmosphere.ai.cache;

import org.atmosphere.ai.AgentExecutionContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes a deterministic cache key from an {@link AgentExecutionContext}.
 * The key is a SHA-256 hex digest over the fields that must match for a
 * cached response to be semantically valid: system prompt, user message,
 * model, conversation history, and tool names.
 *
 * <p>Fields explicitly excluded from the key:</p>
 * <ul>
 *   <li>{@code sessionId}, {@code userId}, {@code conversationId} — identity, not content</li>
 *   <li>{@code approvalStrategy}, {@code listeners} — runtime-scoped, not content</li>
 *   <li>{@code metadata} — except for an explicit cache-scope key when needed</li>
 *   <li>{@code retryPolicy}, {@code approvalPolicy} — behavioral, not content</li>
 * </ul>
 *
 * <p>Multi-modal {@code parts} are included by mime-type + data length only
 * (not byte-for-byte) to keep key computation cheap on large images. This
 * means two different 1024-byte JPEGs with the same mime type will collide
 * — acceptable for the response-cache use case since cache hits on
 * image/audio/file prompts are rare and the alternative (SHA over full
 * binary payload) would dominate key computation time on multi-MB inputs.
 * Callers that need content-sensitive caching on binary parts should hash
 * the payload separately and pass the digest as part of the user
 * message.</p>
 */
public final class CacheKey {

    private CacheKey() {}

    public static String compute(AgentExecutionContext context) {
        var digest = newDigest();
        update(digest, "model", context.model());
        update(digest, "sys", context.systemPrompt());
        update(digest, "msg", context.message());
        if (context.history() != null) {
            for (var m : context.history()) {
                update(digest, "h.r", m.role());
                update(digest, "h.c", m.content());
            }
        }
        if (context.tools() != null) {
            for (var t : context.tools()) {
                update(digest, "t", t.name());
            }
        }
        if (context.parts() != null) {
            for (var p : context.parts()) {
                update(digest, "p", p.getClass().getSimpleName());
                if (p instanceof org.atmosphere.ai.Content.Image img) {
                    update(digest, "p.mime", img.mimeType());
                    update(digest, "p.len", String.valueOf(img.data() != null ? img.data().length : 0));
                } else if (p instanceof org.atmosphere.ai.Content.Audio a) {
                    update(digest, "p.mime", a.mimeType());
                    update(digest, "p.len", String.valueOf(a.data() != null ? a.data().length : 0));
                } else if (p instanceof org.atmosphere.ai.Content.File f) {
                    update(digest, "p.mime", f.mimeType());
                    update(digest, "p.len", String.valueOf(f.data() != null ? f.data().length : 0));
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void update(MessageDigest digest, String label, String value) {
        digest.update(label.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        if (value != null) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
        }
        digest.update((byte) 0);
    }
}
