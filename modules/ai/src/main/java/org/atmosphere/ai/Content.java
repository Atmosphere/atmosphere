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

import java.util.Base64;

/**
 * Multi-modal content that can be sent through a {@link StreamingSession}.
 *
 * <p>Sealed hierarchy covering the common content types returned by modern LLMs.
 * Wire protocol uses {@code "type":"content"} with a {@code "contentType"} discriminator:</p>
 * <pre>{@code
 * {"type":"content","contentType":"text","data":"Hello","sessionId":"abc","seq":1}
 * {"type":"content","contentType":"image","mimeType":"image/png","data":"<base64>","sessionId":"abc","seq":2}
 * {"type":"content","contentType":"file","mimeType":"text/csv","fileName":"results.csv","data":"<base64>","sessionId":"abc","seq":3}
 * }</pre>
 *
 * @see StreamingSession#sendContent(Content)
 */
public sealed interface Content {

    /**
     * Text content — the most common type.
     */
    record Text(String text) implements Content {
        public Text {
            if (text == null) {
                throw new IllegalArgumentException("text must not be null");
            }
        }
    }

    /**
     * Binary image content.
     */
    record Image(byte[] data, String mimeType) implements Content {
        public Image {
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("image data must not be null or empty");
            }
            if (mimeType == null || mimeType.isBlank()) {
                throw new IllegalArgumentException("mimeType must not be null or blank");
            }
            // Defensive copy on construction so a caller that mutates the
            // source byte[] after building the Image cannot poison the
            // record's state. The {@code data()} accessor still returns
            // the internal reference — callers that need to mutate the
            // backing array must construct a new Image.
            data = data.clone();
        }

        /** Base64-encoded data for wire transfer. */
        public String dataBase64() {
            return Base64.getEncoder().encodeToString(data);
        }
    }

    /**
     * Binary audio content (e.g. WAV/MP3/OGG uploaded by the user or produced
     * by a model). Phase 4 of the unified {@code @Agent} API adds this variant
     * so runtimes declaring {@link AiCapability#AUDIO} have a concrete input
     * type to translate.
     */
    record Audio(byte[] data, String mimeType) implements Content {
        public Audio {
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("audio data must not be null or empty");
            }
            if (mimeType == null || mimeType.isBlank()) {
                throw new IllegalArgumentException("mimeType must not be null or blank");
            }
            data = data.clone();
        }

        /** Base64-encoded data for wire transfer. */
        public String dataBase64() {
            return Base64.getEncoder().encodeToString(data);
        }
    }

    /**
     * Binary file content with a filename.
     */
    record File(byte[] data, String mimeType, String fileName) implements Content {
        public File {
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("file data must not be null or empty");
            }
            if (mimeType == null || mimeType.isBlank()) {
                throw new IllegalArgumentException("mimeType must not be null or blank");
            }
            if (fileName == null || fileName.isBlank()) {
                throw new IllegalArgumentException("fileName must not be null or blank");
            }
            data = data.clone();
        }

        /** Base64-encoded data for wire transfer. */
        public String dataBase64() {
            return Base64.getEncoder().encodeToString(data);
        }
    }

    // -- Factory methods --

    /** Create text content. */
    static Content text(String text) {
        return new Text(text);
    }

    /** Create image content. */
    static Content image(byte[] data, String mimeType) {
        return new Image(data, mimeType);
    }

    /** Create audio content. */
    static Content audio(byte[] data, String mimeType) {
        return new Audio(data, mimeType);
    }

    /** Create file content. */
    static Content file(byte[] data, String mimeType, String fileName) {
        return new File(data, mimeType, fileName);
    }
}
