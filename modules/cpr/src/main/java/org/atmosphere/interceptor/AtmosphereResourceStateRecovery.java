/*
 * Copyright 2015 Async-IO.org
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
package org.atmosphere.interceptor;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.BroadcasterListenerAdapter;
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.ApplicationConfig.STATE_RECOVERY_TIMEOUT;
import static org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter.OnSuspend;

/**
 * This interceptor associates a {@link AtmosphereResource} to all {@link Broadcaster} the resource was added before
 * the underlying connection got closed and resume. This allow an application to restore the state of the client before the
 * disconnection occurred, and for the long-polling transport to return to it's previous state.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereResourceStateRecovery implements AtmosphereInterceptor {

    private final static Logger logger = LoggerFactory.getLogger(AtmosphereResourceStateRecovery.class);
    private final ConcurrentHashMap<String, BroadcasterTracker> states = new ConcurrentHashMap<String, BroadcasterTracker>();
    private BroadcasterFactory factory;
    private ScheduledExecutorService stateTracker;
    private long timeout = 5 * 1000 * 60;
    private Future<?> trackerFuture;

    @Override
    public void configure(AtmosphereConfig config) {
        factory = config.getBroadcasterFactory();
        factory.addBroadcasterListener(new B());

        stateTracker = ExecutorsFactory.getScheduler(config);
        String s = config.getInitParameter(STATE_RECOVERY_TIMEOUT);
        if (s != null) {
            timeout = Long.parseLong(s);
        }
        startStateTracker();
        logger.trace("{} started.", AtmosphereResourceStateRecovery.class.getName());
    }

    public AtmosphereResourceStateRecovery timeout(long timeout){
        this.timeout = timeout;
        trackerFuture.cancel(false);
        startStateTracker();
        return this;
    }

    public long timeout(){
        return timeout;
    }

    protected void startStateTracker() {
        trackerFuture = stateTracker.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (Map.Entry<String, BroadcasterTracker> t : states.entrySet()) {
                    // The resource may still be suspended but we don't want to keep a reference to it, so we swap
                    // the state and will recover.
                    if (now - t.getValue().lastTick() > timeout) {
                        logger.trace("AtmosphereResource {} state destroyed.", t.getKey());
                        states.remove(t.getKey());
                    }
                }
            }
        }, timeout, timeout, TimeUnit.MILLISECONDS);
    }

    @Override
    public Action inspect(final AtmosphereResource r) {

        if (!Utils.pollableTransport(r.transport()) && !Utils.webSocketMessage(r)){

            final BroadcasterTracker tracker = track(r).tick();

            List<Object> cachedMessages = retrieveCache(r, tracker, false);
            if (cachedMessages.size() > 0) {
                logger.trace("cached messages");
                writeCache(r, cachedMessages);
                return Action.CANCELLED;
            } else {
                r.addEventListener(new OnAlwaysSuspend() {
                    public void onSuspend(AtmosphereResourceEvent event) {
                        r.removeEventListener(this);

                        logger.trace("onSuspend first");
                        final AtomicBoolean doNotSuspend = new AtomicBoolean(false);
                        /**
                         * If a message gets broadcasted during the execution of the code below, we don't need to
                         * suspend the connection. This code is needed to prevent the connection being suspended
                         * with messages already written.
                         */
                        r.addEventListener(new OnBroadcast() {
                            @Override
                            public void onBroadcast(AtmosphereResourceEvent event) {
                                r.removeEventListener(this);
                                doNotSuspend.set(true);
                                logger.trace("onBroadcast");
                            }
                        });

                        for (String broadcasterID : tracker.ids()) {
                            Broadcaster b = factory.lookup(broadcasterID, false);
                            logger.trace("About to associate resource {} with Broadcaster {}", r.uuid(), broadcasterID);
                            if (b != null && !b.getID().equalsIgnoreCase(r.getBroadcaster().getID())) {
                                logger.trace("Associate AtmosphereResource {} with Broadcaster {}", r.uuid(), broadcasterID);
                                b.addAtmosphereResource(r);
                            } else if (b == null) {
                                logger.trace("Broadcaster {} is no longer available for {}", broadcasterID, r);
                            } else {
                                logger.trace("AtmosphereResource {} already associated with {}", r.uuid(), broadcasterID);
                            }
                        }

                        /**
                         * Check the cache to see if messages has been added directly by using
                         * {@link BroadcasterCache#addToCache(String, org.atmosphere.cpr.AtmosphereResource, org.atmosphere.cache.BroadcastMessage)}
                         * after {@link Broadcaster#addAtmosphereResource(org.atmosphere.cpr.AtmosphereResource)} has been
                         * invoked.
                         */
                        final List<Object> cachedMessages = retrieveCache(r, tracker, true);
                        if (logger.isTraceEnabled()) {
                            logger.trace("message size {}", cachedMessages.size());
                        }
                        if (cachedMessages.size() > 0) {
                            logger.trace("About to write to the cache {}", r.uuid());
                            writeCache(r, cachedMessages);
                            doNotSuspend.set(true);
                        }

                        // Force doNotSuspend.
                        if (doNotSuspend.get()) {
                            AtmosphereResourceImpl.class.cast(r).action().type(Action.TYPE.CONTINUE);
                        }
                        if (logger.isTraceEnabled()) {
                            logger.trace("doNotSuspend {}", doNotSuspend.get());
                        }
                    }
                });
            }
        }
        return Action.CONTINUE;
    }

    private BroadcasterTracker track(AtmosphereResource r) {
        BroadcasterTracker tracker = states.get(r.uuid());
        if (tracker == null) {
            tracker = new BroadcasterTracker();
            states.put(r.uuid(), tracker);
            logger.trace("AtmosphereResource {} state now tracked", r.uuid());
        }
        return tracker;
    }

    @Override
    public void postInspect(AtmosphereResource r) {
    }

    @Override
    public void destroy() {
        trackerFuture.cancel(true);
    }

    public final class B extends BroadcasterListenerAdapter {
        @Override
        public void onAddAtmosphereResource(Broadcaster b, AtmosphereResource r) {

            BroadcasterTracker t = states.get(r.uuid());
            if (t == null) {
                t = track(r);
            }
            logger.trace("Starting tracking the state of {} with broadcaster {}", r.uuid(), b.getID());
            t.add(b);
        }

        @Override
        public void onRemoveAtmosphereResource(Broadcaster b, AtmosphereResource r) {
            // We track cancelled and resumed connection only.
            BroadcasterTracker t = states.get(r.uuid());
            AtmosphereResourceEvent e = r.getAtmosphereResourceEvent();
            if (e.isClosedByClient() || !r.isResumed() && !e.isResumedOnTimeout()) {
                logger.trace("Deleting the state of {} with broadcaster {}", r.uuid(), b.getID());
                if (t != null) {
                    t.remove(b);
                }
            } else {
                // The BroadcasterTracker was swapped
                onAddAtmosphereResource(b, r);
                logger.trace("Keeping the state of {} with broadcaster {}", r.uuid(), b.getID());
                logger.trace("State for {} with broadcaster {}", r.uuid(), t != null ? t.ids() : "null");
            }
        }
    }

    public final static class BroadcasterTracker {

        private final ConcurrentLinkedQueue<String> broadcasterIds;
        private long tick;

        public BroadcasterTracker() {
            this.broadcasterIds = new ConcurrentLinkedQueue<String>();
            tick = System.currentTimeMillis();
        }

        public BroadcasterTracker add(Broadcaster b) {
            logger.trace("Adding {}", b.getID());
            if (!broadcasterIds.contains(b.getID())) {
                broadcasterIds.add(b.getID());
            }
            return this;
        }

        public BroadcasterTracker remove(Broadcaster b) {
            logger.trace("Removing {}", b.getID());
            broadcasterIds.remove(b.getID());
            return this;
        }

        public ConcurrentLinkedQueue<String> ids() {
            return broadcasterIds;
        }

        public BroadcasterTracker tick() {
            tick = System.currentTimeMillis();
            return this;
        }

        public long lastTick() {
            return tick;
        }
    }

    public ConcurrentHashMap<String, BroadcasterTracker> states() {
        return states;
    }

    @Override
    public String toString() {
        return "AtmosphereResource state recovery";
    }

    public List<Object> retrieveCache(AtmosphereResource r, BroadcasterTracker tracker, boolean force) {
        List<Object> cachedMessages = new LinkedList<Object>();
        for (String broadcasterID : tracker.ids()) {
            Broadcaster b = factory.lookup(broadcasterID, false);
            BroadcasterCache cache;
            logger.trace("About to retrieve cached messages for resource {} with Broadcaster {}, tracked by " + b, r.uuid(), r.getBroadcaster());
            if (b != null && (force || !b.getID().equalsIgnoreCase(r.getBroadcaster().getID()))) {
                // We cannot add the resource now. we need to first make sure there is no cached message.
                cache = b.getBroadcasterConfig().getBroadcasterCache();
                List<Object> t = cache.retrieveFromCache(b.getID(), r.uuid());

                t = b.getBroadcasterConfig().applyFilters(r, t);
                if (t.size() > 0) {
                    logger.trace("Found Cached Messages For AtmosphereResource {} with Broadcaster {}", r.uuid(), broadcasterID);
                    cachedMessages.addAll(t);
                }
            } else {
                logger.trace("Broadcaster {} is no longer available for {}", broadcasterID, r);
            }
        }
        return cachedMessages;
    }

    private void writeCache(AtmosphereResource r, List<Object> cachedMessages) {
        try {
            logger.trace("Writing cached messages {} for {}", cachedMessages, r.uuid());
            r.getAtmosphereHandler().onStateChange(
                    new AtmosphereResourceEventImpl(AtmosphereResourceImpl.class.cast(r), false, false, null)
                            .setMessage(cachedMessages));
        } catch (IOException e) {
            logger.warn("Unable to recover from state recovery {}", r.uuid(), e);
        }
    }

    abstract static public class OnAlwaysSuspend extends OnSuspend implements AllowInterceptor {
        @Override
        abstract public void onSuspend(AtmosphereResourceEvent event);
    }
}
