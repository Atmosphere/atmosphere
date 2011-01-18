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

package org.atmosphere.gwt.js;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JavaScriptObject;
import java.io.Serializable;
import java.util.List;
import org.atmosphere.gwt.client.AtmosphereClient;
import org.atmosphere.gwt.client.AtmosphereListener;
import org.timepedia.exporter.client.Export;
import org.timepedia.exporter.client.ExportPackage;
import org.timepedia.exporter.client.Exportable;

/**
 *
 * @author p.havelaar
 */
@ExportPackage("atmosphere")
public class Client implements Exportable {

    private JsSerializer serializer = GWT.create(JsSerializer.class);
    private AtmosphereClient impl;
    private OnMessage onMessage;
    private OnError onError;
    private OnConnected onConnected;
    private OnDisconnected onDisconnected;
    private OnBeforeDisconnected onBeforeDisconnected;
    private OnHeartbeat onHeartbeat;
    
    @Export
    public Client(String url) {
        impl = new AtmosphereClient(url, serializer, listener);
    }

    @Export
    public void start() {
        impl.start();
    }

    @Export
    public void stop() {
        impl.stop();
    }

    @Export void post(JavaScriptObject message) {
        impl.post(encodeJSON(message));
    }

    @Export void broadcast(JavaScriptObject message) {
        impl.broadcast(encodeJSON(message));
    }

    private AtmosphereListener listener = new AtmosphereListener() {
        @Override
        public void onConnected(int heartbeat, int connectionID) {
            if (onConnected != null) {
                onConnected.execute(heartbeat, connectionID);
            }
        }
        @Override
        public void onBeforeDisconnected() {
            if (onBeforeDisconnected != null) {
                onBeforeDisconnected.execute();
            }
        }
        @Override
        public void onDisconnected() {
            if (onDisconnected != null) {
                onDisconnected.execute();
            }
        }
        @Override
        public void onError(Throwable exception, boolean connected) {
            if (onError != null) {
                onError.execute(exception.getMessage(), connected);
            }
        }
        @Override
        public void onHeartbeat() {
            if (onHeartbeat != null) {
                onHeartbeat.execute();
            }
        }
        @Override
        public void onRefresh() {
        }
        @Override
        public void onMessage(List<? extends Serializable> messages) {
            if (onMessage != null) {
                for (Serializable m : messages) {
                    onMessage.execute(decodeJSON((String)m));
                }
            }
        }
    };
    
    @Export
    public void setOnMessage(OnMessage function) {
        onMessage = function;
    }

    @Export
    public void setOnError(OnError function) {
        onError = function;
    }

    @Export
    public void setOnBeforeDisconnected(OnBeforeDisconnected onBeforeDisconnected) {
        this.onBeforeDisconnected = onBeforeDisconnected;
    }

    @Export
    public void setOnConnected(OnConnected onConnected) {
        this.onConnected = onConnected;
    }

    @Export
    public void setOnDisconnected(OnDisconnected onDisconnected) {
        this.onDisconnected = onDisconnected;
    }

    @Export
    public void setOnHeartbeat(OnHeartbeat onHeartbeat) {
        this.onHeartbeat = onHeartbeat;
    }

    private native String encodeJSON(JavaScriptObject obj) /*-{
        return JSON.encode(obj);
    }-*/;

    private native JavaScriptObject decodeJSON(String json) /*-{
        return JSON.decode(json);
    }-*/;

    
}
