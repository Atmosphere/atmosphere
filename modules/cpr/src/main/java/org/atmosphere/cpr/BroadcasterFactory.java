/*
 * Copyright 2008-2020 Async-IO.org
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

/**
 * Factory for {@link Broadcaster} used by Atmosphere when creating broadcasters.
 *
 * @author Jeanfrancois Arcand
 */
public interface BroadcasterFactory {

    /**
     * Configure the factory
     *
     * @param clazz                      {@link org.atmosphere.cpr.Broadcaster}
     * @param broadcasterLifeCyclePolicy {@link org.atmosphere.cpr.BroadcasterLifeCyclePolicy}
     * @param c                          {@link org.atmosphere.cpr.AtmosphereConfig}
     */
    void configure(Class<? extends Broadcaster> clazz, String broadcasterLifeCyclePolicy, AtmosphereConfig c);

    /**
     * Return an instance of the default {@link Broadcaster}.
     * <p/>
     * The name of the Broadcaster will be randomly generated.
     *
     * @return an instance of the default {@link Broadcaster}
     */
    Broadcaster get();

    /**
     * Create a new instance of {@link Broadcaster} and store it for.
     *
     * @param id The unique ID used to retrieve {@link Broadcaster}
     * @return a new instance of {@link Broadcaster}
     */
    Broadcaster get(Object id);

    /**
     * Create a new instance of {@link Broadcaster} and store it for.
     *
     * @param c  The {@link Broadcaster} class instance.
     * @param id The unique ID used to retrieve {@link Broadcaster}
     * @return a new instance of {@link Broadcaster}
     */
    <T extends Broadcaster> T get(Class<T> c, Object id);

    /**
     * Shutdown all {@link Broadcaster}s.
     */
    void destroy();

    /**
     * Add a {@link Broadcaster} to the list.
     *
     * @param b a {@link Broadcaster}
     * @return false if a with the same name {@link Broadcaster} was already stored
     */
    boolean add(Broadcaster b, Object id);

    /**
     * Remove a {@link Broadcaster} from the list.
     *
     * @param b  a {@link Broadcaster}
     * @param id the {@link Broadcaster's ID}
     * @return false if wasn't present, or {@link Broadcaster}
     */
    boolean remove(Broadcaster b, Object id);

    /**
     * Lookup a {@link Broadcaster} instance using {@link Broadcaster#getID()} or ID
     *
     * @param c
     * @param id The Broadcaster's unique ID, or name.
     * @return a Broadcaster, or null if not found.
     */
    <T extends Broadcaster> T lookup(Class<T> c, Object id);

    /**
     * Lookup a {@link Broadcaster} instance using {@link Broadcaster#getID()} or ID
     *
     * @param c
     * @param id           The Broadcaster's unique ID, or name.
     * @param createIfNull If the broadcaster is not found, create it.
     * @return a Broadcaster, or null if not found.
     */
    <T extends Broadcaster> T lookup(Class<T> c, Object id, boolean createIfNull);

    /**
     * Lookup a {@link Broadcaster} instance using {@link Broadcaster#getID()} or ID
     *
     * @param id The Broadcaster's unique ID, or name.
     * @return a Broadcaster, or null if not found.
     */
    <T extends Broadcaster> T lookup(Object id);

    /**
     * Lookup a {@link Broadcaster} instance using {@link Broadcaster#getID()} or ID
     *
     * @param id           The Broadcaster's unique ID, or name.
     * @param createIfNull If the broadcaster is not found, create it.
     * @return a Broadcaster, or null if not found.
     */
    <T extends Broadcaster> T lookup(Object id, boolean createIfNull);

    /**
     * Remove all instances of {@link AtmosphereResource} from all registered {@link Broadcaster}s.
     *
     * @param r an void {@link AtmosphereResource}
     *
     */
    @Deprecated
    void removeAllAtmosphereResource(AtmosphereResource r);

    /**
     * Remove the associated {@link Broadcaster}.
     */
    boolean remove(Object id);

    /**
     * Return an immutable Collection of {@link Broadcaster} this factory contains.
     *
     * @return an immutable Collection of {@link Broadcaster} this factory contains.
     */
    Collection<Broadcaster> lookupAll();

    /**
     * Add a {@link org.atmosphere.cpr.BroadcasterListener}
     *
     * @param b a {@link org.atmosphere.cpr.BroadcasterListener}
     * @return this
     */
    BroadcasterFactory addBroadcasterListener(BroadcasterListener b);

    /**
     * Remove a {@link org.atmosphere.cpr.BroadcasterListener}
     *
     * @param b a {@link org.atmosphere.cpr.BroadcasterListener}
     * @return this
     */
    BroadcasterFactory removeBroadcasterListener(BroadcasterListener b);

    /**
     * Return all {@link org.atmosphere.cpr.BroadcasterListener}
     *
     * @return {@link org.atmosphere.cpr.BroadcasterListener}
     */
    Collection<BroadcasterListener> broadcasterListeners();

    final class BroadcasterCreationException extends RuntimeException {
        BroadcasterCreationException(Throwable t) {
            super(t);
        }
    }

}
