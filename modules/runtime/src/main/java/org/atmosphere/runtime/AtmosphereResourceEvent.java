/*
 * Copyright 2017 Async-IO.org
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

package org.atmosphere.runtime;

/**
 * An AtmosphereResourceEvent is created every time an event occurs, like when a
 * {@link Broadcaster#broadcast(java.lang.Object)} is executed, when a browser remotely closes the connection or
 * when a suspended resource times out or gets resumed. When such events occur, an instance of that class will be
 * created and its associated {@link AtmosphereHandler#onStateChange(org.atmosphere.runtime.AtmosphereResourceEvent)} will be invoked.
 *
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereResourceEvent {

    /**
     * Return the object that was passed to {@link Broadcaster#broadcast(java.lang.Object)}.
     *
     * @return the object that was passed to {@link Broadcaster#broadcast(java.lang.Object)}
     */
    public Object getMessage();

    /**
     * Set an Object that can be retrieved with {@link #getMessage()}. Note that the value may be overridden when
     * {@link Broadcaster#broadcast(java.lang.Object)} gets invoked.
     *
     * @param o an Object that can be retrieved with {@link #getMessage()}.
     */
    public AtmosphereResourceEvent setMessage(Object o);

    /**
     * Return true is the response gets resumed after a timeout.
     *
     * @return true is the response gets resumed after a timeout.
     */
    public boolean isResumedOnTimeout();

    /**
     * Return true when the remote client close the connection.
     *
     * @return true when the remote client close the connection.
     */
    public boolean isCancelled();

    /**
     * Return <tt>true</tt> if {@link AtmosphereResource#suspend()} has been invoked and set to <tt>true</tt>.
     *
     * @return <tt>true</tt> if {@link AtmosphereResource#suspend()} has been invoked and set to <tt>true</tt>
     */
    public boolean isSuspended();

    /**
     * Return <tt>true</tt> if {@link AtmosphereResource#resume()} has been invoked.
     *
     * @return <tt>true</tt> if {@link AtmosphereResource#resume()} has been invoked and set to <tt>true</tt>
     */
    public boolean isResuming();

    /**
     * Return the {@link AtmosphereResource} associated with this event.
     *
     * @return {@link AtmosphereResource}
     */
    public AtmosphereResource getResource();

    /**
     * Return true if the client closed the connection and send the Atmosphere close message. You must
     * use the {@link org.atmosphere.interceptor.OnDisconnectInterceptor} in order to receive the proper value,
     * and atmosphereProtocol must be set to true on the client side (enabledProtocol is true by default).
     *
     * @return
     */
    public boolean isClosedByClient();

    /**
     * Return true if the application closed the connection using {@link org.atmosphere.runtime.AtmosphereResource#close()}.
     *
     * @return if the application.
     */
    public boolean isClosedByApplication();

    /**
     * Return a {@link Throwable} if an unexpected exception occured.
     *
     * @return {@link Throwable} if an unexpected exception occured.
     */
    public Throwable throwable();

    /**
     * Return the broadcaster associated with the {@link AtmosphereResource} this object contains.
     */
    public Broadcaster broadcaster();
}
