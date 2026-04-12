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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;

import java.io.IOException;
import java.util.Map;

/**
 * Test handler for multi-modal content wire protocol (Wave 2).
 *
 * <p>Prompt "image" → emits a 1x1 red PNG via {@link Content.Image}.</p>
 * <p>Prompt "audio" → emits a synthetic 1-sample WAV via {@link Content.Audio}.</p>
 * <p>Prompt "file" → emits a CSV file via {@link Content.File}.</p>
 * <p>Prompt "multi" → emits all three in sequence.</p>
 */
public class MultiModalTestHandler implements AtmosphereHandler {

    // 1x1 red PNG (67 bytes)
    private static final byte[] TINY_PNG = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, (byte) 0x90, 0x77, 0x53,
            (byte) 0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
            0x54, 0x08, (byte) 0xD7, 0x63, (byte) 0xF8, (byte) 0xCF,
            (byte) 0xC0, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01,
            (byte) 0xE2, 0x21, (byte) 0xBC, 0x33, 0x00, 0x00, 0x00,
            0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60,
            (byte) 0x82
    };

    // Minimal WAV header + 1 sample of silence (46 bytes)
    private static final byte[] TINY_WAV = buildTinyWav();

    private static final byte[] TINY_CSV = "name,value\ntest,42\n".getBytes();

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("multimodal-test").start(() ->
                    handlePrompt(trimmed, resource));
        }
    }

    private void handlePrompt(String prompt, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);
        try {
            switch (prompt) {
                case "image" -> {
                    session.sendContent(new Content.Image(TINY_PNG, "image/png"));
                    session.emit(new AiEvent.Complete(null, Map.of()));
                }
                case "audio" -> {
                    session.sendContent(new Content.Audio(TINY_WAV, "audio/wav"));
                    session.emit(new AiEvent.Complete(null, Map.of()));
                }
                case "file" -> {
                    session.sendContent(new Content.File(TINY_CSV, "text/csv", "results.csv"));
                    session.emit(new AiEvent.Complete(null, Map.of()));
                }
                case "multi" -> {
                    session.sendContent(new Content.Image(TINY_PNG, "image/png"));
                    Thread.sleep(10);
                    session.sendContent(new Content.Audio(TINY_WAV, "audio/wav"));
                    Thread.sleep(10);
                    session.sendContent(new Content.File(TINY_CSV, "text/csv", "results.csv"));
                    session.emit(new AiEvent.Complete(null, Map.of()));
                }
                default -> {
                    session.emit(new AiEvent.TextDelta("Unknown prompt: " + prompt));
                    session.emit(new AiEvent.Complete(null, Map.of()));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error(e);
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        // handled by StreamingSessions
    }

    @Override
    public void destroy() {
        // no resources to release
    }

    private static byte[] buildTinyWav() {
        var data = new byte[46];
        // RIFF header
        data[0] = 'R'; data[1] = 'I'; data[2] = 'F'; data[3] = 'F';
        writeInt(data, 4, 38); // file size - 8
        data[8] = 'W'; data[9] = 'A'; data[10] = 'V'; data[11] = 'E';
        // fmt chunk
        data[12] = 'f'; data[13] = 'm'; data[14] = 't'; data[15] = ' ';
        writeInt(data, 16, 16); // chunk size
        writeShort(data, 20, 1); // PCM
        writeShort(data, 22, 1); // mono
        writeInt(data, 24, 8000); // sample rate
        writeInt(data, 28, 16000); // byte rate
        writeShort(data, 32, 2); // block align
        writeShort(data, 34, 16); // bits per sample
        // data chunk
        data[36] = 'd'; data[37] = 'a'; data[38] = 't'; data[39] = 'a';
        writeInt(data, 40, 2); // data size (1 sample × 2 bytes)
        data[44] = 0; data[45] = 0; // silence
        return data;
    }

    private static void writeInt(byte[] buf, int offset, int value) {
        buf[offset] = (byte) value;
        buf[offset + 1] = (byte) (value >> 8);
        buf[offset + 2] = (byte) (value >> 16);
        buf[offset + 3] = (byte) (value >> 24);
    }

    private static void writeShort(byte[] buf, int offset, int value) {
        buf[offset] = (byte) value;
        buf[offset + 1] = (byte) (value >> 8);
    }
}
