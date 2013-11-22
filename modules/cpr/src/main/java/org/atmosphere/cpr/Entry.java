/*
 * Copyright 2013 Jeanfrancois Arcand
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

import java.util.Set;

public class Entry {

    public enum TYPE {RESOURCE, SET, ALL}

    public Object message;
    public BroadcasterFuture<?> future;
    public boolean writeLocally;
    public Object originalMessage;
    public final AtmosphereResource resource;
    public final Set<AtmosphereResource> resources;
    public final TYPE type;
    // https://github.com/Atmosphere/atmosphere/issues/864
    public CacheMessage cache;
    public boolean async;

    public Entry(TYPE type,
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


    public Entry(Object message, AtmosphereResource r, BroadcasterFuture<?> future, Object originalMessage) {
        this(TYPE.RESOURCE, originalMessage, message, r, future, null, true, null, true);
    }

    public Entry(Object message, BroadcasterFuture<?> future, Object originalMessage) {
        this(TYPE.ALL, originalMessage, message, null, future, null, true, null, true);
    }

    public Entry(AtmosphereResource r, Entry e) {
        this(TYPE.RESOURCE, e.originalMessage, e.message, r, e.future, e.cache, e.writeLocally, null, e.async);
    }

    public Entry(Object message, Set<AtmosphereResource> resources, BroadcasterFuture<?> future, Object originalMessage) {
        this(TYPE.SET, originalMessage, message, null, future, null, true, resources, true);
    }

    public Entry(Object message, BroadcasterFuture<?> future, boolean writeLocally) {
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
}
