package org.atmosphere.gwt.client.extra;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.user.client.Window.ClosingEvent;
import com.google.gwt.user.client.Window.ClosingHandler;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.rpc.SerializationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import org.atmosphere.gwt.client.AtmosphereClient;
import org.atmosphere.gwt.client.AtmosphereGWTSerializer;
import org.atmosphere.gwt.client.AtmosphereListener;

import org.atmosphere.gwt.client.JSONObjectSerializer;
import org.atmosphere.gwt.client.UserInterface;
import org.atmosphere.gwt.client.extra.AtmosphereProxyEvent.EventType;

/**
 *
 * @author p.havelaar
 */
public class AtmosphereProxy implements UserInterface {
    private static final String EVENT_SOCKET = "atmosphereProxyEvents";
    
    private static final Logger logger = Logger.getLogger(AtmosphereProxy.class.getName());
    
    private AtmosphereListener clientListener;
    private AtmosphereGWTSerializer serializer;
    private JSONObjectSerializer jsonSerializer = GWT.create(JSONObjectSerializer.class);
    private AtmosphereClient masterConnection = null;
    private String url;
    private WindowSocket eventSocket;
    private List<Window> windowList = new ArrayList<Window>();
    private Window parent;
    
    public AtmosphereProxy(String url, AtmosphereGWTSerializer serializer, AtmosphereListener clientListener) {
        this.url = url;
        this.serializer = serializer;
        this.clientListener = clientListener;
    }
    
    @Override
    public void start() {
        initialize();
    }
    
    @Override
    public void stop() {
        if (parent != null) {
            dispatchEvent(parent, event(EventType.ANNOUNCE_CHILD_DEATH));
        }
        if (windowList.size() > 0) {
            Window fosterParent = parent != null ? parent : windowList.get(0);
            JSONArray arr = new JSONArray();
            List<Window> orphans;
            if (parent == fosterParent) {
                orphans = windowList;
            } else {
                orphans = windowList.subList(1, windowList.size());
            }
            int i = 0;
            for (Window w : orphans) {
                arr.set(i++, new JSONObject(w));
            }
            if (arr.size() > 0) {
                fosterParent.set("orphans", arr.getJavaScriptObject());
            }
            if (masterConnection != null) {
                dispatchEvent(fosterParent, event(EventType.ELECT_MASTER));
                masterConnection.stop();
                masterConnection = null;
            } else if (arr.size() > 0){
                dispatchEvent(fosterParent, event(EventType.ADOPT_ORPHANS));
            }
        }
        parent = null;
        windowList.clear();
    }
    
    // push message back to the server on this connection
    @Override
    public void post(Object message) {
        post(Collections.singletonList(message));
    }

    // push message back to the server on this connection
    @Override
    public void post(List<?> messages) {
        if (masterConnection != null) {
            masterConnection.post(messages);
        } else if (parent != null) {
            for (Object m : messages) {
                dispatchEvent(parent, event(EventType.POST).setData(m));
            }
        } else {
            throw new IllegalStateException("Failed to find master connection for post");
        }
    }

    @Override
    public void post(Object message, AsyncCallback<Void> callback) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void post(List<?> messages, AsyncCallback<Void> callback) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    // send message back to the server on this connection for broadcast
    @Override
    public void broadcast(Object message) {
        broadcast(Collections.singletonList(message));
    }

    // send message back to the server on this connection for broadcast
    @Override
    public void broadcast(List<?> messages) {
        if (masterConnection != null) {
            masterConnection.broadcast(messages);
        } else if (parent != null) {
            for (Object m : messages) {
                dispatchEvent(parent, event(EventType.BROADCAST).setData(m));
            }
        } else {
            throw new IllegalStateException("Failed to find master connection for broadcast");
        }
    }

    // 
    public void localBroadcast(Object message) {
        localBroadcast(Collections.singletonList(message));
    }

    // push message to other windows
    public void localBroadcast(List<?> messages) {
        if (masterConnection != null) {
            masterListener.onMessage(messages);
        } else if (parent != null) {
            for (Object m : messages) {
                dispatchEvent(parent, event(EventType.LOCAL_BROADCAST).setData(m));
            }
        } else {
            throw new IllegalStateException("Failed to find master connection for local broadcast");
        }
    }

