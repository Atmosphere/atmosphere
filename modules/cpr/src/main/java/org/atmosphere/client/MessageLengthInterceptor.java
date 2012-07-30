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

import org.atmosphere.cpr.*;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketResponseFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * An {@link AtmosphereInterceptor} that add a special String "|" at the end of a message, allowing the
 * atmosphere.js to detect if one or several messages where aggregated in one write operations.
 * <p/>
 * The special String is configurable using {@link ApplicationConfig#MESSAGE_DELIMITER}
 *
 * @author Jeanfrancois Arcand
 */
public class MessageLengthInterceptor extends AtmosphereInterceptorAdapter {

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
                    public void prePayload(AtmosphereResponse response, String data) {
                    }

                    @Override
                    public void prePayload(AtmosphereResponse response, byte[] data) {
                    }

                    @Override
                    public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                    }

                    @Override
                    public void transformPayload(ByteArrayOutputStream response, String data) throws IOException {
                        response.write(data.getBytes());
                    }

                    @Override
                    public void transformPayload(ByteArrayOutputStream response, byte[] data) throws IOException {
                        response.write(data);
                    }

                    @Override
                    public void transformPayload(ByteArrayOutputStream response, byte[] data, int offset, int length) throws IOException {
                        response.write(data);
                    }

                    @Override
                    public void postPayload(AtmosphereResponse response, String data) {
                        response.write(end);
                    }

                    @Override
                    public void postPayload(AtmosphereResponse response, byte[] data) {
                        response.write(end);
                    }

                    @Override
                    public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                        response.write(end);
                    }
                });
            } else {
                throw new IllegalStateException("AsyncIOWriter must be an instance of " + AsyncIOWriter.class.getName());
            }
        } else {
            ((WebSocket) response.getAsyncIOWriter()).webSocketResponseFilter(new WebSocketResponseFilter() {

                @Override
                public String filter(AtmosphereResponse r, String message) {
                    return message + endString;
                }

                @Override
                public byte[] filter(AtmosphereResponse r, byte[] message) {

                    byte[] nb = new byte[message.length + end.length];
                    System.arraycopy(message, 0, nb, 0, message.length);
                    System.arraycopy(end, 0, nb, message.length, nb.length);

                    return nb;
                }

                @Override
                public byte[] filter(AtmosphereResponse r, byte[] message, int offset, int length) {
                    byte[] nb = new byte[length + end.length];
                    System.arraycopy(message, offset, nb, 0, length);
                    System.arraycopy(end, 0, nb, length, nb.length);

                    return nb;
                }
            });
        }
        return Action.CONTINUE;
    }

    @Override
    public String toString() {
        return endString + " End Message Interceptor";
    }
}
