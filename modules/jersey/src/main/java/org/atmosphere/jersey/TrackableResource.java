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

/**
 * Associate any kind of resource to a @Suspend operation. When returning a {@link TrackableResource} from a method
 * annotated with the suspend annotation, the atmosphere framework will automatically add the X-Atmosphere-tracking-id
 * header to the response. The header can later be used for injecting {@link TrackableResource}. As simple as
 * <blockquote><pre>
    @GET
    @Suspend
    public TrackableResource suspend(){
        return new TrackableResource(AtmosphereResource.class, "abcdef", Response.OK());
    }

    @POST
    public String asyncBroadcast(@HeaderParam("X-Atmosphere-tracking-id") TrackableResource<AtmosphereResource> track) {
        AtmosphereResource<?,?> r = track.resource();
        ...
    }
 * </blockquote><pre>
 * @param <T>
 */
public class TrackableResource<T extends Trackable> {

    public static final String TRACKING_HEADER = "X-Atmosphere-tracking-id";

    private final Class<T> type;
    private T resource;
    private final String trackingID;
    private final Object entity;

    public TrackableResource(Class<T> type, String trackingID, Object entity) {
        this.type = type;
        this.trackingID = trackingID;
        this.entity = entity;
    }

    protected void setResource(Trackable resource) {
        if (!type.isAssignableFrom(resource.getClass())) {
            throw new IllegalStateException(String.format("Unassignable %s to %s", type.toString(), resource.getClass().toString()));
        }
        this.resource = type.cast(resource);
    }

    /**
     * Return the associated resource of type T
     * @return the associated resource of type T
     */
    public T resource() {
        return resource;
    }

    /**
     * Retunr the class's type.
     * @return
     */
    public Class<T> type() {
        return type;
    }

    /**
     * Return the trackingID token associated with the resource.
     * @return the trackingID token associated with the resource.
     */
    public String trackingID() {
        return trackingID;
    }

    /**
     * Return the Entity associated with the resource.
     * @return the Entity associated with the resource.
     */
    public Object entity() {
        return entity;
    }
}
