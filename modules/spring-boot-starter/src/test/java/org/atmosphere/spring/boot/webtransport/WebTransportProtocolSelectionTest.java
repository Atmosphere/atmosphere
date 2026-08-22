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
package org.atmosphere.spring.boot.webtransport;

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.webtransport.WebTransportProcessor;
import org.atmosphere.webtransport.WebTransportProtocol;
import org.atmosphere.webtransport.WebTransportSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (registre#28): {@code WebTransportProtocol} claimed to mirror
 * {@code WebSocketProtocol} (three production implementations selected by
 * a config knob), but had zero implementations and its
 * {@code WEBTRANSPORT_PROTOCOL} knob was never read. The knob now selects
 * a custom protocol, a broken configuration fails loudly, and unset keeps
 * the WebSocket-bridge delegation.
 */
class WebTransportProtocolSelectionTest {

    /** Recording protocol the knob selects by class name. */
    public static class RecordingProtocol implements WebTransportProtocol {
        static final List<String> MESSAGES = new CopyOnWriteArrayList<>();
        static final List<String> LIFECYCLE = new CopyOnWriteArrayList<>();

        @Override public void configure(AtmosphereConfig config) { LIFECYCLE.add("configure"); }
        @Override public List<AtmosphereRequest> onMessage(WebTransportSession session, String data) {
            MESSAGES.add(data);
            return null; // manual handling
        }
        @Override public List<AtmosphereRequest> onMessage(WebTransportSession session,
                                                           byte[] data, int offset, int length) {
            MESSAGES.add("binary:" + length);
            return null;
        }
        @Override public void onOpen(WebTransportSession session) { LIFECYCLE.add("open"); }
        @Override public void onClose(WebTransportSession session) { LIFECYCLE.add("close"); }
        @Override public void onError(WebTransportSession session,
                                      WebTransportProcessor.WebTransportException t) {
            LIFECYCLE.add("error");
        }
    }

    private AtmosphereFramework framework;

    @AfterEach
    void tearDown() {
        RecordingProtocol.MESSAGES.clear();
        RecordingProtocol.LIFECYCLE.clear();
        if (framework != null) {
            framework.destroy();
        }
    }

    private AtmosphereConfig config(String protocolClass) throws Exception {
        framework = new AtmosphereFramework();
        if (protocolClass != null) {
            framework.addInitParameter(ApplicationConfig.WEBTRANSPORT_PROTOCOL, protocolClass);
        }
        framework.setAsyncSupport(new BlockingIOCometSupport(framework.getAtmosphereConfig()));
        framework.init();
        return framework.getAtmosphereConfig();
    }

    @Test
    void configuredProtocolReceivesMessagesInsteadOfTheBridge() throws Exception {
        var processor = new DefaultWebTransportProcessor();
        processor.configure(config(RecordingProtocol.class.getName()));
        var session = Mockito.mock(WebTransportSession.class);

        processor.invokeWebTransportProtocol(session, "hello-wire");
        processor.invokeWebTransportProtocol(session, new byte[] {1, 2, 3}, 0, 3);
        processor.close(session, 1000);

        assertEquals(List.of("hello-wire", "binary:3"), RecordingProtocol.MESSAGES,
                "the configured protocol must own the message path");
        assertTrue(RecordingProtocol.LIFECYCLE.contains("configure"),
                "the protocol must be configured with the framework config");
        assertTrue(RecordingProtocol.LIFECYCLE.contains("close"));
    }

    @Test
    void brokenProtocolConfigurationFailsLoudly() throws Exception {
        var processor = new DefaultWebTransportProcessor();
        var cfg = config("com.example.NoSuchProtocol");

        assertThrows(IllegalStateException.class, () -> processor.configure(cfg),
                "a configured-but-broken protocol must not silently fall back "
                + "to different wire behaviour");
    }

    @Test
    void unsetKnobKeepsTheWebSocketBridgeDelegation() throws Exception {
        var processor = new DefaultWebTransportProcessor();
        processor.configure(config(null));
        var session = Mockito.mock(WebTransportSession.class);

        // No bridge for the session: the delegation path logs and drops.
        // The recording protocol must NOT be consulted.
        processor.invokeWebTransportProtocol(session, "ignored");

        assertTrue(RecordingProtocol.MESSAGES.isEmpty(),
                "without the knob no custom protocol may be consulted");
    }
}
