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
package org.atmosphere.ai.voice;

import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Voice bridge speech-to-speech loop (P1.4) through the loopback provider. */
class VoiceBridgeTest {

    @Test
    void clientAudioIsBridgedAndProviderAudioReturned() {
        var session = new CapturingSession();
        var bridge = VoiceBridge.open(new LoopbackVoiceProvider(),
                VoiceSessionConfig.of("realtime", "Be brief."), session);

        var mic = "PCMDATA".getBytes(StandardCharsets.UTF_8);
        bridge.onClientAudio(mic);

        assertEquals(1, session.audio.size(), "provider audio must come back as a Content.Audio frame");
        assertArrayEquals(mic, session.audio.get(0).data(),
                "loopback must echo the same audio bytes back to the client");
        assertEquals("audio/pcm;rate=24000", session.audio.get(0).mimeType());
    }

    @Test
    void textTurnBecomesAssistantTranscript() {
        var session = new CapturingSession();
        var bridge = VoiceBridge.open(new LoopbackVoiceProvider(),
                VoiceSessionConfig.of("realtime", ""), session);

        bridge.onClientText("hello");
        assertTrue(session.text.toString().contains("hello"),
                "assistant transcript must stream as text for captioning");
    }

    @Test
    void commitAndCloseSettleTheSession() {
        var session = new CapturingSession();
        var bridge = VoiceBridge.open(new LoopbackVoiceProvider(),
                VoiceSessionConfig.of("realtime", ""), session);

        bridge.commitInput();
        assertTrue(session.text.toString().contains("complete"));

        bridge.close();
        assertTrue(session.completed, "closing the bridge completes the client session");
        // Idempotent — a second close must not double-complete.
        bridge.close();
        assertEquals(1, session.completeCount);
    }

    @Test
    void closedBridgeIgnoresFurtherAudio() {
        var session = new CapturingSession();
        var bridge = VoiceBridge.open(new LoopbackVoiceProvider(),
                VoiceSessionConfig.of("realtime", ""), session);
        bridge.close();
        bridge.onClientAudio("late".getBytes(StandardCharsets.UTF_8));
        assertTrue(session.audio.isEmpty(), "a closed bridge must not forward more audio");
    }

    @Test
    void resolveFallsBackToLoopbackWhenNoRealProvider() {
        var provider = RealtimeVoiceProvider.resolve();
        assertFalse(provider == null);
        assertTrue(provider.isAvailable());
    }

    /** Captures audio frames + text + completion for assertions. */
    private static final class CapturingSession implements StreamingSession {
        private final List<Content.Audio> audio = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private boolean completed;
        private int completeCount;

        @Override public String sessionId() { return "voice-test"; }
        @Override public void send(String t) { text.append(t); }
        @Override public void sendContent(Content content) {
            if (content instanceof Content.Audio a) {
                audio.add(a);
            } else if (content instanceof Content.Text t) {
                send(t.text());
            }
        }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { completed = true; completeCount++; }
        @Override public void complete(String summary) { complete(); }
        @Override public void error(Throwable t) { completed = true; }
        @Override public boolean isClosed() { return completed; }
    }
}
