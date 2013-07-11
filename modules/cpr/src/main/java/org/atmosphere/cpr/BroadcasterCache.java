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
 * A BroadcasterCache is used to persist broadcasted Object {@link Broadcaster#broadcast(Object)}. Disconnected clients
 * can always retrieve messages that were broadcasted during their "downtime". {@link BroadcasterCache} is useful when
 * the long polling technique is used to prevent applications from loosing event between re-connection.
 * <p/>
 * A BroadcasterCache can be configured by invoking {@link org.atmosphere.cpr.BroadcasterConfig#setBroadcasterCache(BroadcasterCache)} by
 * defining it in your web/application.xml or by using the {@link org.atmosphere.config.service.BroadcasterCacheService}
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
     * Configure the cache.
     */
    void configure(AtmosphereConfig config);

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
     * @param interceptor
     * @return this
     */
    BroadcasterCache inspector(BroadcasterCacheInspector interceptor);

}
