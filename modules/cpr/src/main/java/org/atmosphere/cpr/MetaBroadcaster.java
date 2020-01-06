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

import org.atmosphere.inject.AtmosphereConfigAware;
import org.atmosphere.util.ExecutorsFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Broadcast events to all or a subset of available {@link Broadcaster}s based on their {@link org.atmosphere.cpr.Broadcaster#getID()} value.
 * This class allows broadcasting events to a set of broadcasters that maps to some String like:
 * <blockquote><pre>
 *        // Broadcast the event to all Broadcaster ID starting with /hello
 *        broadcast("/hello", event)
 *        // Broadcast the event to all Broadcaster ID
 *        broaccast("/*", event);
 * </pre></blockquote>
 * The rule used is similar to path/URI mapping used by technology like Servlet, Jersey, etc.
 * <p/>
 * NOTE: Broadcasters' name must start with / in order to get retrieved by this class.
 * <p/>
 * This class is NOT thread safe.
 * <p/>
 * If you want to use MetaBroadcaster with Jersey or any framework, make sure all {@link org.atmosphere.cpr.Broadcaster#getID()}
 * starts with '/'. For example, with Jersey:
 * <blockquote><pre>
 *
 * @author Jeanfrancois Arcand
 * @Path(RestConstants.STREAMING + "/workspace{wid:/[0-9A-Z]+}")
 * public class JerseyPubSub {
 * @PathParam("wid") private Broadcaster topic;
 * </pre></blockquote>
 */
public interface MetaBroadcaster extends AtmosphereConfigAware {
    /**
     * Broadcast the message to all Broadcasters whose {@link org.atmosphere.cpr.Broadcaster#getID()} matches the broadcasterID value.
     *
     * @param broadcasterID a String (or path) that can potentially match a {@link org.atmosphere.cpr.Broadcaster#getID()}
     * @param message       a message to be broadcasted
     * @return a Future
     */
    Future<List<Broadcaster>> broadcastTo(String broadcasterID, Object message);

    /**
     * Broadcast the message to all Broadcasters whose {@link org.atmosphere.cpr.Broadcaster#getID()} matches the broadcasterID value.
     *
     * @param broadcasterID a String (or path) that can potentially match a {@link org.atmosphere.cpr.Broadcaster#getID()}
     * @param message       a message to be broadcasted
     * @param cacheMessage  allow the cache to be cached or not.
     * @return a Future
     */
    Future<List<Broadcaster>> broadcastTo(String broadcasterID, Object message, boolean cacheMessage);

    /**
     * Broadcast the message at a fixed rate to all Broadcasters whose {@link org.atmosphere.cpr.Broadcaster#getID()}
     * matches the broadcasterID value. This operation will invoke {@link Broadcaster#scheduleFixedBroadcast(Object, long, java.util.concurrent.TimeUnit)}}
     *
     * @param broadcasterID a String (or path) that can potentially match a {@link org.atmosphere.cpr.Broadcaster#getID()}
     * @param message       a message to be broadcasted
     * @param time          a time value
     * @param unit          a {@link TimeUnit}
     * @return a Future
     */
    Future<List<Broadcaster>> scheduleTo(String broadcasterID, Object message, int time, TimeUnit unit);

    /**
     * Delay the message delivery to Broadcasters whose {@link org.atmosphere.cpr.Broadcaster#getID()}
     * matches the broadcasterID value. This operation will invoke {@link Broadcaster#delayBroadcast(Object, long, java.util.concurrent.TimeUnit)} (Object, long, java.util.concurrent.TimeUnit)}}
     *
     * @param broadcasterID a String (or path) that can potentially match a {@link org.atmosphere.cpr.Broadcaster#getID()}
     * @param message       a message to be broadcasted
     * @param time          a time value
     * @param unit          a {@link TimeUnit}
     * @return a Future
     */
    Future<List<Broadcaster>> delayTo(String broadcasterID, Object message, int time, TimeUnit unit);

    /**
     * Add a {@link BroadcasterListener} to all mapped {@link Broadcaster}s.
     *
     * @param b {@link BroadcasterListener}
     * @return this
     */
    MetaBroadcaster addBroadcasterListener(BroadcasterListener b);

    /**
     * Remove the {@link BroadcasterListener}.
     *
     * @param b {@link BroadcasterListener}
     * @return this
     */
    MetaBroadcaster removeBroadcasterListener(BroadcasterListener b);

    /**
     * Set the {@link MetaBroadcasterCache}. Default is {@link NoCache}.
     *
     * @param cache
     * @return this
     */
    MetaBroadcaster cache(DefaultMetaBroadcaster.MetaBroadcasterCache cache);

    void destroy();


    /**
     * Cache message if no {@link Broadcaster} maps the {@link #broadcastTo(String, Object)}
     */
    public static interface MetaBroadcasterCache {

        /**
         * Cache the Broadcaster ID and message
         *
         * @param path    the value passed to {@link #broadcastTo(String, Object)}
         * @param message the value passed to {@link #broadcastTo(String, Object)}
         * @return this
         */
        public MetaBroadcasterCache cache(String path, Object message);

        /**
         * Flush the Cache.
         *
         * @return this
         */
        public MetaBroadcasterCache flushCache();

    }

    public final static class NoCache implements MetaBroadcasterCache {

        @Override
        public MetaBroadcasterCache cache(String path, Object o) {
            return this;
        }

        @Override
        public MetaBroadcasterCache flushCache() {
            return this;
        }
    }

    /**
     * Flush the cache every 30 seconds.
     */
    public final static class ThirtySecondsCache implements MetaBroadcasterCache, Runnable {

        private final MetaBroadcaster metaBroadcaster;
        private final ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<String, Object>();

        public ThirtySecondsCache(MetaBroadcaster metaBroadcaster, AtmosphereConfig config) {
            this.metaBroadcaster = metaBroadcaster;
            ExecutorsFactory.getScheduler(config).scheduleAtFixedRate(this, 0, 30, TimeUnit.SECONDS);
        }

        @Override
        public MetaBroadcasterCache cache(String path, Object o) {
            cache.put(path, o);
            return this;
        }

        @Override
        public MetaBroadcasterCache flushCache() {
            for (Map.Entry<String, Object> e : cache.entrySet()) {
                metaBroadcaster.broadcastTo(e.getKey(), e.getValue(), false);
            }
            return this;
        }

        @Override
        public void run() {
            flushCache();
            cache.clear();
        }
    }

}
