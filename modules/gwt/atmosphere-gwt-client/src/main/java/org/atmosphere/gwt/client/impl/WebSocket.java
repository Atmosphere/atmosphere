/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */
package org.atmosphere.gwt.client.impl;

import com.google.gwt.core.client.JavaScriptObject;

/**
 *
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
        return new WebSocket(url);
    }-*/;

    public static native WebSocket create(String url, String protocol) /*-{
        return new WebSocket(url, protocol);
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
        this.onerror = $entry(function() {
            listener.@org.atmosphere.gwt.client.impl.WebSocketListener::onError(Lorg/atmosphere/gwt/client/impl/WebSocket;)(self);
        });
        this.onmessage = $entry(function(event) {
            listener.@org.atmosphere.gwt.client.impl.WebSocketListener::onMessage(Lorg/atmosphere/gwt/client/impl/WebSocket;Ljava/lang/String;)(self,event.data);
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
        if (!$wnd.WebSocket) {
            return false;
        } else {
            return true;
        }
    }-*/;

}
