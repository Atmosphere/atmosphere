/*
 * Copyright 2012 Jeanfrancois Arcand
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
package org.atmosphere.client;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncIOInterceptor;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketResponseFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

/**
 * An {@link org.atmosphere.cpr.AtmosphereInterceptor} that add a add message size and delimiter.
 * <p/>
 * The special String is configurable using {@link org.atmosphere.cpr.ApplicationConfig#MESSAGE_DELIMITER}
 *
 * @author Jeanfrancois Arcand
 */
public class TrackMessageSizeInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(TrackMessageSizeInterceptor.class);
    private final static byte[] END = "|".getBytes();

    private byte[] end = END;
    private String endString = "|";

    @Override
    public void configure(AtmosphereConfig config) {
        String s = config.getInitParameter(ApplicationConfig.MESSAGE_DELIMITER);
        if (s != null) {
            end = s.getBytes();
            endString = s;
        }
    }

    @Override
    public Action inspect(final AtmosphereResource r) {
        final AtmosphereResponse response = r.getResponse();

        if (r.transport() != AtmosphereResource.TRANSPORT.WEBSOCKET) {
            super.inspect(r);

            AsyncIOWriter writer = response.getAsyncIOWriter();
            if (AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())) {
                AtmosphereInterceptorWriter.class.cast(writer).interceptor(new AsyncIOInterceptor() {

                    @Override
                    public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                    }

                    @Override
                    public byte[] transformPayload(byte[] responseDraft, byte[] data) throws IOException {

                        // TODO: This is totally inefficient, I know!
                        String s = new String(responseDraft, response.getCharacterEncoding());
                        if (s.trim().length() != 0) {
                            s = s.length() + endString + s;

                            return s.getBytes(response.getCharacterEncoding());
                        } else {
                            return responseDraft;
                        }
                    }

                    @Override
                    public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                    }
                });
            } else {
                throw new IllegalStateException("AsyncIOWriter must be an instance of " + AsyncIOWriter.class.getName());
            }
        } else {
            ((WebSocket) response.getAsyncIOWriter()).webSocketResponseFilter(new WebSocketResponseFilter() {

                @Override
                public String filter(AtmosphereResponse r, String message) {
                    return message.length() + endString + message;
                }

                @Override
                public byte[] filter(AtmosphereResponse r, byte[] message) {

                    // TODO: This is totally inefficient, I know!
                    String s = null;
                    try {
                        s = new String(message, r.getCharacterEncoding());
                    } catch (UnsupportedEncodingException e) {
                        logger.trace("", e);
                    }
                    s += s.length() + endString + s;

                    try {
                        return s.getBytes(response.getCharacterEncoding());
                    } catch (UnsupportedEncodingException e) {
                        logger.trace("", e);
                    }
                    return message;
                }

                @Override
                public byte[] filter(AtmosphereResponse r, byte[] message, int offset, int length) {
                    // TODO: This is totally inefficient, I know!
                    String s = null;
                    try {
                        s = new String(message, offset, length, r.getCharacterEncoding());
                    } catch (UnsupportedEncodingException e) {
                        logger.trace("", e);
                    }
                    s += s.length() + endString + s;

                    try {
                        return s.getBytes(response.getCharacterEncoding());
                    } catch (UnsupportedEncodingException e) {
                        logger.trace("", e);
                    }
                    return message;
                }
            });
        }
        return Action.CONTINUE;
    }

    @Override
    public String toString() {
        return " Track Message Size Interceptor using " + endString;
    }
}