    protected void initialize() {
        if (WindowSocket.exists(Window.current(), EVENT_SOCKET)) {
            throw new IllegalStateException("Only one AtmosphereProxy instance is allowed per window");
        }
        
        eventSocket = new WindowSocket();
        eventSocket.addHandler(new WindowSocket.MessageHandler() {
          @Override
          public void onMessage(Window source, String message) {
                AtmosphereProxyEvent event = deserialize(message);
                onEvent(source, event, message);
        }});
        eventSocket.bind(EVENT_SOCKET);
        Window current = Window.current();
        
        if (current.opener() != null && current != current.opener() 
                && WindowSocket.exists(current.opener(), EVENT_SOCKET)) {
            logger.info("Connecting new client window");
            parent = current.opener();
            dispatchEvent(parent, event(EventType.ANNOUNCE_NEW_CHILD));
        } else {
            logger.info("Starting master connection");
            masterConnection = new AtmosphereClient(url, serializer, masterListener);
            masterConnection.start();
            parent = null;
        }
        
        com.google.gwt.user.client.Window.addWindowClosingHandler(new ClosingHandler() {
            @Override
            public void onWindowClosing(ClosingEvent event) {
                AtmosphereProxy.this.onWindowClosing(event);
            }
        });
    }
    
    protected void onWindowClosing(ClosingEvent event) {
        stop();
    }
    
    protected void onEvent(Window source, AtmosphereProxyEvent event, String rawEvent) {
        switch (event.getEventType()) {
            // do not bubble these events
            // they are specificly meant for us
            case ANNOUNCE_NEW_CHILD:
                logger.fine("Adding new child");
                windowList.add(source);
                return;
            case ANNOUNCE_CHILD_DEATH:
                logger.fine("Removing child");
                windowList.remove(source);
                return;
            case ANNOUNCE_NEW_PARENT:
                logger.fine("Set new parent");
                parent = source;
                return;
            case ELECT_MASTER:
                logger.fine("Become new master");
                parent = null;
                masterConnection = new AtmosphereClient(url, serializer, masterListener);
                // same code for adoption
            case ADOPT_ORPHANS:
                JavaScriptObject orphanArray = Window.current().getObject("orphans");
                if (orphanArray != null) {
                    JSONArray arr = new JSONArray(orphanArray);
                    // adopt orphans
                    logger.fine("Adopting " + arr.size() + " orphans");
                    for (int i=0; i < arr.size(); i++) {
                        final Window w = (Window)((JSONObject)arr.get(i)).getJavaScriptObject();
                        windowList.add(w);
                        Scheduler.get().scheduleDeferred(new Scheduler.ScheduledCommand() {
                            @Override
                            public void execute() {
                                dispatchEvent(w, event(EventType.ANNOUNCE_NEW_PARENT));
                            }
                        });
                    }
                    Window.current().remove("orphans");
                }
                if (masterConnection != null) {
                    masterConnection.start();
                }
                return;
                
            // these events need to dispatched to our children as well as to the client listener
            // they originate from the master connection and need to be propagated top-down
            case ON_CONNECTED:
                clientListener.onConnected(-1, -1);
                break;
            case ON_BEFORE_DISCONNECTED:
                clientListener.onBeforeDisconnected();
                break;
            case ON_DISCONNECTED:
                clientListener.onDisconnected();
                break;
            case ON_ERROR:
//                clientListener.onError(new AtmosphereClientException((String)event.getData()), true);
                break;
            case ON_HEARTBEAT:
                clientListener.onHeartbeat();
                break;
            case ON_REFRESH:
                clientListener.onRefresh();
                break;
            case ON_AFTER_REFRESH:
                clientListener.onAfterRefresh();
                break;
            case ON_MESSAGE:
                clientListener.onMessage(Collections.singletonList(event.getData()));
                break;
                
            // these events originate from the client and are meant for the master connection
            case POST:
                if (masterConnection != null) {
                    masterConnection.post(event.getData());
                } else if (parent != null) {
                    // we are not the master propagate up
                    dispatchRawEvent(parent, rawEvent);
                } else {
                    throw new IllegalStateException("Failed to find master connection for post");
                }
                return;
            case BROADCAST:
                if (masterConnection != null) {
                    masterConnection.broadcast(event.getData());
                } else if (parent != null) {
                    // we are not the master propagate up
                    dispatchRawEvent(parent, rawEvent);
                } else {
                    throw new IllegalStateException("Failed to find master connection for broadcast");
                }
                return;
            case LOCAL_BROADCAST:
                if (masterConnection != null) {
                    masterListener.onMessage(Collections.singletonList(event.getData()));
                } else if (parent != null) {
                    // we are not the master propagate up
                    dispatchRawEvent(parent, rawEvent);
                } else {
                    throw new IllegalStateException("Failed to find master connection for local broadcast");
                }
                return;
        }
        // propagate message to children
        dispatchRawEvent(rawEvent);
    }
    
