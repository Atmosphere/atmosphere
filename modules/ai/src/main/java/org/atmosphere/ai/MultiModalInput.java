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
package org.atmosphere.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.List;

/**
 * Decodes an inbound {@code @AiEndpoint} prompt frame that carries multi-modal
 * input (image / audio / file) into a text prompt plus a list of typed
 * {@link Content} parts, so the built-in and framework runtimes can thread them
 * onto the outbound model request (e.g. the OpenAI {@code image_url} /
 * {@code input_audio} content blocks). Without this decoder the wire-encoding
 * for image parts already exists in {@code OpenAiCompatibleClient} but has no
 * production feeder — every inbound frame is treated as a plain-text prompt and
 * an uploaded image never reaches the model (only text and audio would, and
 * only if a caller constructed the parts by hand).
 *
 * <p>The inbound envelope mirrors the outbound frame the sessions already emit
 * ({@code DefaultStreamingSession.sendContent}):</p>
 * <pre>{@code
 * {"type":"content","contentType":"image","mimeType":"image/png","data":"<base64>","text":"describe this"}
 * }</pre>
 *
 * <p>The optional {@code text} (or {@code prompt}) field carries the user's
 * accompanying question; when absent the prompt is empty and the model receives
 * an image-only turn.</p>
 *
 * <h2>Safety posture</h2>
 * <ul>
 *   <li><strong>Opt-in (Invariant #6, default-deny):</strong> decoding is off
 *       unless {@link #ENABLED_PROPERTY} / {@link #ENABLED_ENV} is truthy. When
 *       off, {@link #decode(String)} returns the raw message as the prompt with
 *       no parts — byte-identical to the pre-feature text-only path.</li>
 *   <li><strong>Bounded (Invariant #3, backpressure):</strong> a decoded part
 *       larger than {@link #maxBytes()} (default {@value #DEFAULT_MAX_BYTES}
 *       bytes) is rejected with a logged warning rather than accepted — an
 *       unbounded base64 upload is a DoS vector. The rejection is explicit and
 *       observable, never a silent drop.</li>
 *   <li><strong>Boundary-safe (Invariant #4):</strong> malformed JSON or a
 *       corrupt base64 payload never throws out of {@link #decode(String)}; the
 *       frame degrades to the plain-text prompt path with a logged warning.</li>
 * </ul>
 */
public final class MultiModalInput {

