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
package org.atmosphere.jersey;

import org.atmosphere.cpr.Trackable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A {@link TrackableSession} stores {@link TrackableResource} that can be retrieved when the http header called
 * {@link org.atmosphere.cpr.HeaderConfig#X_ATMOSPHERE_TRACKING_ID} is added to a request.
 *
 * @author Jeanfrancois Arcand
 */
public class TrackableSession {

    private static final Logger logger = LoggerFactory.getLogger(TrackableSession.class);
    private final static TrackableSession factory = new TrackableSession();
    private final ConcurrentHashMap<String, TrackableResource> factoryCache = new ConcurrentHashMap<String, TrackableResource>();
    private final ConcurrentHashMap<String, CountDownLatch> pendingLock = new ConcurrentHashMap<String, CountDownLatch>();

    private TrackableSession() {
    }

    /**
     * Return the default implementation of {@link TrackableSession}
     *
     * @return the default implementation of {@link TrackableSession}
     */
    public static TrackableSession getDefault() {
        return factory;
    }

    /**
     * Start tracking an {@link TrackableResource}
     *
     * @param trackableResource a {@link TrackableResource}
     */
    public void track(TrackableResource<? extends Trackable> trackableResource) {
        logger.debug("Tracking {}", trackableResource.trackingID());
        factoryCache.put(trackableResource.trackingID(), trackableResource);
        CountDownLatch latch = pendingLock.remove(trackableResource.trackingID());
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * Return the {@link TrackableResource} associated with the trackingID
     *
     * @param trackingID a unique token.
     * @return the {@link TrackableResource} associated with the trackingID
     */
    public TrackableResource<? extends Trackable> lookup(String trackingID) {
        return factoryCache.get(trackingID);
    }

    /**
     * Return the {@link TrackableResource} associated with the trackingID
     *
     * @param trackingID a unique token.
     * @return the {@link TrackableResource} associated with the trackingID
     */
    public TrackableResource<? extends Trackable> lookupAndWait(String trackingID) {
        logger.debug("Lookup trackinID {}", trackingID);

        TrackableResource<? extends Trackable> r = factoryCache.get(trackingID);
        if (r == null){
            CountDownLatch latch = new CountDownLatch(1);
            pendingLock.put(trackingID,latch);
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                }
                pendingLock.remove(trackingID);
            } catch (InterruptedException e) {
                logger.trace("",e);
            }
        }
        return  factoryCache.get(trackingID);
    }
}