    protected AtmosphereProxyEvent event(AtmosphereProxyEvent.EventType type) {
        return new AtmosphereProxyEvent().setEventType(type);
    }
    
    protected void dispatchEvent(Window target, AtmosphereProxyEvent event) {
        WindowSocket.post(target, EVENT_SOCKET, serialize(event));
    }
    
    protected void dispatchEvent(AtmosphereProxyEvent event) {
        if (windowList.size() > 0) {
            dispatchRawEvent(serialize(event));
        }
    }
    
    protected void dispatchRawEvent(Window target, String event) {
        WindowSocket.post(target, EVENT_SOCKET, event);
    }
    
    protected void dispatchRawEvent(String event) {
        for (Window w : windowList) {
            WindowSocket.post(w, EVENT_SOCKET, event);
        }
    }
    
//    protected String serialize(JsonSerializable object) {
//        try {
//            SerializationStreamWriter writer = new CommandToStringWriter(null);
//            writer.writeObject(object);
//            return writer.toString();
//        } catch (SerializationException ex) {
//            clientListener.onError(ex, true);
//            throw new RuntimeException("Failed to serialize object", ex);
//        }
//    }
    protected String serialize(AtmosphereProxyEvent event) {
        String data;
        if (event.getData() != null) {
            try {
                data = jsonSerializer.serialize(event.getData());
            } catch (SerializationException ex) {
                throw new IllegalStateException(ex);
            }
        } else {
            data = "";
        }
        return event.getEventType().name() + ";" + data;
    }
    
//    protected <T extends JsonSerializable> T deserialize(String data) {
//        try {
//            SerializationStreamReader reader = ClientWriterFactory.createReader(data);
//            return (T) reader.readObject();
//        } catch (SerializationException ex) {
//            clientListener.onError(ex, true);
//            throw new RuntimeException("Failed to deserialize object", ex);
//        }
//    }
     protected AtmosphereProxyEvent deserialize(String data) {
         AtmosphereProxyEvent event = new AtmosphereProxyEvent();
         int pos = data.indexOf(";");
         event.setEventType(EventType.valueOf(data.substring(0, pos)));
         if (pos + 1 < data.length()) {
            try {
                event.setData(jsonSerializer.deserialize(data.substring(pos+1)));
            } catch (SerializationException ex) {
                throw new IllegalStateException(ex);
            }
         }
         return event;
    }
    
    private AtmosphereListener masterListener = new AtmosphereListener() {

        @Override
        public void onConnected(int heartbeat, int connectionID) {
            clientListener.onConnected(heartbeat, connectionID);
            dispatchEvent(event(EventType.ON_CONNECTED));
        }

        @Override
        public void onBeforeDisconnected() {
            clientListener.onBeforeDisconnected();
            dispatchEvent(event(EventType.ON_BEFORE_DISCONNECTED));
        }

        @Override
        public void onDisconnected() {
            clientListener.onDisconnected();
            dispatchEvent(event(EventType.ON_DISCONNECTED));
        }

        @Override
        public void onError(Throwable exception, boolean connected) {
            clientListener.onError(exception, connected);
//            dispatchEvent(event(EventType.ON_ERROR).setData(exception.getMessage()));
        }

        @Override
        public void onHeartbeat() {
            clientListener.onHeartbeat();
            dispatchEvent(event(EventType.ON_HEARTBEAT));
        }

        @Override
        public void onRefresh() {
            clientListener.onRefresh();
            dispatchEvent(event(EventType.ON_REFRESH));
        }

        @Override
        public void onAfterRefresh() {
            clientListener.onAfterRefresh();
            dispatchEvent(event(EventType.ON_AFTER_REFRESH));
        }

        @Override
        public void onMessage(List messages) {
            clientListener.onMessage(messages);
            for (Object m : messages) {
                dispatchEvent(event(EventType.ON_MESSAGE).setData(m));
            }
        }

    };
}
