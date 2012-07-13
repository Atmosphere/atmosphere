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
 * Copyright 2009 Richard Zschech.
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


import com.google.gwt.core.client.JavaScriptException;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Event.NativePreviewEvent;
import com.google.gwt.user.client.Event.NativePreviewHandler;
import com.google.gwt.user.client.Timer;
import com.google.gwt.xhr.client.ReadyStateChangeHandler;
import com.google.gwt.xhr.client.XMLHttpRequest;

/**
 * This class uses a XmlHttpRequest and onreadystatechange events to process stream events.
 * <p/>
 * The main issue with this implementation is that GWT does not generate RECEIVING events from its XMLHTTPRequest. The
 * implementation of XMLHTTPRequest included in this package overrides that behaviour.
 * <p/>
 * Another issues is that the memory required for the XMLHTTPRequest's responseText constantly grows so the server
 * occasionally disconnects the client who then reestablishes the connection.
 * <p/>
 * The protocol for this transport is a '\n' separated transport messages. The different types of transport message are
 * identified by the first character in the line as follows:
 * <p/>
 * ! A connection message followed by the heartbeat duration as an integer
 * <p/>
 * ? A clean server disconnection message
 * <p/>
 * # A heartbeat message
 * <p/>
 * * A padding message to cause the browser to start processing the stream
 * <p/>
 * ] A string message that needs unescaping
 * <p/>
 * | A string message that does not need unescaping
 * <p/>
 * [ A GWT serialized object
 * <p/>
 * R, r or f A GWT deRPC object
 * <p/>
 * string messages are escaped for '\\' and '\n' characters as '\n' is the message separator.
 * <p/>
 * GWT serialized object messages are escaped by GWT so do not need to be escaped by the transport
 *
 * @author Richard Zschech
 */
public class HTTPRequestCometTransport extends StreamingProtocolTransport {

    /**
     * Fix dropping RPC request when hitting ESC in firefox
     * @see http://is.gd/dDeGQ
     */
    static {
        Event.addNativePreviewHandler(new NativePreviewHandler() {
            @Override
            public void onPreviewNativeEvent(NativePreviewEvent e) {
                if (e.getTypeInt() == Event.getTypeInt(KeyDownEvent.getType().getName())) {
                    NativeEvent nativeEvent = e.getNativeEvent();
                    if (nativeEvent.getKeyCode() == KeyCodes.KEY_ESCAPE) {
                        nativeEvent.preventDefault();
                    }
                }
            }
        });
    }

    private final static int POLLING_INTERVAL = 250;

    private XMLHttpRequest xmlHttpRequest;
    private Timer pollingTimer = new Timer() {
        @Override
        public void run() {
            if (xmlHttpRequest != null && read < xmlHttpRequest.getResponseText().length()) {
                onReceiving(xmlHttpRequest.getStatus(), xmlHttpRequest.getResponseText());
            }
        }
    };

    @Override
    public void connect(int connectionCount) {
        init();
        xmlHttpRequest = XMLHttpRequest.create();
        try {
            xmlHttpRequest.open("GET", getUrl(connectionCount));
            xmlHttpRequest.setRequestHeader("Accept", "application/comet");
            xmlHttpRequest.setOnReadyStateChange(new ReadyStateChangeHandler() {
                @Override
                public void onReadyStateChange(XMLHttpRequest request) {
                    if (!aborted) {
                        switch (request.getReadyState()) {
                            case XMLHttpRequest.LOADING:
                                onReceiving(request.getStatus(), request.getResponseText());
                                if (needPolling()) {
                                    pollingTimer.scheduleRepeating(POLLING_INTERVAL);
                                }
                                break;
                            case XMLHttpRequest.DONE:
                                onLoaded(request.getStatus(), request.getResponseText());
                                pollingTimer.cancel();
                                break;
                        }
                    } else {
                        request.clearOnReadyStateChange();
                        if (request.getReadyState() != XMLHttpRequest.DONE) {
                            request.abort();
                        }
                    }
                }
            });
            xmlHttpRequest.send();
        } catch (JavaScriptException e) {
            if (xmlHttpRequest != null) {
                xmlHttpRequest.abort();
                xmlHttpRequest = null;
            }
            listener.onError(new RequestException(e.getMessage()), false);
        }
    }

    @Override
    public void disconnect() {
        aborted = true;
        expectingDisconnection = true;
        super.disconnect();
        if (xmlHttpRequest != null) {
            if (xmlHttpRequest.getReadyState() >= XMLHttpRequest.HEADERS_RECEIVED) {
//                if readystate >= HEADERS_RECEIVED we can abort otherwise wait for this in onReadyStateChange
                xmlHttpRequest.clearOnReadyStateChange();
                if (xmlHttpRequest.getReadyState() != XMLHttpRequest.DONE) {
                    listener.onDisconnected();
                    xmlHttpRequest.abort();
                }
                xmlHttpRequest = null;
            } else {
                new Timer() {
                    XMLHttpRequest r = xmlHttpRequest;

                    @Override
                    public void run() {
                        r.clearOnReadyStateChange();
                        if (r.getReadyState() != XMLHttpRequest.DONE
                                && r.getReadyState() != XMLHttpRequest.UNSENT) {
                            listener.onDisconnected();
                            r.abort();
                        }
                        r = null;
                    }
                }.schedule(5000);
                xmlHttpRequest = null;
            }
        }
    }

    protected boolean needPolling() {
        String ua = getUserAgent();
        return ua.contains("opera") || ua.contains("android 2.");
    }

    /**
     * Returns the browser's user agent.
     *
     * @return the user agent
     */
    public native static String getUserAgent() /*-{
        return $wnd.navigator.userAgent.toLowerCase();
    }-*/;

    private void onLoaded(int statusCode, String responseText) {
        xmlHttpRequest.clearOnReadyStateChange();
        xmlHttpRequest = null;
        onReceiving(statusCode, responseText, false);
    }

    private void onReceiving(int statusCode, String responseText) {
        onReceiving(statusCode, responseText, true);
    }

}
