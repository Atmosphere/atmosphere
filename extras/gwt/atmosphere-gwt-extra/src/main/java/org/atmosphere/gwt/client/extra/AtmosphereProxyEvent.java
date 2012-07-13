package org.atmosphere.gwt.client.extra;

/**
 *
 * @author p.havelaar
 */
public class AtmosphereProxyEvent  {
    public static enum EventType {
        ON_CONNECTED, ON_BEFORE_DISCONNECTED, ON_DISCONNECTED, ON_ERROR, ON_HEARTBEAT, ON_REFRESH, ON_AFTER_REFRESH,
        ON_MESSAGE, POST, BROADCAST, LOCAL_BROADCAST,
        ANNOUNCE_NEW_CHILD, ANNOUNCE_CHILD_DEATH, ELECT_MASTER, ADOPT_ORPHANS, ANNOUNCE_NEW_PARENT;
    }
    private EventType eventType;
    private Object data;
    
    public AtmosphereProxyEvent setEventType(EventType eventType) {
        this.eventType = eventType;
        return this;
    }

    public EventType getEventType() {
        return eventType;
    }

    public Object getData() {
        return data;
    }

    public AtmosphereProxyEvent setData(Object data) {
        this.data = data;
        return this;
    }

} 
