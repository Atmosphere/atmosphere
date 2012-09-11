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
package org.atmosphere.websocket;

import org.atmosphere.cpr.AtmosphereResponse;

/**
 * Implementation of this interface allow the customization of the WebSocket message before it gets send.
 *
 * @deprecated - Use {@link }AsyncIOWriter} and {@link AtmosphereInterceptor} instead.
 */
public interface WebSocketResponseFilter {

    final WebSocketResponseFilter NOOPS_WebSocketResponseFilter = new NoOpsWebSocketResponseFilter();

    /**
     * Transform of filter the message, return a new one or the same
     *
     * @param message the Websocket text message
     * @return a new message
     */
    String filter(AtmosphereResponse r, String message);

    /**
     * Transform of filter the message, return a new one or the same
     *
     * @param message the Websocket bytes message
     * @return a new message
     */
    byte[] filter(AtmosphereResponse r, byte[] message);

    /**
     * Transform of filter the message, return a new one or the same
     *
     * @param message the Websocket bytes message
     * @return a new message
     */
    byte[] filter(AtmosphereResponse r, byte[] message, int offset, int length);


    public final static class NoOpsWebSocketResponseFilter implements WebSocketResponseFilter {
        @Override
        public String filter(AtmosphereResponse r, String message) {
            return message;
        }

        @Override
        public byte[] filter(AtmosphereResponse r, byte[] message) {
            return message;
        }

        @Override
        public byte[] filter(AtmosphereResponse r, byte[] message, int offset, int length) {
            // Not used anyway so don't copy bytes.
            return message;
        }
    }

}
