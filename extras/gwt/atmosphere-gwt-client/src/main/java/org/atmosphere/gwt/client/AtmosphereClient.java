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
package org.atmosphere.gwt.client;

import com.google.gwt.core.client.Duration;
import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.event.logical.shared.CloseEvent;
import com.google.gwt.event.logical.shared.CloseHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.Window.ClosingEvent;
import com.google.gwt.user.client.rpc.AsyncCallback;
import org.atmosphere.gwt.client.impl.CometTransport;
import org.atmosphere.gwt.client.impl.WebSocketCometTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.atmosphere.gwt.client.impl.CometTransport;

/**
 * This class is the client interface. It will connect to the given url and notify the given 
 * {@link AtmosphereListener} of events. To receive GWT serialized objects supply a {@link AtmosphereGWTSerializer} 
 * class with the appropriate annotations.
 * <p/>
 * The sequence of events are as follows: The application calls {@link AtmosphereClient#start()}.
 * {@link AtmosphereListener#onConnected(int,int)} gets called when the connection is established.
 * {@link AtmosphereListener#onMessage(List)} gets called when messages are received from the server.
 * {@link AtmosphereListener#onDisconnected()} gets called when the connection is disconnected
 * {@link AtmosphereListener#onError(Throwable, boolean)} gets called if there is an error with the connection.
 * For more details about the possible events see {@link AtmosphereListener}.
 * <p/>
 * The client will attempt to maintain a connection when disconnections occur until the application calls
 * {@link AtmosphereClient#stop()}.
 * <p/>
 * The server sends heart beat messages to ensure the connection is maintained and that disconnections can be detected
 * in all cases.
 *
 * @author Richard Zschech
 * @author Pierre Havelaar
 */
public class AtmosphereClient implements UserInterface {

    private enum RefreshState {
        CONNECTING, PRIMARY_DISCONNECTED, REFRESH_CONNECTED, PRIMARY_RECONNECT
    }

    private String url;
    private final AtmosphereGWTSerializer serializer;
    private final AtmosphereListener listener;
    private CometClientTransportWrapper primaryTransport;
    private CometClientTransportWrapper refreshTransport;
    private HandlerRegistration unloadHandlerReg;

    private boolean running;
    private RefreshState refreshState;
    private List<Object> refreshQueue;

    private static final Object REFRESH = new Object();
    private static final Object DISCONNECT = new Object();

    private int connectionCount;

    private int connectionTimeout = 10000;
    private int reconnectionTimeout = 1000;
    private int reconnectionCount = -1;

    private boolean webSocketsEnabled = false;

    private Logger logger = Logger.getLogger(getClass().getName());

    private AsyncCallback<Void> postCallback = new AsyncCallback<Void>() {
        @Override
        public void onFailure(Throwable caught) {
            logger.log(Level.SEVERE, "Failed to post message", caught);
        }

        @Override
        public void onSuccess(Void result) {
            if (logger.isLoggable(Level.FINEST)) {
                logger.log(Level.FINEST, "post succeeded");
            }
        }
    };

    public AtmosphereClient(String url, AtmosphereListener listener) {
        this(url, null, listener);
    }

    public AtmosphereClient(String url, AtmosphereGWTSerializer serializer, AtmosphereListener listener) {
        this(url, serializer, listener, true);
    }

    public AtmosphereClient(String url, AtmosphereGWTSerializer serializer, AtmosphereListener listener, boolean webSocketsEnabled) {
        this.url = url;
        this.serializer = serializer;
        this.listener = listener;
        this.webSocketsEnabled = webSocketsEnabled;

        primaryTransport = new CometClientTransportWrapper();

    }

    public boolean isWebSocketsEnabled() {
        return webSocketsEnabled;
    }

    public void setWebSocketsEnabled(boolean webSocketsEnabled) {
        this.webSocketsEnabled = webSocketsEnabled;
    }
    

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public AtmosphereGWTSerializer getSerializer() {
        return serializer;
    }

