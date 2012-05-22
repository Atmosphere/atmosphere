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

import com.google.gwt.core.client.JavaScriptObject;

/**
 * @author p.havelaar
 */
public class WebSocket extends JavaScriptObject {


    public enum ReadyState {
        CONNECTING,
        OPEN,
        CLOSED
    }

    /**
     * Creates an WebSocket object.
     *
     * @return the created object
     */
    public static native WebSocket create(String url) /*-{
        if (typeof $wnd.WebSocket != 'undefined') {
            return new WebSocket(url);
        } else if (typeof $wnd.MozWebSocket != 'undefined') {
            return new MozWebSocket(url);
        }
    }-*/;

    public static native WebSocket create(String url, String protocol) /*-{
        if ($wnd.WebSocket) {
            return new WebSocket(url, protocol);
        } else if ($wnd.MozWebSocket) {
            return new MozWebSocket(url, protocol);
        }
    }-*/;

    protected WebSocket() {
    }

    public final native ReadyState getReadyState() /*-{
        return this.readyState;
    }-*/;

    public final native int getBufferedAmount() /*-{
        return this.bufferedAmount;
    }-*/;

    public final native void send(String data) /*-{
        this.send(data);
    }-*/;

    public final native void close() /*-{
        this.close();
    }-*/;

    public final native void setListener(WebSocketListener listener) /*-{
        // The 'this' context is always supposed to point to the WebSocket object in the
        // onreadystatechange handler, but we reference it via closure to be extra sure.
        var self = this;
        this.onopen = $entry(function() {
            listener.@org.atmosphere.gwt.client.impl.WebSocketListener::onOpen(Lorg/atmosphere/gwt/client/impl/WebSocket;)(self);
        });
        this.onclose = $entry(function() {
            listener.@org.atmosphere.gwt.client.impl.WebSocketListener::onClose(Lorg/atmosphere/gwt/client/impl/WebSocket;)(self);
        });
        this.onerror = $entry(function(event) {
            listener.@org.atmosphere.gwt.client.impl.WebSocketListener::onError(Lorg/atmosphere/gwt/client/impl/WebSocket;Ljava/lang/String;)(self, event.data);
        });
        this.onmessage = $entry(function(event) {
            listener.@org.atmosphere.gwt.client.impl.WebSocketListener::onMessage(Lorg/atmosphere/gwt/client/impl/WebSocket;Ljava/lang/String;)(self, event.data);
        });
    }-*/;

    public final native void clearListener() /*-{
        var self = this;
        $wnd.setTimeout(function() {
            // Using a function literal here leaks memory on ie6
            // Using the same function object kills HtmlUnit
            self.onopen = new Function();
            self.onclose = new Function();
            self.onerror = new Function();
            self.onmessage = new Function();
        }, 0);
    }-*/;


    public native static boolean isSupported() /*-{
        return (typeof $wnd.WebSocket != 'undefined')
             || (typeof $wnd.MozWebSocket != 'undefined');
    }-*/;

}
