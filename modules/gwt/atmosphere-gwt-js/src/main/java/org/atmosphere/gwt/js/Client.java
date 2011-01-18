/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
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
