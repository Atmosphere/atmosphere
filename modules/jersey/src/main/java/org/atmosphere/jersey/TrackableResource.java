/*
 * Copyright 2012 Jeanfrancois Arcand
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Associate any kind of resource to a @Suspend operation. When returning a {@link TrackableResource} from a method
 * annotated with the suspend annotation, the atmosphere framework will automatically add the X-Atmosphere-tracking-id
 * header to the response. The header can later be used for injecting {@link TrackableResource}. As simple as
 * <blockquote><pre>
 *
 * @param <T>
 * @GET
 * @Suspend public TrackableResource suspend(){
 * return new TrackableResource(AtmosphereResource.class, "abcdef", Response.OK());
 * }
 * @POST public String asyncBroadcast(@HeaderParam("X-Atmosphere-tracking-id") TrackableResource<AtmosphereResource> track) {
 * AtmosphereResource r = track.resource();
 * ...
 * }
 * </blockquote><pre>
 */
public class TrackableResource<T extends Trackable> {

    private static final Logger logger = LoggerFactory.getLogger(TrackableResource.class);

    private final Class<T> type;
    private T resource;
    private String trackingID = null;
    private final Object entity;
    private final CountDownLatch latch = new CountDownLatch(1);

    public TrackableResource(Class<T> type, Object entity) {
        this.type = type;
        this.entity = entity;
    }

    public TrackableResource(Class<T> type, String trackingID, Object entity) {
        this.type = type;
        this.trackingID = trackingID;
        this.entity = entity;
    }

    protected void setResource(Trackable resource) {
        if (!type.isAssignableFrom(resource.getClass())) {
            throw new IllegalStateException(String.format("Unassignable %s to %s", type.toString(), resource.getClass().toString()));
        }
        latch.countDown();
        this.resource = type.cast(resource);
    }

    protected void setTrackingID(String trackingID) {
        this.trackingID = trackingID;
    }

    /**
     * Return the associated resource of type T
     *
     * @return the associated resource of type T
     */
    public T resource() {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.trace("", e);
        }
        return resource;
    }

    /**
     * Retunr the class's type.
     *
     * @return
     */
    public Class<T> type() {
        return type;
    }

    /**
     * Return the trackingID token associated with the resource.
     *
     * @return the trackingID token associated with the resource.
     */
    public String trackingID() {
        return trackingID;
    }

    /**
     * Return the Entity associated with the resource.
     *
     * @return the Entity associated with the resource.
     */
    public Object entity() {
        return entity;
    }
}