    public AtmosphereListener getListener() {
        return listener;
    }

    /**
     * This is the amount of time the client waits until a connection is established. If the connection is 
     * not established within this time the connection is assumed to be dead.
     * 
     * @param connectionTimeout the timeout in milliseconds defaults to 10000
     */
    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    /**
     * This establishes the wait time before a new connection is started.
     * When a connection has dropped this timeout is used to setup a wait time before a new connection is 
     * started. The algorithm will start with the given reconnectionTimeout and will multiply the timeout
     * with the number of failed attempts to reconnect. This will prevent for instance a server that is 
     * rebooted from being flooded with connections.
     * 
     * @see #setReconnectionCount(int)
     * @param reconnectionTimeout 
     */
    public void setReconnectionTimeout(int reconnectionTimeout) {
        this.reconnectionTimeout = reconnectionTimeout;
    }

    public int getReconnectionTimeout() {
        return reconnectionTimeout;
    }

    public int getReconnectionCount() {
        return reconnectionCount;
    }

    /**
     * The amount of times to try to reconnect if a connection has failed.
     * If the count has been reached then client is stopped {@link #stop() } and {@link #isRunning() }
     * will return false.
     * 
     * @param reconnectionCount Set to -1 to keep trying (default -1)
     */
    public void setReconnectionCount(int reconnectionCount) {
        this.reconnectionCount = reconnectionCount;
    }

    /**
     * This is true between {@link #start() } and {@link #stop() }
     * If the connection is failing and the {@link #reconnectionCount} has been reached then stop is also called.
     * @return 
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * The unique connection ID
     * 
     * @return 
     */
    public int getConnectionID() {
        return primaryTransport.connectionID;
    }

    /** 
     * push message back to the server on this connection. On the serverside the message can be received
     * by the {@link AtmosphereGwtHandler#doPost}
     * 
     */
    public void post(Object message) {
        primaryTransport.post(message, postCallback);
    }

    /** 
     * push message back to the server on this connection. On the serverside the message can be received
     * by the {@link AtmosphereGwtHandler#doPost}
     * 
     */
    public void post(Object message, AsyncCallback<Void> callback) {
        primaryTransport.post(message, callback);
    }

    /** 
     * push messages back to the server on this connection. On the serverside the message can be received
     * by the {@link AtmosphereGwtHandler#doPost}
     * 
     */
    public void post(List<?> messages) {
        primaryTransport.post(messages, postCallback);
    }

    /** 
     * push messages back to the server on this connection. On the serverside the message can be received
     * by the {@link AtmosphereGwtHandler#doPost}
     * 
     */
    public void post(List<?> messages, AsyncCallback<Void> callback) {
        primaryTransport.post(messages, callback);
    }

    /** 
     * Send a message back to the server on this connection and use our broadcaster to send this message to
     * other clients connected to the same broadcaster.
     */
    public void broadcast(Object message) {
        primaryTransport.broadcast(message);
    }

    /** 
     * Send a message back to the server on this connection and use our broadcaster to send this message to
     * other clients connected to the same broadcaster.
     */
    public void broadcast(List<?> messages) {
        primaryTransport.broadcast(messages);
    }

    /**
     * This will startup the connection to the server and the client will try to maintain the connection
     * until {@ling #stop()} is called.
     */
    public void start() {
        // avoid spinning mouse cursor in chrome by starting as a deferred command
        Scheduler.get().scheduleDeferred(new Scheduler.ScheduledCommand() {
            @Override
            public void execute() {
                if (!running) {
                    running = true;

                    if (unloadHandlerReg != null) {
                        unloadHandlerReg.removeHandler();
                    }

                    UnloadHandler handler = new UnloadHandler();
                    final HandlerRegistration reg1 = Window.addCloseHandler(handler);
                    final HandlerRegistration reg2 = Window.addWindowClosingHandler(handler);
                    unloadHandlerReg = new HandlerRegistration() {
                        @Override
                        public void removeHandler() {
                            reg1.removeHandler();
                            reg2.removeHandler();
                        }
                    };

                    doConnect();
                }
            }
        });
    }

