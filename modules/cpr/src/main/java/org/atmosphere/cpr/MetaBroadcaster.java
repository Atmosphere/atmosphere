/*
 * Copyright 2012 Jean-Francois Arcand
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

import org.atmosphere.util.uri.UriTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Broadcast events to all or a subset of available {@link Broadcaster} based on their{@link org.atmosphere.cpr.Broadcaster#getID()} value.
 * This class allow broadcasting events to a set of broadcaster that maps some String like:
 * <blockquote><pre>
 *        // Broadcast the event to all Broadcaster ID starting with /hello
 *        broadcast("/hello", event)
 *        // Broadcast the event to all Broadcaster ID
 *        broaccast("/*", event);
 * </pre></blockquote>
 * The rule used is similar to path/uri mapping used by technology like Servlet, Jersey, etc.
 * <p/>
 * NOTE: Broadcaster's name must start with / in order to get retrieved by this class.
 *
 * @author Jeanfrancois Arcand
 */
public class MetaBroadcaster {
    public static final String MAPPING_REGEX = "[/a-zA-Z0-9-&.*=;\\?]+";

    private final static Logger logger = LoggerFactory.getLogger(MetaBroadcaster.class);
    private final static MetaBroadcaster metaBroadcaster = new MetaBroadcaster();
    private static final CopyOnWriteArrayList<BroadcasterListener> broadcasterListeners = new CopyOnWriteArrayList<BroadcasterListener>();

    protected MetaBroadcasterFuture broadcast(String path, Object message, int time, TimeUnit unit) {
        if (BroadcasterFactory.getDefault() != null) {
            Collection<Broadcaster> c = BroadcasterFactory.getDefault().lookupAll();

            final Map<String, String> m = new HashMap<String, String>();
            List<Broadcaster> l = new ArrayList<Broadcaster>();
            logger.debug("Map {}", path);
            UriTemplate t = new UriTemplate(path);
            for (Broadcaster b : c) {
                logger.debug("Trying to map {} to {}", t, b.getID());
                if (t.match(b.getID(), m)) {
                    l.add(b);
                }
                m.clear();
            }

            MetaBroadcasterFuture f = new MetaBroadcasterFuture(l);
            CompleteListener cl = new CompleteListener(f);
            for (Broadcaster b : l) {
                if (time <= 0) {
                    b.addBroadcasterListener(cl).broadcast(message);
                } else {
                    b.scheduleFixedBroadcast(message, time, unit);
                }
            }

            return f;
        } else {
            return new MetaBroadcasterFuture(Collections.<Broadcaster>emptyList());
        }
    }

    protected MetaBroadcasterFuture map(String path, Object message, int time, TimeUnit unit) {

        if (path == null || path.isEmpty()) {
            throw new NullPointerException();
        }

        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        if (path.contains("*")) {
            path = path.replace("*", MAPPING_REGEX);
        }

        if (path.equals("/")) {
            path += MAPPING_REGEX;
        }

        return broadcast(path, message, time, unit);
    }

    /**
     * Broadcast the message to all Broadcaster whose {@link org.atmosphere.cpr.Broadcaster#getID()} maps the broadcasterID value.
     *
     * @param broadcasterID a String (or path) that can potentially match a {@link org.atmosphere.cpr.Broadcaster#getID()}
     * @param message       a message to be broadcasted
     * @return a Future.
     */
    public Future<List<Broadcaster>> broadcastTo(String broadcasterID, Object message) {
        return map(broadcasterID, message, -1, null);
    }

    /**
     * Broadcast the message at a fixed rate to all Broadcaster whose {@link org.atmosphere.cpr.Broadcaster#getID()}
     * maps the broadcasterID value. This operation will invoke {@link Broadcaster#scheduleFixedBroadcast(Object, long, java.util.concurrent.TimeUnit)}}
     *
     * @param broadcasterID a String (or path) that can potentially match a {@link org.atmosphere.cpr.Broadcaster#getID()}
     * @param message       a message to be broadcasted
     * @param time          a time value
     * @param unit          a {@link TimeUnit}
     * @return a Future.
     */
    public Future<List<Broadcaster>> scheduleTo(String broadcasterID, Object message, int time, TimeUnit unit) {
        return map(broadcasterID, message, time, unit);
    }

    public final static MetaBroadcaster getDefault() {
        return metaBroadcaster;
    }

    private final static class CompleteListener implements BroadcasterListener {

        private final MetaBroadcasterFuture f;

        private CompleteListener(MetaBroadcasterFuture f) {
            this.f = f;
        }

        @Override
        public void onComplete(Broadcaster b) {
            f.countDown();
            for (BroadcasterListener l : broadcasterListeners) {
                l.onComplete(b);
            }
            b.removeBroadcasterListener(this);
        }
    }

    private final static class MetaBroadcasterFuture implements Future<List<Broadcaster>> {

        private final CountDownLatch latch;
        private final List<Broadcaster> l;
        private boolean isCancelled = false;

        private MetaBroadcasterFuture(List<Broadcaster> l) {
            this.latch = new CountDownLatch(l.size());
            this.l = l;
        }

        @Override
        public boolean cancel(boolean b) {
            while (latch.getCount() > 0) {
                latch.countDown();
            }
            isCancelled = true;
            return isCancelled;
        }

        @Override
        public boolean isCancelled() {
            return isCancelled;
        }

        @Override
        public boolean isDone() {
            return latch.getCount() == 0;
        }

        @Override
        public List<Broadcaster> get() throws InterruptedException, ExecutionException {
            latch.await();
            return l;
        }

        @Override
        public List<Broadcaster> get(long t, TimeUnit timeUnit) throws InterruptedException, ExecutionException, TimeoutException {
            latch.await(t, timeUnit);
            return l;
        }

        public void countDown() {
            latch.countDown();
        }
    }

    /**
     * Add a {@link BroadcasterListener} to all mapped {@link Broadcaster}. T
     * @param b {@link BroadcasterListener}
     * @return this
     */
    public MetaBroadcaster addBroadcasterListener(BroadcasterListener b) {
        broadcasterListeners.add(b);
        return this;
    }

    /**
     * Remove the {@link BroadcasterListener}.
     * @param b {@link BroadcasterListener}
     * @return this
     */
    public MetaBroadcaster removeBroadcasterListener(BroadcasterListener b) {
        broadcasterListeners.remove(b);
        return this;
    }
}