    private static final Logger logger = LoggerFactory.getLogger(MultiModalInput.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Sysprop toggling inbound multi-modal decoding on. Env: {@link #ENABLED_ENV}. */
    public static final String ENABLED_PROPERTY = "atmosphere.ai.multimodal-input";
    /** Env var toggling inbound multi-modal decoding on. See {@link #ENABLED_PROPERTY}. */
    public static final String ENABLED_ENV = "LLM_MULTIMODAL_INPUT";

    /** Sysprop capping the decoded byte size of a single part. Env: {@link #MAX_BYTES_ENV}. */
    public static final String MAX_BYTES_PROPERTY = "atmosphere.ai.multimodal-input.max-bytes";
    /** Env var capping the decoded byte size of a single part. See {@link #MAX_BYTES_PROPERTY}. */
    public static final String MAX_BYTES_ENV = "LLM_MULTIMODAL_INPUT_MAX_BYTES";

    /** Default per-part decoded-size ceiling (8 MiB) when the knob is unset. */
    public static final int DEFAULT_MAX_BYTES = 8 * 1024 * 1024;

    private MultiModalInput() {
    }

    /**
     * The result of decoding an inbound frame: the text prompt to dispatch to
     * the {@code @Prompt} method and the multi-modal parts to thread onto the
     * model request. {@code parts} is empty on the plain-text path.
     *
     * @param text  the prompt text (never {@code null}; may be empty)
     * @param parts the decoded multi-modal parts (never {@code null}; may be empty)
     */
    public record Decoded(String text, List<Content> parts) {
        public Decoded {
            text = text != null ? text : "";
            parts = parts != null ? List.copyOf(parts) : List.of();
        }
    }

    /**
     * {@code true} when inbound multi-modal decoding is enabled via the sysprop
     * or env var (sysprop wins). Default {@code false} — the capability is
     * available but off by default so a text-only deployment is never exposed
     * to inbound binary decoding it did not ask for.
     */
    public static boolean isEnabled() {
        var raw = System.getProperty(ENABLED_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(ENABLED_ENV);
        }
        if (raw == null) {
            return false;
        }
        raw = raw.trim();
        return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
    }

    /**
     * The per-part decoded-size ceiling in bytes. Reads {@link #MAX_BYTES_PROPERTY}
     * then {@link #MAX_BYTES_ENV}; a missing or malformed value falls back to
     * {@link #DEFAULT_MAX_BYTES}. A non-positive configured value also falls back
     * — a zero/negative ceiling would reject every part, which is never the
     * intent of a size knob.
     */
    public static int maxBytes() {
        var raw = System.getProperty(MAX_BYTES_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(MAX_BYTES_ENV);
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_BYTES;
        }
        try {
            var parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_MAX_BYTES;
        } catch (NumberFormatException ex) {
            logger.warn("Ignoring malformed {} value '{}' (expected a positive integer); using {} bytes",
                    MAX_BYTES_PROPERTY, raw, DEFAULT_MAX_BYTES);
            return DEFAULT_MAX_BYTES;
        }
    }

    /**
     * Decode an inbound prompt frame. When decoding is disabled, the frame is
     * not a JSON object, or it is not a {@code "type":"content"} envelope, the
     * raw message passes through as the prompt with no parts (the historical
     * text-only behavior). Never throws — malformed input degrades to the
     * text-only path (Invariant #4).
     *
     * @param rawMessage the inbound frame (may be {@code null})
     * @return the decoded prompt text plus parts, never {@code null}
     */
    public static Decoded decode(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            return new Decoded(rawMessage, List.of());
        }
        if (!isEnabled()) {
            return new Decoded(rawMessage, List.of());
        }
        // Fast reject: only a JSON object can be a content envelope. A plain
        // text prompt (the overwhelming common case) short-circuits here without
        // paying for a parse.
        var trimmed = rawMessage.stripLeading();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
            return new Decoded(rawMessage, List.of());
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(rawMessage);
        } catch (RuntimeException ex) {
            logger.warn("Inbound frame is not valid JSON; treating as plain text prompt: {}", ex.toString());
            return new Decoded(rawMessage, List.of());
        }
        if (root == null || !root.isObject()) {
            return new Decoded(rawMessage, List.of());
        }
        if (!"content".equals(stringField(root, "type"))) {
            // Not a multi-modal content envelope (e.g. an approval control frame
            // or an app-specific JSON prompt) — leave it untouched.
            return new Decoded(rawMessage, List.of());
        }
        var contentType = stringField(root, "contentType");
        var mimeType = stringField(root, "mimeType");
        var data = stringField(root, "data");
        var text = stringField(root, "text");
        if (text == null) {
            text = stringField(root, "prompt");
        }
        if (text == null) {
            text = "";
        }
        if (contentType == null || data == null || data.isBlank()) {
            logger.warn("Inbound content frame missing contentType/data; treating text field as plain prompt");
            return new Decoded(text, List.of());
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException ex) {
            logger.warn("Inbound {} frame carries a corrupt base64 payload; dropping the part: {}",
                    contentType, ex.toString());
            return new Decoded(text, List.of());
        }
        var ceiling = maxBytes();
        if (bytes.length > ceiling) {
            // Explicit, logged rejection — never a silent drop (Invariant #3).
            logger.warn("Rejecting inbound {} part of {} bytes: exceeds the {}-byte ceiling ({}). "
                            + "Dispatching the text prompt only.",
                    contentType, bytes.length, ceiling, MAX_BYTES_PROPERTY);
            return new Decoded(text, List.of());
        }
        var part = toContent(contentType, mimeType, bytes, stringField(root, "fileName"));
        if (part == null) {
            logger.warn("Unsupported inbound contentType '{}'; dispatching the text prompt only", contentType);
            return new Decoded(text, List.of());
        }
        return new Decoded(text, List.of(part));
    }

    private static Content toContent(String contentType, String mimeType, byte[] bytes, String fileName) {
        try {
            return switch (contentType.toLowerCase(java.util.Locale.ROOT)) {
                case "image" -> new Content.Image(bytes,
                        mimeType != null && !mimeType.isBlank() ? mimeType : "image/png");
                case "audio" -> new Content.Audio(bytes,
                        mimeType != null && !mimeType.isBlank() ? mimeType : "audio/wav");
                case "file" -> new Content.File(bytes,
                        mimeType != null && !mimeType.isBlank() ? mimeType : "application/octet-stream",
                        fileName != null && !fileName.isBlank() ? fileName : "upload.bin");
                default -> null;
            };
        } catch (IllegalArgumentException ex) {
            // Content records validate their own arguments; a validation failure
            // is a bad frame, not a fatal error (Invariant #4).
            logger.warn("Inbound {} part rejected by Content validation: {}", contentType, ex.toString());
            return null;
        }
    }

    private static String stringField(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isString() ? value.stringValue() : value.asString();
    }
}
