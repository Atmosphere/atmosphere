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
package org.atmosphere.gwt.client.impl;

import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.rpc.SerializationException;
import com.google.gwt.user.client.rpc.StatusCodeException;
import org.atmosphere.gwt.client.AtmosphereClient;
import org.atmosphere.gwt.client.AtmosphereClientException;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * @author p.havelaar
 */
abstract public class StreamingProtocolTransport extends BaseCometTransport {

    private static final String SEPARATOR = "\n";

    protected boolean aborted;
    protected boolean expectingDisconnection;
    protected int read;

    public void init() {
        aborted = false;
        expectingDisconnection = false;
        read = 0;
    }

    protected void onReceiving(int statusCode, String responseText, boolean connected) {
        if (statusCode != Response.SC_OK) {
            if (!connected) {
                expectingDisconnection = true;
                listener.onError(new StatusCodeException(statusCode, responseText), connected);
            }
        } else {
            int index = responseText.lastIndexOf(SEPARATOR);
            if (index > read) {
                List<Serializable> messages = new ArrayList<Serializable>();
                JsArrayString data = AtmosphereClient.split(responseText.substring(read, index), SEPARATOR);
                int length = data.length();
                for (int i = 0; i < length; i++) {
                    if (aborted) {
                        return;
                    }
                    parse(data.get(i), messages);
                }
                read = index + 1;
                if (!messages.isEmpty()) {
                    listener.onMessage(messages);
                }
            }

            if (!connected) {
                if (expectingDisconnection) {
                    listener.onDisconnected();
                } else {
                    listener.onError(new AtmosphereClientException("Unexpected disconnection"), false);
                }
            }
        }
    }

    private void parse(String message, List messages) {
        if (expectingDisconnection) {
            listener.onError(new AtmosphereClientException("Expecting disconnection but received message: " + message), true);
        } else if (message.isEmpty()) {
            listener.onError(new AtmosphereClientException("Invalid empty message received"), true);
        } else {
            char c = message.charAt(0);
            switch (c) {
                case '!':
                    String initParameters = message.substring(1);
                    try {
                        String[] params = initParameters.split(":");
                        connectionId = Integer.parseInt(params[1]);
                        listener.onConnected(Integer.parseInt(params[0]), connectionId);
                    } catch (NumberFormatException e) {
                        listener.onError(new AtmosphereClientException("Unexpected init parameters: " + initParameters), true);
                    }
                    break;
                case '?':
                    // clean disconnection
                    expectingDisconnection = true;
                    break;
                case '#':
                    listener.onHeartbeat();
                    break;
                case '@':
                    listener.onRefresh();
                    break;
                case '*':
                    // ignore padding
                    break;
                case '|':
                    messages.add(message.substring(1));
                    break;
                case ']':
                    messages.add(unescape(message.substring(1)));
                    break;
                case '[':
                case 'R':
                case 'r':
                case 'f':
                    try {
                        messages.add(parse(message));
                    } catch (SerializationException e) {
                        listener.onError(e, true);
                    }
                    break;
                default:
                    listener.onError(new AtmosphereClientException("Invalid message received: " + message), true);
            }
        }
    }

    static String unescape(String string) {
        return string.replace("\\n", "\n").replace("\\\\", "\\");
    }
}
