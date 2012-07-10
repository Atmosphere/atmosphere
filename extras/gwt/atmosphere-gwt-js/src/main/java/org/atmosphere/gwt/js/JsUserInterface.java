package org.atmosphere.gwt.js;

import com.google.gwt.core.client.JavaScriptObject;
import java.util.List;
import org.atmosphere.gwt.client.AtmosphereListener;
import org.atmosphere.gwt.client.UserInterface;
import org.timepedia.exporter.client.Export;
import org.timepedia.exporter.client.Exportable;

/**
 *
 * @author p.havelaar
 */
abstract public class JsUserInterface implements Exportable {

    protected UserInterface impl;
    protected OnMessage onMessage;
    protected OnError onError;
    protected OnConnected onConnected;
    protected OnDisconnected onDisconnected;
    protected OnBeforeDisconnected onBeforeDisconnected;
    protected OnHeartbeat onHeartbeat;
    protected OnRefresh onRefresh;
    protected OnAfterRefresh onAfterRefresh;
    
    
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
        impl.post(message);
    }

    @Export
    void broadcast(JavaScriptObject message) {
        impl.broadcast(message);
    }

    protected AtmosphereListener listener = new AtmosphereListener() {
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
        public void onMessage(List<?> messages) {
            if (onMessage != null) {
                for (Object m : messages) {
                    onMessage.execute((JavaScriptObject) m);
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

}
