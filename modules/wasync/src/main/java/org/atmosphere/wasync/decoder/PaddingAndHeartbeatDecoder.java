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
package org.atmosphere.wasync.decoder;

import org.atmosphere.wasync.Decoder;
import org.atmosphere.wasync.Event;

/**
 * Decoder that strips Atmosphere server padding and filters out heartbeat messages.
 *
 * <p>Atmosphere servers send whitespace padding at the beginning of a streaming response
 * to flush the response through proxies, and periodically send heartbeat characters to
 * keep the connection alive.</p>
 */
public class PaddingAndHeartbeatDecoder implements Decoder<String, String> {

    private final String heartbeatChar;

    /**
     * Create a decoder with the default heartbeat character ("X").
     */
    public PaddingAndHeartbeatDecoder() {
        this("X");
    }

    /**
     * Create a decoder with a custom heartbeat character.
     *
     * @param heartbeatChar the heartbeat character to filter
     */
    public PaddingAndHeartbeatDecoder(String heartbeatChar) {
        this.heartbeatChar = heartbeatChar;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String decode(Event event, String message) {
        if (event != Event.MESSAGE) {
            return message;
        }

        if (message == null) {
            return null;
        }

        // Strip whitespace padding
        var stripped = message.strip();

        if (stripped.isEmpty()) {
            return ((Decoded<String>) Decoded.ABORT).decoded();
        }

        // Filter heartbeat messages
        if (stripped.equals(heartbeatChar)) {
            return ((Decoded<String>) Decoded.ABORT).decoded();
        }

        return stripped;
    }
}
