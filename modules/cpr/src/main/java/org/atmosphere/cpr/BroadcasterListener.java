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
package org.atmosphere.cpr;

/**
 * A listener for a {@link Broadcaster}'s event lifecycle.
 *
 * @author Jeanfrancois Arcand
 */
public interface BroadcasterListener {

    /**
     * Invoked just after the {@link Broadcaster} has been created.
     *
     * @param b a Broadcaster
     */
    void onPostCreate(Broadcaster b);

    /**
     * Invoked when the Broadcast operation completes.
     *
     * @param b a Broadcaster
     */
    void onComplete(Broadcaster b);

    /**
     * Invoked before a Broadcaster is about to be deleted.
     *
     * @param b a Broadcaster
     */
    void onPreDestroy(Broadcaster b);

    /**
     * Invoked when an {@link AtmosphereResource} is getting associated to a {@link Broadcaster}.
     *
     * @param b a Broadcaster
     * @param r an AtmosphereResource
     */
    void onAddAtmosphereResource(Broadcaster b, AtmosphereResource r);

    /**
     * Invoked when an {@link AtmosphereResource} is getting removed to a {@link Broadcaster}.
     *
     * @param b a Broadcaster
     * @param r an AtmosphereResource
     */
    void onRemoveAtmosphereResource(Broadcaster b, AtmosphereResource r);

    /**
     * Throw this exception to interrupt the {@link org.atmosphere.cpr.Broadcaster#destroy()} operation.
     */
    public final static class BroadcastListenerException extends RuntimeException {

        public BroadcastListenerException() {
        }
    }
}
