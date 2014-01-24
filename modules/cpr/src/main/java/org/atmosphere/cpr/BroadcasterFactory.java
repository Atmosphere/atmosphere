/*
 * Copyright 2014 Jeanfrancois Arcand
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

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Factory for {@link Broadcaster} used by Atmosphere when creating broadcasters.
 *
 * @author Jeanfrancois Arcand
 */
public abstract class BroadcasterFactory {

    protected static BroadcasterFactory factory;
    protected static AtmosphereConfig config;
    protected final ConcurrentLinkedQueue<BroadcasterListener> broadcasterListeners = new ConcurrentLinkedQueue<BroadcasterListener>();

    /**
     * Return an instance of the default {@link Broadcaster}.
     * <p/>
     * The name of the Broadcaster will be randomly generated.
     *
     * @return an instance of the default {@link Broadcaster}
     * @throws IllegalAccessException
     * @throws InstantiationException
     */
    abstract public Broadcaster get();

    /**
     * Create a new instance of {@link Broadcaster} and store it for.
     *
     * @param id The unique ID used to retrieve {@link Broadcaster}
     * @return a new instance of {@link Broadcaster}
     */
    abstract public Broadcaster get(Object id);

    /**
     * Create a new instance of {@link Broadcaster} and store it for.
     *
     * @param c  The {@link Broadcaster} class instance.
     * @param id The unique ID used to retrieve {@link Broadcaster}
     * @return a new instance of {@link Broadcaster}
     */
    abstract public <T extends Broadcaster> T get(Class<T> c, Object id);

    /**
     * Shutdown all {@link Broadcaster}s.
     */
    abstract public void destroy();

    /**
     * Add a {@link Broadcaster} to the list.
     *
     * @param b a {@link Broadcaster}
     * @return false if a with the same name {@link Broadcaster} was already stored
     */
    abstract public boolean add(Broadcaster b, Object id);

    /**
     * Remove a {@link Broadcaster} from the list.
     *
     * @param b  a {@link Broadcaster}
     * @param id the {@link Broadcaster's ID}
     * @return false if wasn't present, or {@link Broadcaster}
     */
    abstract public boolean remove(Broadcaster b, Object id);

    /**
     * Lookup a {@link Broadcaster} instance using {@link Broadcaster#getID()} or ID
     * used when invoking {@link BroadcasterFactory#getDefault()}.
     *
     * @param c
     * @param id The Broadcaster's unique ID, or name.
     * @return a Broadcaster, or null if not found.
     */
    abstract public <T extends Broadcaster> T lookup(Class<T> c, Object id);

    /**
     * Lookup a {@link Broadcaster} instance using {@link Broadcaster#getID()} or ID
     * used when invoking {@link BroadcasterFactory#getDefault()}.
     *
     * @param c
     * @param id           The Broadcaster's unique ID, or name.
     * @param createIfNull If the broadcaster is not found, create it.
     * @return a Broadcaster, or null if not found.
     */
    abstract public <T extends Broadcaster> T lookup(Class<T> c, Object id, boolean createIfNull);

    /**
     * Lookup a {@link Broadcaster} instance using {@link Broadcaster#getID()} or ID
     * used when invoking {@link BroadcasterFactory#getDefault()}.
     *
     * @param id The Broadcaster's unique ID, or name.
     * @return a Broadcaster, or null if not found.
     */
    abstract public <T extends Broadcaster> T lookup(Object id);

    /**
     * Lookup a {@link Broadcaster} instance using {@link Broadcaster#getID()} or ID
     * used when invoking {@link BroadcasterFactory#getDefault()}.
     *
     * @param id           The Broadcaster's unique ID, or name.
     * @param createIfNull If the broadcaster is not found, create it.
     * @return a Broadcaster, or null if not found.
     */
    abstract public <T extends Broadcaster> T lookup(Object id, boolean createIfNull);

    /**
     * Remove all instances of {@link AtmosphereResource} from all registered {@link Broadcaster}s.
     *
     * @param r an void {@link AtmosphereResource}
     */
    abstract public void removeAllAtmosphereResource(AtmosphereResource r);

    /**
     * Remove the associated {@link Broadcaster}.
     */
    abstract public boolean remove(Object id);

    /**
     * Return an immutable Collection of {@link Broadcaster} this factory contains.
     *
     * @return an immutable Collection of {@link Broadcaster} this factory contains.
     */
    abstract public Collection<Broadcaster> lookupAll();

    /**
     * Return the default {@link BroadcasterFactory}.
     *
     * @return the default {@link BroadcasterFactory}.
     */
    public synchronized static BroadcasterFactory getDefault() {
        return factory;
    }

    public BroadcasterFactory addBroadcasterListener(BroadcasterListener b) {
        if (!broadcasterListeners.contains(b)) {
            broadcasterListeners.add(b);
        }
        return this;
    }

    public BroadcasterFactory removeBroadcasterListener(BroadcasterListener b) {
        broadcasterListeners.remove(b);
        return this;
    }

    static void setBroadcasterFactory(BroadcasterFactory f, AtmosphereConfig c) {
        factory = f;
        config = c;
    }
}
