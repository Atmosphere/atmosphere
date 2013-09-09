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
/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.atmosphere.cpr;

import org.atmosphere.cache.BroadcastMessage;
import org.atmosphere.cache.BroadcasterCacheInspector;
import org.atmosphere.cache.CacheMessage;
import org.atmosphere.cache.DefaultBroadcasterCache;

import java.util.List;

/**
 * A BroadcasterCache is cache broadcasted message. When a Broadcaster is about to execute a broadcast operation ({@link Broadcaster#broadcast(Object)},
 * the messages is cached, and the the write operation is executed. If the write operation succeed, the message is removed from the cache. If the write
 * operation fails for an {@link AtmosphereResource}, the message stay in the cache so next time the client reconnect, the message can be
 * send back to the client. BroadcasterCache are useful for application that requires no messge lost, e.g all broadcasted message
 * must be delivered to the client. If your application can survive message's lost, your don't need to install a BroadcasterCache.
 * <p/>
 * BroadcasterCache works the following way. They are always invoked from the application's {@link Broadcaster}
 <blockquote><pre>
 *     1. When the Broadcaster gets created, a unique BroadcasterCache gets created as well. That means a BroadcasterCache is, by default,
 *     associated with a Broadcaster. You can write share BroadcasterCache amongs Broadcaster as well.
 *     2. Just after the constructor has been invoked, the {@link #configure(BroadcasterConfig)} will get invoked, allowing
 *     the instance to configure itself based on a {@link BroadcasterConfig}
 *     3. When {@link Broadcaster} starts, {@link #start()} will be invoked.
 *     4. Every time a {@link Broadcaster#broadcast(Object)} invocation occurs, the {@link #addToCache(String, AtmosphereResource, org.atmosphere.cache.BroadcastMessage)}
 *     method will be invoked, allowing the instance to cache the object.
 *     5. If the write operation succeed, the {@link #clearCache(String, AtmosphereResource, org.atmosphere.cache.CacheMessage)} method will
 *     be invoked. If the write operation fail, that means the cache won't be cleared, and the message will be available next time the
 *     client reconnect. An application that write a BroadcasterCache must makes sure cached message aren't staying in the cache forever
 *     to prevent memory leaks.
 *     6. When a client reconnects, the {@link #retrieveFromCache(String, AtmosphereResource)} method will be invoked. If messages are
 *     available, a {@link List} will be returned and written back to the client.
 *     7. When messages are added to the cache, an application can always customize the messages by creating {@link BroadcasterCacheInspector}
 *     and add them using {@link #inspector(org.atmosphere.cache.BroadcasterCacheInspector)}. BroadcasterCacheInspector
 *     will be invoked every time {@link #addToCache(String, AtmosphereResource, org.atmosphere.cache.BroadcastMessage)} is executed.
 *     8. An application may decide that, at one point in time, stops caching message for a particular {@link AtmosphereResource} by invoking
 *     {@link #excludeFromCache(String, AtmosphereResource)}
 *
 </pre></blockquote>
 *
 * Implementation of this interface must be thread-safe.
 *
 * A BroadcasterCache can be configured by invoking {@link org.atmosphere.cpr.BroadcasterConfig#setBroadcasterCache(BroadcasterCache)} by
 * defining it in your web/application.xml or by using the {@link org.atmosphere.config.service.BroadcasterCacheService}
 *
 * @author Jeanfrancois Arcand
 */
public interface BroadcasterCache {

    BroadcasterCache DEFAULT = new DefaultBroadcasterCache();

    /**
     * Start
     */
    void start();

    /**
     * Stop
     */
    void stop();

    /**
     * Clean resource associated with this instance. This method is useful when used when ExecutorServices are shared
     * and some future must be cancelled. This method will always be invoked when a {@link Broadcaster} gets destroyed.
     */
    void cleanup();

    /**
     * Configure the cache
     *
     * @param config a {@link BroadcasterConfig}
     */
    void configure(BroadcasterConfig config);

    /**
     * Start tracking messages associated with {@link AtmosphereResource} from the cache
     *
     * @param broadcasterId The associated {@link Broadcaster#addAtmosphereResource(AtmosphereResource).getID}
     * @param r             {@link AtmosphereResource}
     * @param e             {@link BroadcastMessage}.
     * @return The {@link CacheMessage}
     */
    CacheMessage addToCache(String broadcasterId, AtmosphereResource r, BroadcastMessage e);

    /**
     * Retrieve messages associated with {@link AtmosphereResource}
     *
     * @param id The associated {@link Broadcaster#addAtmosphereResource(AtmosphereResource).getID}
     * @param r  {@link AtmosphereResource}
     * @return a {@link List} of messages (String).
     */
    List<Object> retrieveFromCache(String id, AtmosphereResource r);

    /**
     * Remove the previously cached message.
     * @param broadcasterId The {@link org.atmosphere.cpr.Broadcaster#getID()}
     * @param r an {@link AtmosphereResource}
     * @param cache the {@link CacheMessage}
     */
    void clearCache(String broadcasterId, AtmosphereResource r, CacheMessage cache);

    /**
     * Allow an application to exclude, or block, an {@link AtmosphereResource} to received cached message. No new message will get sent to this client except the ones already cached.
     * @param broadcasterId The {@link org.atmosphere.cpr.Broadcaster#getID()}
     * @param r an {@link AtmosphereResource}
     */
    void excludeFromCache(String broadcasterId, AtmosphereResource r);

    /**
     * Add a {@link BroadcasterCacheInspector} that will be invoked before a message gets added to the cache.
     *
     * @param interceptor  an instance of {@link BroadcasterCacheInspector}
     * @return this
     */
    BroadcasterCache inspector(BroadcasterCacheInspector interceptor);

}
