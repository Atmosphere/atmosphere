/*
 * Copyright 2015 Async-IO.org
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
package org.atmosphere.cpr;

import org.atmosphere.cache.CacheMessage;

import java.io.Serializable;
import java.util.Set;

/**
 * A Deliver is an {@link Broadcaster}'s internal token that is created before the message gets Broadcaster. A Deliver gives information about
 * what will be delivered, to whow, etc.
 *
 * @author Jeanfrancois Arcand
 */
public class Deliver implements Serializable {
    private static final long serialVersionUID = -126253550299206646L;

    public enum TYPE {RESOURCE, SET, ALL}

    protected Object message;
    protected BroadcasterFuture<?> future;
    protected boolean writeLocally;
    protected Object originalMessage;
    protected final AtmosphereResource resource;
    protected final Set<AtmosphereResource> resources;
    protected final TYPE type;
    // https://github.com/Atmosphere/atmosphere/issues/864
    protected CacheMessage cache;
    protected boolean async;

    public Deliver(TYPE type,
                   Object originalMessage,
                   Object message,
                   AtmosphereResource r,
                   BroadcasterFuture<?> future,
                   CacheMessage cache,
                   boolean writeLocally,
                   Set<AtmosphereResource> resources,
                   boolean async) {

        this.message = message;
        this.future = future;
        this.writeLocally = writeLocally;
        this.originalMessage = originalMessage;

        this.type = type;
        this.resource = r;
        this.cache = cache;
        this.resources = resources;
        this.async = async;
    }


    public Deliver(Object message, AtmosphereResource r, BroadcasterFuture<?> future, Object originalMessage) {
        this(TYPE.RESOURCE, originalMessage, message, r, future, null, true, null, true);
    }

    public Deliver(Object message, BroadcasterFuture<?> future, Object originalMessage) {
        this(TYPE.ALL, originalMessage, message, null, future, null, true, null, true);
    }

    public Deliver(AtmosphereResource r, Deliver e) {
        this(TYPE.RESOURCE, e.originalMessage, e.message, r, e.future, e.cache, e.writeLocally, null, e.async);
    }

    public Deliver(AtmosphereResource r, Deliver e, CacheMessage cacheMessage) {
        this(TYPE.RESOURCE, e.originalMessage, e.message, r, e.future, cacheMessage, e.writeLocally, null, e.async);
    }

    public Deliver(Object message, Set<AtmosphereResource> resources, BroadcasterFuture<?> future, Object originalMessage) {
        this(TYPE.SET, originalMessage, message, null, future, null, true, resources, true);
    }

    public Deliver(Object message, BroadcasterFuture<?> future, boolean writeLocally) {
        this(TYPE.ALL, message, message, null, future, null, writeLocally, null, true);
    }

    @Override
    public String toString() {
        return "Entry{" +
                "message=" + message +
                ", type=" + type +
                ", future=" + future +
                '}';
    }

    public Object getMessage() {
        return message;
    }

    public void setMessage(Object message) {
        this.message = message;
    }

    public BroadcasterFuture<?> getFuture() {
        return future;
    }

    public void setFuture(BroadcasterFuture<?> future) {
        this.future = future;
    }

    public boolean isWriteLocally() {
        return writeLocally;
    }

    public void setWriteLocally(boolean writeLocally) {
        this.writeLocally = writeLocally;
    }

    public Object getOriginalMessage() {
        return originalMessage;
    }

    public void setOriginalMessage(Object originalMessage) {
        this.originalMessage = originalMessage;
    }

    public AtmosphereResource getResource() {
        return resource;
    }

    public Set<AtmosphereResource> getResources() {
        return resources;
    }

    public TYPE getType() {
        return type;
    }

    public CacheMessage getCache() {
        return cache;
    }

    public void setCache(CacheMessage cache) {
        this.cache = cache;
    }

    public boolean isAsync() {
        return async;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }
}
