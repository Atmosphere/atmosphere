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
/*
 *
 * With thanks to : http://code.google.com/p/gwt-websockets
 *  Copyright (c)2010 Peter Bridge
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * 
 */
package org.atmosphere.gwt.client.impl;

import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.user.client.rpc.SerializationException;
import com.google.gwt.user.client.rpc.StatusCodeException;
import org.atmosphere.gwt.client.AtmosphereClient;
import org.atmosphere.gwt.client.AtmosphereClientException;

import java.util.Collections;

/**
 * @author p.havelaar
 */
public class WebSocketCometTransport extends BaseCometTransport {

    @Override
    public void connect(int connectionCount) {
        disconnect();
        String url = getUrl(connectionCount);
        url = url.replaceFirst("http://", "ws://");
        url = url.replaceFirst("https://", "wss://");
        socket = WebSocket.create(url);
        socket.setListener(socketListener);
    }

    @Override
    public void disconnect() {
        if (socket != null) {
            super.disconnect();
            socket.close();
        }
        socket = null;
    }

    @Override
    protected ServerTransport getServerTransport() {
        // TODO use WebSocket send. at the moment server does not receive yet
        return super.getServerTransport();
    }

    public static boolean hasWebSocketSupport() {
        return WebSocket.isSupported();
    }

    private boolean connected = false;

    @SuppressWarnings("unused")
    private final void logError(String message) {
        listener.onError(new AtmosphereClientException(message), connected);
    }

    private void parseMessage(String message) {
        if (message.startsWith("s\n")) {
            // a string message
            listener.onMessage(Collections.singletonList(
                    HTTPRequestCometTransport.unescape(message.substring(2))));
        } else if (message.startsWith("o\n")) {
            // a GWT object message
            try {
                listener.onMessage(Collections.singletonList(parse(message.substring(2))));
            } catch (SerializationException e) {
                listener.onError(e, true);
            }
        } else if (message.startsWith("c\n")) {
            // a connection message
            onConnection(message.substring(2));
        }
    }

    private void onConnection(String message) {
        if (message.startsWith("c")) {
            connected = true;
            String initParameters = message.substring(1);
            try {
                String[] params = initParameters.split(":");
                connectionId = Integer.parseInt(params[1]);
                listener.onConnected(Integer.parseInt(params[0]), connectionId);
            } catch (NumberFormatException e) {
                listener.onError(new AtmosphereClientException("Unexpected init parameters: " + initParameters), true);
            }
        } else if (message.startsWith("e")) {
            disconnect();
            String status = message.substring(1);
            try {
                int statusCode;
                String statusMessage;
                int index = status.indexOf(' ');
                if (index == -1) {
                    statusCode = Integer.parseInt(status);
                    statusMessage = null;
                } else {
                    statusCode = Integer.parseInt(status.substring(0, index));
                    statusMessage = HTTPRequestCometTransport.unescape(status.substring(index + 1));
                }
                listener.onError(new StatusCodeException(statusCode, statusMessage), false);
            } catch (NumberFormatException e) {
                listener.onError(new AtmosphereClientException("Unexpected status code: " + status), false);
            }
        } else if (message.equals("d")) {
            disconnect();
        } else if (message.equals("h")) {
            listener.onHeartbeat();
        } else {
            listener.onError(new AtmosphereClientException("Unexpected connection status: " + message), true);
        }
    }


    private WebSocket socket;
    private WebSocketListener socketListener = new WebSocketListener() {

        @Override
        public void onOpen(WebSocket socket) {

        }

        @Override
        public void onClose(WebSocket socket) {
            connected = false;
            listener.onDisconnected();
        }

        @Override
        public void onError(WebSocket socket) {
            connected = false;
            listener.onError(new IllegalStateException("Websocket Connection Error"), false);
        }

        @Override
        public void onMessage(WebSocket socket, String message) {
            JsArrayString messages = AtmosphereClient.split(message, "\n\n");
            int len = messages.length();
            for (int i = 0; i < len; i++) {
                parseMessage(messages.get(i));
            }
        }
    };
}