    private class UnloadHandler implements CloseHandler<Window>, Window.ClosingHandler {

        @Override
        public void onClose(CloseEvent<Window> event) {
            doUnload();
        }

        @Override
        public void onWindowClosing(ClosingEvent event) {
            doUnload();
        }

        private void doUnload() {
            if (!done) {
                done = true;
                logger.log(Level.FINE, "Stopping comet client because of page unload");
                stop();
            }
        }

        private boolean done = false;
    }

    /**
     * End the connection.
     */
    public void stop() {
        if (running) {
            running = false;
            if (unloadHandlerReg != null) {
                unloadHandlerReg.removeHandler();
                unloadHandlerReg = null;
            }
            doDisconnect();
        }
    }

    private void doConnect() {
        primaryTransport.connect();
    }
    
    private void scheduleConnect(CometClientTransportWrapper transport) {
        if (running && transport == primaryTransport) {
            primaryTransport.reconnectionTimer.schedule(reconnectionTimeout);
        } else {
            logger.warning("Schedule for reconnection is called without the primary transport");
        }
    }

    private void doDisconnect() {
        refreshState = null;
        primaryTransport.disconnect();
        if (refreshTransport != null) {
            refreshTransport.disconnect();
        }
    }

    private void doOnConnected(int heartbeat, int connectionID, CometClientTransportWrapper transport) {
        if (refreshState != null) {
            if (transport == refreshTransport) {
                if (refreshState == RefreshState.PRIMARY_DISCONNECTED) {
                    doneRefresh();
                } else if (refreshState == RefreshState.CONNECTING) {
                    primaryTransport.disconnect();
                    doneRefresh();
                } else {
                    throw new IllegalStateException("Unexpected refresh state");
                }
            } else {
                // refreshed connection after connection failed
                if (refreshState != RefreshState.CONNECTING 
                        && refreshState != RefreshState.PRIMARY_RECONNECT) {
                    throw new IllegalStateException("Unexpected refresh state");
                }
                refreshState = null;
                listener.onAfterRefresh();
            }
        } else {
            listener.onConnected(heartbeat, connectionID);
        }
    }


    private void doOnBeforeDisconnected(CometClientTransportWrapper transport) {
        if (refreshState == null && transport == primaryTransport) {
            listener.onBeforeDisconnected();
        }
    }

    private void doOnDisconnected(CometClientTransportWrapper transport) {
        if (refreshState != null) {
            if (transport == primaryTransport) {
                if (refreshState != RefreshState.CONNECTING 
                        && refreshState != RefreshState.PRIMARY_RECONNECT) {
                    throw new IllegalStateException("Unexpected refreshState");
                }
                if (refreshState == RefreshState.PRIMARY_RECONNECT) {
                    scheduleConnect(transport);
                } else {
                    refreshState = RefreshState.PRIMARY_DISCONNECTED;
                    logger.warning("primary disconnected before refresh transport was connected");
                }
            } else {
                // the refresh transport has disconnected
                failedRefresh();
            }
        } else {
            listener.onDisconnected();

            scheduleConnect(transport);
        }
    }

    private void failedRefresh() {
        refreshState = null;
        logger.severe("Failed refesh");
        // dispatch remaining messages;
        if (refreshQueue != null) {
            for (Object object : refreshQueue) {
                if (object == REFRESH || object == DISCONNECT) {
                } else {
                    doOnMessage((List<?>) object, primaryTransport);
                }
            }
            refreshQueue.clear();
        }
        doDisconnect();
        scheduleConnect(primaryTransport);
    }

