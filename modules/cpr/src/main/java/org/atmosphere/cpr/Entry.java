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

    public Entry(Object message, AtmosphereResource r, BroadcasterFuture<?> future, Object originalMessage) {
        this.message = message;
        this.future = future;
        this.writeLocally = true;
        this.originalMessage = originalMessage;

        this.type = TYPE.RESOURCE;
        this.resource = r;
        this.resources = null;
    }

    public Entry(Object message, BroadcasterFuture<?> future, Object originalMessage) {
        this.message = message;
        this.future = future;
        this.writeLocally = true;
        this.originalMessage = originalMessage;

        this.type = TYPE.ALL;
        this.resource = null;
        this.resources = null;
    }

    public Entry(AtmosphereResource r, Entry e) {
        this.message = e.message;
        this.future = e.future;
        this.writeLocally = true;
        this.originalMessage = e.originalMessage;

        this.type = TYPE.RESOURCE;
        this.resource = r;
        this.resources = null;
    }

    public Entry(Object message, Set<AtmosphereResource> resources, BroadcasterFuture<?> future, Object originalMessage) {
        this.message = message;
        this.future = future;
        this.writeLocally = true;
        this.originalMessage = message;

        this.type = TYPE.SET;
        this.resources = resources;
        this.resource = null;
    }

    public Entry(Object message, BroadcasterFuture<?> future, boolean writeLocally) {
        this.message = message;
        this.future = future;
        this.writeLocally = writeLocally;
        this.originalMessage = message;

        this.type = TYPE.ALL;
        this.resources = null;
        this.resource = null;
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
