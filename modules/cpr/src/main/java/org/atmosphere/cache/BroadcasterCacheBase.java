/*
 * Copyright 2011 Jeanfrancois Arcand
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
package org.atmosphere.cache;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.BroadcasterCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Abstract {@link org.atmosphere.cpr.BroadcasterCache} which is used to implement headers or query parameters or
 * session based caching.
 *
 * @author Jeanfrancois Arcand
 */
public abstract class BroadcasterCacheBase implements BroadcasterCache<HttpServletRequest, HttpServletResponse> {

    private static final Logger logger = LoggerFactory.getLogger(BroadcasterCacheBase.class);

    protected final List<CachedMessage> queue = new CopyOnWriteArrayList<CachedMessage>();

    protected ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor();

    protected int maxCachedinMs = 1000 * 5 * 60;

    public BroadcasterCacheBase() {
    }

    /**
     * {@inheritDoc}
     */
    public final void start() {
        reaper.scheduleAtFixedRate(new Runnable() {

            public void run() {
                Iterator<CachedMessage> i = queue.iterator();
                CachedMessage message;
                while (i.hasNext()) {
                    message = i.next();
                    logger.trace("Message: {}", message.message());

                    if (System.currentTimeMillis() - message.currentTime() > maxCachedinMs) {
                        logger.trace("Pruning: {}", message.message());
                        queue.remove(message);
                    } else {
                        break;
                    }
                }
            }
        }, 0, 60, TimeUnit.SECONDS);
    }

    public void setExecutorService(ScheduledExecutorService reaper){
        if (reaper != null) {
            stop();
        }
        this.reaper = reaper;
    }

    /**
     * {@inheritDoc}
     */
    public final void stop() {
        reaper.shutdown();
    }

    /**
     * {@inheritDoc}
     */
    public final synchronized void addToCache(
            final AtmosphereResource<HttpServletRequest, HttpServletResponse> resource, final Object object) {
        logger.trace("Adding message for resource: {}, object: {}", resource, object);

        CachedMessage cm = new CachedMessage(object, System.currentTimeMillis(), null);
        CachedMessage prev = null;
        if (!queue.isEmpty()) {
            prev = queue.get(queue.size() - 1);
        }

        if (prev != null) {
            prev.next(cm);
        }

        // Some indexing issue if this return true
        if (!queue.contains(cm)) {
            queue.add(cm);
        }

        if (prev == null) {
            cm = new CachedMessage(true);
        }

        if (resource != null) {
            cache(resource, cm);
        }
    }

    /**
     * Cache the last message broadcasted.
     *
     * @param r  {@link org.atmosphere.cpr.AtmosphereResource}.
     * @param cm {@link org.atmosphere.cache.BroadcasterCacheBase.CachedMessage}
     */
    public abstract void cache(final AtmosphereResource<HttpServletRequest, HttpServletResponse> r, CachedMessage cm);

    /**
     * Return the last message broadcasted to the {@link org.atmosphere.cpr.AtmosphereResource}.
     *
     * @param r {@link org.atmosphere.cpr.AtmosphereResource}.
     * @return a {@link org.atmosphere.cache.BroadcasterCacheBase.CachedMessage}, or null if not matched.
     */
    public abstract CachedMessage retrieveLastMessage(final AtmosphereResource<HttpServletRequest, HttpServletResponse> r);

    /**
     * {@inheritDoc}
     */
    public final synchronized List<Object> retrieveFromCache(final AtmosphereResource<HttpServletRequest, HttpServletResponse> r) {

        CachedMessage cm = retrieveLastMessage(r);
        boolean isNew = false;
        if (cm == null && r.getRequest().getAttribute(AtmosphereResourceImpl.PRE_SUSPEND) != null) {
            isNew = true;
        }

        boolean isHead = false;

        if (cm != null && cm.isTail) cm = null;

        if (cm != null) {
            if (!queue.contains(cm) && !queue.isEmpty()) {
                cm = queue.get(0);
                isHead = true;
            }
        } else if (isNew && !queue.isEmpty()) {
            cm = queue.get(0);
            isHead = true;
        }

        final ArrayList<Object> l = new ArrayList<Object>();
        if (cm == null) {
            // Try to locate the
            return l;
        }

        if (!isHead)
            cm = cm.next();

        CachedMessage prev = cm;
        while (cm != null) {
            l.add(cm.message());
            prev = cm;
            cm = cm.next();
        }

        if (prev != null)
            cache(r, prev);

        return l;
    }

    /**
     * Get the maximum time a broadcasted message can stay cached.
     *
     * @return Get the maximum time a broadcasted message can stay cached.
     */
    public int getMaxCachedinMs() {
        return maxCachedinMs;
    }

    /**
     * Set the maximum time a broadcasted message can stay cached.
     *
     * @param maxCachedinMs time in milliseconds
     */
    public void setMaxCachedinMs(final int maxCachedinMs) {
        this.maxCachedinMs = maxCachedinMs;
    }

    protected final static class CachedMessage implements Serializable {

        public final Object message;
        public final long currentTime;
        public CachedMessage next;
        public final boolean isTail;

        public CachedMessage(boolean isTail) {
            this.currentTime = 0L;
            this.message = null;
            this.next = null;
            this.isTail = isTail;
        }

        public CachedMessage(Object message, long currentTime, CachedMessage next) {
            this.currentTime = currentTime;
            this.message = message;
            this.next = next;
            this.isTail = false;
        }

        public Object message() {
            return message;
        }

        public long currentTime() {
            return currentTime;
        }

        public CachedMessage next() {
            return next;
        }

        public CachedMessage next(CachedMessage next) {
            this.next = next;
            return next;
        }

        public String toString() {
            if (message != null) {
                return message.toString();
            } else {
                return "";
            }
        }

        public boolean isTail() {
            return isTail;
        }

    }
}