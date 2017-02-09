/*
 * Copyright 2017 Async-IO.org
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

package org.atmosphere.runtime;

import org.atmosphere.cache.BroadcastMessage;
import org.atmosphere.cache.BroadcasterCacheInspector;
import org.atmosphere.cache.CacheMessage;
import org.atmosphere.cache.DefaultBroadcasterCache;
import org.atmosphere.inject.AtmosphereConfigAware;

import java.util.List;

/**
 * A BroadcasterCache is a cache for broadcasted messages. When a Broadcaster is about to execute a broadcast operation
 * ({@link Broadcaster#broadcast(Object)}, the messages is cached, and the the write operation is executed. If the
 * write operation succeed, the message is removed from the cache. If the write operation fails for an
 * {@link AtmosphereResource}, the message stays in the cache so next time the client reconnects, the message can be sent
 * back to the client. BroadcasterCache is useful for applications that require that no messages are lost, e.g all
 * broadcasted message must be delivered to the client. If your application can survive lost messages, your don't need
 * to install a BroadcasterCache.
 * <p/>
 * A BroadcasterCache works the following way. The methods are always invoked from the application's {@link Broadcaster}.
 * <blockquote><pre>
 *     1. When the Broadcaster is created, a unique BroadcasterCache is created and assigned to it as well. That means
 *     a BroadcasterCache is, by default, associated with a Broadcaster. You can share BroadcasterCache instances among
 *     Broadcasters as well.
 *     2. Just after the constructor has been invoked, the {@link #configure(BroadcasterConfig)} will get invoked, allowing
 *     the instance to configure itself based on a {@link BroadcasterConfig}.
 *     3. When {@link Broadcaster} starts, {@link #start()} will be invoked.
 *     4. Every time a {@link Broadcaster#broadcast(Object)} invocation occurs, the {@link #addToCache(String, String, org.atmosphere.cache.BroadcastMessage)}
 *     method will be invoked, allowing the instance to cache the object.
 *     5. If the write operation succeeds, the {@link #clearCache(String, String, org.atmosphere.cache.CacheMessage)} method will
 *     be invoked. If the write operation fail the cache won't be cleared, and the message will be available next time the
 *     client reconnects. An application that write a BroadcasterCache must make sure cached message aren't staying in the
 *     cache forever to prevent memory leaks.
 *     6. When a client reconnects, the {@link #retrieveFromCache(String, String)} method will be invoked.
 *     If messages are available, a {@link List} will be returned and written back to the client.
 *     7. When messages are added to the cache, an application can always customize the messages by creating {@link BroadcasterCacheInspector}
 *     and add them using {@link #inspector(org.atmosphere.cache.BroadcasterCacheInspector)}. BroadcasterCacheInspector
 *     will be invoked every time {@link #addToCache(String, String, org.atmosphere.cache.BroadcastMessage)} is executed.
 *     8. An application may decide that, at one point in time, stop caching message for a particular {@link AtmosphereResource} by invoking
 *     {@link #excludeFromCache(String, AtmosphereResource)}
 * <p/>
 * </pre></blockquote>
 * <p/>
 * Implementations of this interface must be thread-safe.
 * <p/>
 * A BroadcasterCache can be configured by invoking {@link org.atmosphere.runtime.BroadcasterConfig#setBroadcasterCache(BroadcasterCache)}, by
 * defining it in your web/application.xml or by using the {@link org.atmosphere.config.service.BroadcasterCacheService} annotation.
 *
 * @author Jeanfrancois Arcand
 */
public interface BroadcasterCache extends AtmosphereConfigAware {
    public static final String NULL = "null";

    BroadcasterCache DEFAULT = new DefaultBroadcasterCache();

    /**
     * This method is invoked when the Broadcaster is started.
     */
    void start();

    /**
     * This method is invoked when the Broadcaster is stopped.
     */
    void stop();

    /**
     * Clean resources associated with this instance. This method is useful when ExecutorServices are shared
     * and some future must be cancelled. This method will always be invoked when a {@link Broadcaster} gets destroyed.
     */
    void cleanup();

    /**
     * Start tracking messages associated with {@link AtmosphereResource} from the cache.
     *
     * @param broadcasterId The associated {@link Broadcaster#addAtmosphereResource(AtmosphereResource).getID}
     * @param uuid      {@link AtmosphereResource#uuid}
     * @param message       {@link BroadcastMessage}.
     * @return The {@link CacheMessage}
     */
    CacheMessage addToCache(String broadcasterId, String uuid, BroadcastMessage message);

    /**
     * Retrieve messages associated with {@link AtmosphereResource}.
     *
     *
     * @param id The associated {@link org.atmosphere.runtime.Broadcaster#addAtmosphereResource(org.atmosphere.runtime.AtmosphereResource).getID}
     * @param uuid  {@link org.atmosphere.runtime.AtmosphereResource}
     * @return a {@link List} of messages (String).
     */
    List<Object> retrieveFromCache(String id, String uuid);

    /**
     * Remove the previously cached message.
     *
     * @param broadcasterId The {@link org.atmosphere.runtime.Broadcaster#getID()}
     * @param uuid      an {@link org.atmosphere.runtime.AtmosphereResource#uuid()}
     * @param cache         the {@link CacheMessage}
     */
    BroadcasterCache clearCache(String broadcasterId, String uuid, CacheMessage cache);

    /**
     * Allow an application to exclude, or block, an {@link AtmosphereResource} to received cached message.
     * No new message will get sent to this client except the ones already cached.
     *
     * @param broadcasterId The {@link org.atmosphere.runtime.Broadcaster#getID()}
     * @param r             an {@link AtmosphereResource}
     * @return this
     */
    BroadcasterCache excludeFromCache(String broadcasterId, AtmosphereResource r);

    /**
     * Add a {@link org.atmosphere.runtime.AtmosphereResource#uuid()} to the list of active {@link org.atmosphere.runtime.AtmosphereResource}
     * Message will be cached for the resource associated with the uuid.
     *
     * @param broadcasterId The {@link org.atmosphere.runtime.Broadcaster#getID()}
     * @param uuid          an {@link org.atmosphere.runtime.AtmosphereResource#uuid()}
     * @return this
     */
    BroadcasterCache cacheCandidate(String broadcasterId, String uuid);

    /**
     * Add a {@link BroadcasterCacheInspector} that will be invoked before a message gets added to the cache.
     *
     * @param interceptor an instance of {@link BroadcasterCacheInspector}
     * @return this
     */
    BroadcasterCache inspector(BroadcasterCacheInspector interceptor);

    /**
     * Add a {@link BroadcasterCacheListener}
     *
     * @param l a {@link BroadcasterCacheListener}
     * @return this
     */
    BroadcasterCache addBroadcasterCacheListener(BroadcasterCacheListener l);

    /**
     * Remove a {@link BroadcasterCacheListener}
     *
     * @param l a {@link BroadcasterCacheListener}
     * @return this
     */
    BroadcasterCache removeBroadcasterCacheListener(BroadcasterCacheListener l);
}
