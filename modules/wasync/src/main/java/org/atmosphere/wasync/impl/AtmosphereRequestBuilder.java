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
package org.atmosphere.wasync.impl;

import org.atmosphere.wasync.Decoder;
import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.RequestBuilder;
import org.atmosphere.wasync.decoder.PaddingAndHeartbeatDecoder;
import org.atmosphere.wasync.decoder.TrackMessageSizeDecoder;

import java.util.UUID;

/**
 * {@link RequestBuilder} for connecting to Atmosphere servers. Adds protocol-specific
 * query parameters and decoders for the Atmosphere protocol handshake.
 *
 * <pre>{@code
 * AtmosphereClient client = AtmosphereClient.create();
 * Request request = client.newRequestBuilder()
 *     .uri("ws://localhost:8080/chat")
 *     .transport(Request.TRANSPORT.WEBSOCKET)
 *     .enableProtocol(true)
 *     .build();
 * }</pre>
 */
public class AtmosphereRequestBuilder extends RequestBuilder<AtmosphereRequestBuilder> {

    /** Atmosphere protocol framework version to advertise. */
    private static final String ATMOSPHERE_FRAMEWORK_VERSION = "4.0";

    private boolean enableProtocol = true;
    private boolean trackMessageLength;
    private String trackMessageLengthDelimiter = "|";
    private String heartbeatChar = "X";
    private int paddingSize = 4098;
    private String trackingId = UUID.randomUUID().toString();

    /**
     * Enable or disable the Atmosphere protocol handshake.
     */
    public AtmosphereRequestBuilder enableProtocol(boolean enable) {
        this.enableProtocol = enable;
        return this;
    }

    /**
     * Enable or disable message length tracking.
     */
    public AtmosphereRequestBuilder trackMessageLength(boolean track) {
        this.trackMessageLength = track;
        return this;
    }

    /**
     * Set the message length tracking delimiter.
     */
    public AtmosphereRequestBuilder trackMessageLengthDelimiter(String delimiter) {
        this.trackMessageLengthDelimiter = delimiter;
        return this;
    }

    /**
     * Set the heartbeat character.
     */
    public AtmosphereRequestBuilder heartbeatChar(String heartbeatChar) {
        this.heartbeatChar = heartbeatChar;
        return this;
    }

    /**
     * Set the padding size.
     */
    public AtmosphereRequestBuilder paddingSize(int paddingSize) {
        this.paddingSize = paddingSize;
        return this;
    }

    @Override
    public Request build() {
        if (transports.isEmpty()) {
            transport(Request.TRANSPORT.WEBSOCKET);
            transport(Request.TRANSPORT.SSE);
            transport(Request.TRANSPORT.STREAMING);
            transport(Request.TRANSPORT.LONG_POLLING);
        }

        // Add Atmosphere protocol query parameters
        queryString("X-Atmosphere-Framework", ATMOSPHERE_FRAMEWORK_VERSION);
        queryString("X-Atmosphere-tracking-id", trackingId);

        if (enableProtocol) {
            queryString("X-atmo-protocol", "true");
        }

        if (trackMessageLength) {
            queryString("X-Atmosphere-TrackMessageSize", "true");
        }

        // Add transport-specific query parameter
        if (!transports.isEmpty()) {
            queryString("X-Atmosphere-Transport", transports.getFirst().name().toLowerCase());
        }

        // Add protocol decoders
        decoder(new PaddingAndHeartbeatDecoder(heartbeatChar));

        if (trackMessageLength) {
            decoder(new TrackMessageSizeDecoder(trackMessageLengthDelimiter, enableProtocol));
        }

        if (enableProtocol) {
            decoder(new AtmosphereProtocolDecoder(this));
        }

        return new DefaultRequest(this);
    }

    String trackingId() {
        return trackingId;
    }

    void trackingId(String trackingId) {
        this.trackingId = trackingId;
    }

    /**
     * Internal decoder that handles the Atmosphere protocol UUID handshake.
     * The first message from the server contains the UUID and protocol configuration.
     */
    static class AtmosphereProtocolDecoder implements Decoder<String, String> {

        private final AtmosphereRequestBuilder builder;
        private volatile boolean handshakeDone;

        AtmosphereProtocolDecoder(AtmosphereRequestBuilder builder) {
            this.builder = builder;
        }

        @Override
        @SuppressWarnings("unchecked")
        public String decode(Event event, String message) {
            if (event != Event.MESSAGE || handshakeDone || message == null) {
                return message;
            }

            handshakeDone = true;

            // Parse protocol response: "UUID|heartbeat|paddingSize" or just "UUID"
            var parts = message.split("\\|");
            if (!parts[0].isEmpty()) {
                builder.trackingId(parts[0]);
            }

            // Absorb the handshake message
            @SuppressWarnings("rawtypes")
            Decoded abort = Decoded.ABORT;
            return ((Decoded<String>) abort).decoded();
        }
    }
}
