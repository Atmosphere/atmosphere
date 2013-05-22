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
package org.atmosphere.interceptor;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.BroadcasterListenerAdapter;
import org.atmosphere.util.ExecutorsFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.atmosphere.cpr.ApplicationConfig.STATE_RECOVERY_TIMEOUT;

/**
 * This interceptor associates a {@link AtmosphereResource} to all {@link Broadcaster} the resource was added before
 * the underlying connection got closed. This allow an application to restore the state of the client before the
 * disconnection occurred.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereResourceStateRecovery implements AtmosphereInterceptor {

    private final static Logger logger = LoggerFactory.getLogger(AtmosphereResourceStateRecovery.class);
    private final ConcurrentHashMap<String, BroadcasterTracker> states = new ConcurrentHashMap<String, BroadcasterTracker>();
    private BroadcasterFactory factory;
    private ScheduledExecutorService stateTracker;
    private long timeout = 5 * 1000 * 60;

    @Override
    public void configure(AtmosphereConfig config) {
        factory = config.getBroadcasterFactory();
        factory.addBroadcasterListener(new B());

        stateTracker = ExecutorsFactory.getScheduler(config);
        String s = config.getInitParameter(STATE_RECOVERY_TIMEOUT);
        if (s != null) {
            timeout = Long.parseLong(s);
        }

        clearStateTracker();
        logger.trace("{} started.", AtmosphereResourceStateRecovery.class.getName());
    }

    protected void clearStateTracker(){
        stateTracker.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (Map.Entry<String,BroadcasterTracker> t: states.entrySet()) {
                    if (now - t.getValue().lastTick() > timeout) {
                        logger.trace("AtmosphereResource {} state destroyed.", t.getKey());
                        states.remove(t.getKey());
                    }
                }
            }
        }, timeout, timeout, TimeUnit.NANOSECONDS);
    }

    @Override
    public Action inspect(final AtmosphereResource r) {

        if (!r.transport().equals(AtmosphereResource.TRANSPORT.POLLING)
                && !r.transport().equals(AtmosphereResource.TRANSPORT.AJAX)) {

            r.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onPreSuspend(AtmosphereResourceEvent e) {
                    // We have state
                    BroadcasterTracker tracker = track(r).tick();
                    for (String broadcasterID : tracker.ids()) {
                        Broadcaster b = factory.lookup(broadcasterID, false);
                        if (b != null && !b.getID().equalsIgnoreCase(r.getBroadcaster().getID())) {
                            logger.trace("Associate AtmosphereResource {} with Broadcaster {}", r.uuid(), broadcasterID);
                            b.addAtmosphereResource(r);
                        } else {
                            logger.trace("Broadcaster {} is no longer available", broadcasterID);
                        }
                    }
                    r.removeEventListener(this);
                }
            });
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

    public final class B extends BroadcasterListenerAdapter {
        @Override
        public void onAddAtmosphereResource(Broadcaster b, AtmosphereResource r) {

            BroadcasterTracker t = states.get(r.uuid());
            if (t == null) {
                t = track(r);
            }

            t.add(b);
        }

        @Override
        public void onRemoveAtmosphereResource(Broadcaster b, AtmosphereResource r) {
            // We only track cancelled connection
            BroadcasterTracker t = states.get(r.uuid());
            if (!r.isCancelled() && t != null) {
                t.remove(b);
            }
        }
    }

    public final static class BroadcasterTracker {

        private final List<String> broadcasterIds;
        private long tick;

        public BroadcasterTracker() {
            this.broadcasterIds = new LinkedList<String>();
            tick = System.currentTimeMillis();
        }

        public BroadcasterTracker add(Broadcaster b) {
            logger.trace("Adding {}", b.getID());
            broadcasterIds.add(b.getID());
            return this;
        }

        public BroadcasterTracker remove(Broadcaster b) {
            logger.trace("Removing {}", b.getID());
            broadcasterIds.remove(b.getID());
            return this;
        }

        public List<String> ids() {
            return broadcasterIds;
        }

        public BroadcasterTracker tick(){
            tick = System.currentTimeMillis();
            return this;
        }

        public long lastTick(){
            return tick;
        }
    }

    public ConcurrentHashMap<String, BroadcasterTracker> states(){
        return states;
    }

    @Override
    public String toString(){
        return "AtmosphereResource state recovery";
    }

}