    @SuppressWarnings("unchecked")
    private void doneRefresh() {
        refreshState = null;
        CometClientTransportWrapper temp = primaryTransport;
        primaryTransport = refreshTransport;
        refreshTransport = temp;
        
        listener.onAfterRefresh();

        if (refreshQueue != null) {
            if (refreshQueue.size() > 0) {
                logger.fine("pushing queued messages");
            }
            for (Object object : refreshQueue) {
                if (object == REFRESH) {
                    doOnRefresh(primaryTransport);
                } else if (object == DISCONNECT) {
                    doOnDisconnected(primaryTransport);
                } else {
                    doOnMessage((List<?>) object, primaryTransport);
                }
            }
            refreshQueue.clear();
        }
    }

    private void doOnHeartbeat(CometClientTransportWrapper transport) {
        if (transport == primaryTransport) {
            listener.onHeartbeat();
        }
    }

    private void doOnRefresh(CometClientTransportWrapper transport) {
        if (refreshState == null && transport == primaryTransport) {
            refreshState = RefreshState.CONNECTING;

            if (refreshTransport == null) {
                refreshTransport = new CometClientTransportWrapper();
            }
            refreshTransport.connect();

            listener.onRefresh();
        } else if (transport == refreshTransport) {
            refreshEnqueue(REFRESH);
        } else {
            throw new IllegalStateException("Unexpected refresh from primaryTransport");
        }
    }
    
    private void refreshEnqueue(Object message) {
        if (refreshQueue == null) {
            refreshQueue = new ArrayList<Object>();
        }
        refreshQueue.add(message);
    }

    private void doOnError(Throwable exception, boolean connected, CometClientTransportWrapper transport) {
        if (connected) {
            transport.disconnect();
        }

        listener.onError(exception, connected);
    }

    private void doOnMessage(List<?> messages, CometClientTransportWrapper transport) {
        if (transport == primaryTransport) {
            listener.onMessage(messages);
        } else if (RefreshState.PRIMARY_DISCONNECTED.equals(refreshState)) {
            refreshEnqueue(messages);
        }
    }

    private class CometClientTransportWrapper implements AtmosphereListener {

        private CometTransport transport;

        private final Timer connectionTimer = createConnectionTimer();
        private final Timer reconnectionTimer = createReconnectionTimer();
        private final Timer heartbeatTimer = createHeartbeatTimer();

        private boolean webSocketSuccessful = false;
        private int heartbeatTimeout;
        private double lastReceivedTime;
        private int connectionID;
        private int reconnectionCounter = 1;

        public CometClientTransportWrapper() {
            // Websocket support not enabled yet

            if (webSocketsEnabled && WebSocketCometTransport.hasWebSocketSupport()) {
                transport = new WebSocketCometTransport();
            } else {
                transport = GWT.create(CometTransport.class);
            }
            logger.info("Created transport: " + transport.getClass().getName());
            transport.initiate(AtmosphereClient.this, this);
        }

        public void post(Object message, AsyncCallback<Void> callback) {
            transport.post(message, callback);
        }

        public void post(List<?> messages, AsyncCallback<Void> callback) {
            transport.post(messages, callback);
        }

        public void broadcast(Object message) {
            transport.broadcast(message);
        }

        public void broadcast(List<?> messages) {
            transport.broadcast(messages);
        }

        public int getConnectionID() {
            return connectionID;
        }

        public void connect() {
            connectionTimer.schedule(connectionTimeout);
            transport.connect(++connectionCount);
        }

        public void disconnect() {
            transport.disconnect();
        }

        @Override
        public void onConnected(int heartbeat, int connectionID) {
            heartbeatTimeout = heartbeat + connectionTimeout;
            lastReceivedTime = Duration.currentTimeMillis();
            this.connectionID = connectionID;
            if (transport instanceof WebSocketCometTransport) {
                webSocketSuccessful = true;
            }

            reconnectionTimer.cancel();
            reconnectionCounter = 1;
            connectionTimer.cancel();
            heartbeatTimer.schedule(heartbeatTimeout);

            doOnConnected(heartbeat, connectionID, this);
        }

