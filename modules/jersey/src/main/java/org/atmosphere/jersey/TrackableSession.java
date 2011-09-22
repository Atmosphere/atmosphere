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

import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link TrackableSession} stores {@link TrackableResource} that can be retrieved when the http header called
 * X-Atmosphere-tracking-id is added to a request.
 *
 * @author Jeanfrancois Arcand
 */
public class TrackableSession {

    private final static TrackableSession factory = new TrackableSession();
    private final ConcurrentHashMap<String, TrackableResource> factoryCache = new ConcurrentHashMap<String, TrackableResource>();

    private TrackableSession() {
    }

    /**
     * Return the default implementation of {@link TrackableSession}
     * @return the default implementation of {@link TrackableSession}
     */
    public static TrackableSession getDefault() {
        return factory;
    }

    /**
     * Start tracking an {@link TrackableResource}
     * @param trackableResource a {@link TrackableResource}
     */
    public void track(TrackableResource<? extends Trackable> trackableResource) {
        factoryCache.put(trackableResource.trackingID(), trackableResource);
    }

    /**
     * Return the {@link TrackableResource} associated with the trackingID
     * @param trackingID a unique token.
     * @return  the {@link TrackableResource} associated with the trackingID
     */
    public TrackableResource<? extends Trackable> lookup(String trackingID) {
        return factoryCache.get(trackingID);
    }


}
