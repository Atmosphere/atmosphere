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

/**
 * Implementation of this interface allow the customization of the WebSocket message before it gets send.
 */
public interface WebSocketResponseFilter {

    final WebSocketResponseFilter NOOPS_WebSocketResponseFilter = new NoOpsWebSocketResponseFilter();

    /**
     * Transform of filter the message, return a new one or the same
     * @param message the Websocket text message
     * @return a new message
     */
    String filter(String message);

    /**
     * Transform of filter the message, return a new one or the same
     * @param message the Websocket bytes message
     * @return a new message
     */
    byte[] filter(byte[] message);

    public final static class NoOpsWebSocketResponseFilter implements WebSocketResponseFilter {
        @Override
        public String filter(String message) {
            return message;
        }

        @Override
        public byte[] filter(byte[] message) {
            return message;
        }
    }

}
