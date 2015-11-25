/*
 * Copyright 2015 Jean-Francois Arcand
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
public class DefaultMetaBroadcaster implements MetaBroadcaster {

    public static final String MAPPING_REGEX = "[/a-zA-Z0-9-&.*=@_;\\?]+";

    private final static Logger logger = LoggerFactory.getLogger(DefaultMetaBroadcaster.class);
    private final static ConcurrentLinkedQueue<BroadcasterListener> broadcasterListeners = new ConcurrentLinkedQueue<BroadcasterListener>();
    private final static MetaBroadcasterFuture E = new MetaBroadcasterFuture(Collections.<Broadcaster>emptyList());
    private MetaBroadcasterCache cache = new NoCache();
    private AtmosphereConfig config;

    public DefaultMetaBroadcaster() {
    }

    @Override
    public void configure(AtmosphereConfig config) {
        this.config = config;
    }

    protected MetaBroadcasterFuture broadcast(final String path, Object message, int time, TimeUnit unit, boolean delay, boolean cacheMessage) {
        if (config != null) {
            Collection<Broadcaster> c = config.getBroadcasterFactory().lookupAll();

            final Map<String, String> m = new HashMap<String, String>();
            List<Broadcaster> l = new ArrayList<Broadcaster>();
            logger.trace("Map {}", path);
            UriTemplate t = null;
            try {
                t = new UriTemplate(path);
                for (Broadcaster b : c) {
                    logger.trace("Trying to map {} to {}", t, b.getID());
                    if (t.match(b.getID(), m)) {
                        l.add(b);
                    }
                    m.clear();
                }
            } finally {
                if (t != null) t.destroy();
            }

            if (l.isEmpty() && cacheMessage) {
                if (NoCache.class.isAssignableFrom(cache.getClass())) {
                    logger.warn("No Broadcaster matches {}. Message {} WILL BE LOST. " +
                            "Make sure you cache it or make sure the Broadcaster exists before.", path, message);
                } else {
                    cache.cache(path, message);
                }
                return E;
            }

            MetaBroadcasterFuture f = new MetaBroadcasterFuture(l);
            CompleteListener cl = new CompleteListener(f);

            for (Broadcaster b : l) {
                if (time <= 0) {
                    f.outerFuture(b.addBroadcasterListener(cl).broadcast(message));
                } else if (!delay) {
                    f.outerFuture(b.scheduleFixedBroadcast(message, time, unit));
                } else {
                    f.outerFuture(b.delayBroadcast(message, time, unit));
                }
            }

            return f;
        } else {
            return E;
        }
    }

    protected MetaBroadcasterFuture map(String path, Object message, int time, TimeUnit unit, boolean delay, boolean cacheMessage) {

        if (path == null || path.isEmpty()) {
            throw new NullPointerException();
        }

        if (path.contains("*")) {
            path = path.replace("*", MAPPING_REGEX);
        }

        if (path.equals("/")) {
            path += MAPPING_REGEX;
        }

        return broadcast(path, message, time, unit, delay, cacheMessage);
    }

    @Override
    public Future<List<Broadcaster>> broadcastTo(String broadcasterID, Object message) {
        return map(broadcasterID, message, -1, null, false, true);
    }

    @Override
    public Future<List<Broadcaster>> broadcastTo(String broadcasterID, Object message, boolean cacheMessage) {
        return map(broadcasterID, message, -1, null, false, cacheMessage);
    }

    /**
     * Flush the cached messages.
     * @return this
     */
    protected MetaBroadcaster flushCache() {
        if (cache != null) cache.flushCache();
        return this;
    }

    @Override
    public Future<List<Broadcaster>> scheduleTo(String broadcasterID, Object message, int time, TimeUnit unit) {
        return map(broadcasterID, message, time, unit, false, true);
    }

    @Override
    public Future<List<Broadcaster>> delayTo(String broadcasterID, Object message, int time, TimeUnit unit) {
        return map(broadcasterID, message, time, unit, true, true);
    }

    private final static class CompleteListener extends BroadcasterListenerAdapter {

        private final MetaBroadcasterFuture f;

        private CompleteListener(MetaBroadcasterFuture f) {
            this.f = f;
        }

        @Override
        public void onPostCreate(Broadcaster b) {
        }

        @Override
        public void onComplete(Broadcaster b) {
            b.removeBroadcasterListener(this);
            f.countDown();
            if (f.isDone()) {
                for (BroadcasterListener l : broadcasterListeners) {
                    try {
                        l.onComplete(b);
                    } catch (Exception ex) {
                        logger.warn("", ex);
                    }
                }
            }
        }

        @Override
        public void onPreDestroy(Broadcaster b) {
        }
    }

    private final static class MetaBroadcasterFuture implements Future<List<Broadcaster>> {

        private final CountDownLatch latch;
        private final List<Broadcaster> l;
        private boolean isCancelled;
        private final List<Future<?>> outerFuture = new ArrayList<Future<?>>();

        private MetaBroadcasterFuture(List<Broadcaster> l) {
            this.latch = new CountDownLatch(l.size());
            this.l = l;
        }

        MetaBroadcasterFuture outerFuture(Future<?> f) {
            outerFuture.add(f);
            return this;
        }

        @Override
        public boolean cancel(boolean b) {
            for (Future<?> f : outerFuture) {
                f.cancel(b);
            }

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

    @Override
    public MetaBroadcaster addBroadcasterListener(BroadcasterListener b) {
        broadcasterListeners.add(b);
        return this;
    }

    @Override
    public MetaBroadcaster removeBroadcasterListener(BroadcasterListener b) {
        broadcasterListeners.remove(b);
        return this;
    }

    @Override
    public MetaBroadcaster cache(MetaBroadcasterCache cache) {
        this.cache = cache;
        return this;
    }

    public void destroy(){
        broadcasterListeners.clear();
        flushCache();
    }

}