        @Override
        public void onBeforeDisconnected() {
            doOnBeforeDisconnected(this);
        }

        @Override
        public void onDisconnected() {
            heartbeatTimer.cancel();
            connectionTimer.cancel();
            if (transport instanceof WebSocketCometTransport && webSocketSuccessful == false) {
                // server doesn't support WebSocket's degrade the connection ...
                logger.info("Server does not support WebSockets");
                transport = GWT.create(CometTransport.class);
                transport.initiate(AtmosphereClient.this, this);
                transport.connect(++connectionCount);
            } else {
                doOnDisconnected(this);
            }
        }

        @Override
        public void onError(Throwable exception, boolean connected) {
            heartbeatTimer.cancel();
            connectionTimer.cancel();
            if (transport instanceof WebSocketCometTransport && webSocketSuccessful == false) {
                if (connected) {
                    transport.disconnect();
                }
            } else {
                doOnError(exception, connected, this);
            }
        }

        @Override
        public void onHeartbeat() {
            lastReceivedTime = Duration.currentTimeMillis();
            doOnHeartbeat(this);
        }

        @Override
        public void onRefresh() {
            lastReceivedTime = Duration.currentTimeMillis();
            doOnRefresh(this);
        }

        @Override
        public void onAfterRefresh() {
            lastReceivedTime = Duration.currentTimeMillis();
        }

        @Override
        public void onMessage(List<?> messages) {
            lastReceivedTime = Duration.currentTimeMillis();
            doOnMessage(messages, this);
        }

        private Timer createConnectionTimer() {
            return new Timer() {
                @Override
                public void run() {
                    doDisconnect();
                    doOnError(new TimeoutException(url, connectionTimeout), false, CometClientTransportWrapper.this);
                }
            };
        }

        private Timer createHeartbeatTimer() {
            return new Timer() {
                @Override
                public void run() {
                    double currentTimeMillis = Duration.currentTimeMillis();
                    double difference = currentTimeMillis - lastReceivedTime;
                    if (difference >= heartbeatTimeout) {
                        doDisconnect();
                        doOnError(new AtmosphereClientException("Heartbeat failed"), false, CometClientTransportWrapper.this);
                    } else {
                        // we have received a message since the timer was
                        // schedule so reschedule it.
                        schedule(heartbeatTimeout - (int) difference);
                    }
                }
            };
        }

        private Timer createReconnectionTimer() {
            return new Timer() {
                @Override
                public void run() {
                    reconnectionCounter++;
                    logger.finest("Running reconnect: count="+ (reconnectionCounter - 1));
                    if (reconnectionCount != -1 && reconnectionCounter - 2 > reconnectionCount) {
                        logger.info("Reconnection attempts exceeded " + reconnectionCount + ". Giving up...");
                        stop();
                    } else if (running) {
                        logger.fine("Starting reconnect");
                        refreshState = RefreshState.PRIMARY_RECONNECT;
                        doConnect();
                    }
                }
                @Override
                public void schedule(int delayMillis) {
                    int delay = delayMillis * (reconnectionCounter < 30 ? reconnectionCounter : 30);
                    logger.finest("Scheduling for reconnect, waiting " + (delay / 1000) + "s");
                    super.cancel();
                    super.schedule(delay);
                }
            };
        }

    }

    // TODO precompile all regexps
    public native static JsArrayString split(String string, String separator) /*-{
        return string.split(separator);
    }-*/;

    /*
      * @Override public void start() { if (!closing) { Window.addWindowCloseListener(windowListener); super.start(); } }
      *
      * @Override public void stop() { super.stop(); Window.removeWindowCloseListener(windowListener); }
      *
      * private WindowCloseListener windowListener = new WindowCloseListener() {
      *
      * @Override public void onWindowClosed() { closing = true; }
      *
      * @Override public String onWindowClosing() { return null; } };
      */
}
