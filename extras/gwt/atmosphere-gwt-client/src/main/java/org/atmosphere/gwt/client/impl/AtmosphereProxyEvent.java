package org.atmosphere.gwt.client.impl;

import java.io.Serializable;

/**
 *
 * @author p.havelaar
 */
public class AtmosphereProxyEvent {
    public static enum EventType {
        ON_CONNECTED, ON_BEFORE_DISCONNECTED, ON_DISCONNECTED, ON_ERROR, ON_HEARTBEAT, ON_REFRESH, ON_AFTER_REFRESH,
        ON_MESSAGE, POST, BROADCAST,
        ANNOUNCE_NEW_CHILD, ANNOUNCE_CHILD_DEATH, ELECT_MASTER, ADOPT_ORPHANS, ANNOUNCE_NEW_PARENT;
    }
    
    private EventType eventType = EventType.ON_ERROR;
    private Serializable data;
    
    public AtmosphereProxyEvent() {
    }
    
    public AtmosphereProxyEvent(EventType type) {
        this.eventType = type;
    }

    public AtmosphereProxyEvent setEventType(EventType eventType) {
        this.eventType = eventType;
        return this;
    }

    public EventType getEventType() {
        return eventType;
    }

    public Serializable getData() {
        return data;
    }

    public AtmosphereProxyEvent setData(Serializable data) {
        this.data = data;
        return this;
    }

    
} 
