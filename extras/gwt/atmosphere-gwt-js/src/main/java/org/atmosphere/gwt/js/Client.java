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

package org.atmosphere.gwt.js;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JavaScriptObject;
import org.atmosphere.gwt.client.AtmosphereClient;
import org.atmosphere.gwt.client.AtmosphereListener;
import org.timepedia.exporter.client.Export;
import org.timepedia.exporter.client.ExportPackage;
import org.timepedia.exporter.client.Exportable;

import java.util.List;

/**
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
    private OnRefresh onRefresh;
    private OnAfterRefresh onAfterRefresh;

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

    @Export
    void post(JavaScriptObject message) {
        impl.post(encodeJSON(message));
    }

    @Export
    void broadcast(JavaScriptObject message) {
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
            if (onRefresh != null) {
                onRefresh.execute();
            }
        }

        @Override
        public void onAfterRefresh() {
            if (onAfterRefresh != null) {
                onAfterRefresh.execute();
            }
        }

        @Override
        public void onMessage(List messages) {
            if (onMessage != null) {
                for (Object m : messages) {
                    onMessage.execute(decodeJSON((String) m));
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

    @Export
    public void setOnAfterRefresh(OnAfterRefresh onAfterRefresh) {
        this.onAfterRefresh = onAfterRefresh;
    }

    @Export
    public void setOnRefresh(OnRefresh onRefresh) {
        this.onRefresh = onRefresh;
    }

    private native String encodeJSON(JavaScriptObject obj) /*-{
        return atmosphere_JSON.encode(obj);
    }-*/;

    private native JavaScriptObject decodeJSON(String json) /*-{
        return atmosphere_JSON.decode(json);
    }-*/;


}
