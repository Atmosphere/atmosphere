/*
 * Copyright 2008-2020 Async-IO.org
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

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * An {@link AtmosphereResource} encapsulates the mechanism to {@link #suspend()}, {@link #resume()} and
 * broadcast ({@link #getBroadcaster()}) messages among suspended resources. {@link AtmosphereResource}s are passed to
 * an instance of {@link AtmosphereHandler} at runtime.
 *
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereResource {

    enum TRANSPORT {POLLING, LONG_POLLING, STREAMING, WEBSOCKET, JSONP, UNDEFINED, SSE, AJAX, HTMLFILE, CLOSE}

    /**
     * Return the current {@link TRANSPORT}. The transport value is retrieved using the {@link HeaderConfig#X_ATMOSPHERE_TRANSPORT}
     * header value.
     */
    TRANSPORT transport();

    /**
     * Set to true to resume the response after the first broadcast. False by default.
     *
     * @param resumeOnBroadcast
     */
    AtmosphereResource resumeOnBroadcast(boolean resumeOnBroadcast);

    /**
     * Return true if the {@link org.atmosphere.cpr.AtmosphereResource#suspend()} has been invoked.
     *
     * @return true if the {@link org.atmosphere.cpr.AtmosphereResource#suspend()} has been invoked
     */
    boolean isSuspended();

    /**
     * Return true if this AtmosphereResource is resumed after the first broadcast.
     *
     * @see org.atmosphere.cpr.AtmosphereResource#resumeOnBroadcast()
     */
    boolean resumeOnBroadcast();

    /**
     * Return true if this object has been resumed.
     *
     * @return true if this object has been resumed
     */
    boolean isResumed();

    /**
     * Return true if this object has been cancelled.
     *
     * @return true if this object has been cancelled
     */
    boolean isCancelled();

    /**
     * Complete the {@link AtmosphereResponse} and finish/commit it. If the {@link AtmosphereResponse} is in the
     * process of being resumed, invoking this method has no effect.
     */
    AtmosphereResource resume();

    /**
     * Suspend the {@link AtmosphereResponse} indefinitely.
     * Suspending a {@link AtmosphereResponse} will tell the underlying container to avoid recycling objects associated
     * with the current instance, and also to avoid committing a response.
     * <p/>
     * The Framework will output some HTML comments when suspending the response in order to make sure all browsers
     * work well with suspended responses.
     */
    AtmosphereResource suspend();

    /**
     * Suspend the {@link AtmosphereResponse}. Suspending a {@link AtmosphereResponse} will tell the underlying
     * container to avoid recycling objects associated with the current instance, and also to avoid committing response.
     * Invoking this method when a request is being timed out (e.g. {@link AtmosphereResourceEvent#isResumedOnTimeout}
     * returns true) has no effect.
     * <p/>
     *
     * @param timeout The maximum amount of time, in milliseconds, a {@link AtmosphereResponse} can be suspended. When
     *                the timeout expires, the {@link AtmosphereResponse} will be automatically resumed and committed.
     *                Usage of any methods of a {@link AtmosphereResponse} that times out will throw an {@link IllegalStateException}.
     */
    AtmosphereResource suspend(long timeout);

    /**
     * Suspend the {@link AtmosphereResponse}. Suspending a {@link AtmosphereResponse} will tell the underlying
     * container to avoid recycling objects associated with the current instance, and also to avoid committing response.
     * Invoking this method when a request is being timed out (e.g. {@link AtmosphereResourceEvent#isResumedOnTimeout}
     * returns true) has no effect.
     * <p/>
     *
     * @param timeout  The maximum amount of time a {@link AtmosphereResponse} can be suspended. When the timeout
     *                 expires, the {@link AtmosphereResponse} will be automatically resumed and committed. Usage of any
     *                 methods of a {@link AtmosphereResponse} that times out will throw an {@link IllegalStateException}.
     * @param timeunit The time unit of the timeout value
     */

    AtmosphereResource suspend(long timeout, TimeUnit timeunit);

    /**
     * Return the underlying {@link AtmosphereRequest} request.
     *
     * @return {@link AtmosphereRequest} the underlying request.
     */
    AtmosphereRequest getRequest();

    /**
     * Return the {@link AtmosphereResponse}.
     *
     * @return {@link AtmosphereResponse} the underlying response.
     */
    AtmosphereResponse getResponse();

    /**
     * Return the {@link AtmosphereConfig}.
     *
     * @return the {@link AtmosphereConfig}
     */
    AtmosphereConfig getAtmosphereConfig();

    /**
     * Return the first added {@link Broadcaster}.
     *
     * @return the current {@link Broadcaster}
     */
    Broadcaster getBroadcaster();

    /**
     * Return an unmodifiable list of {@link Broadcaster}s associated with this resource
     *
     * @return an unmodifiable list of {@link Broadcaster}
     */
    List<Broadcaster> broadcasters();

    /**
     * Remove this {@link org.atmosphere.cpr.AtmosphereResource} from all {@link org.atmosphere.cpr.Broadcaster}
     *
     * @return this
     */
    public AtmosphereResource removeFromAllBroadcasters();

    /**
     * Set the first {@link Broadcaster} associated with this resource. This {@link org.atmosphere.cpr.Broadcaster}
     * will be returned when {@link #getBroadcaster()} is invoked.
     *
     * @param broadcaster
     * @return this
     */
    AtmosphereResource setBroadcaster(Broadcaster broadcaster);

    /**
     * Add/Associate a {@link org.atmosphere.cpr.Broadcaster} with this resource.
     *
     * @param broadcaster
     * @return this
     */
    AtmosphereResource addBroadcaster(Broadcaster broadcaster);

    /**
     * Remove a {@link org.atmosphere.cpr.Broadcaster} with this resource.
     *
     * @param broadcaster
     * @return this
     */
    AtmosphereResource removeBroadcaster(Broadcaster broadcaster);

    /**
     * Set the {@link Serializer} to use when {@link AtmosphereResource#write} execute the operation.
     * By default, the {@link Serializer} is null.
     *
     * @param s the {@link Serializer}
     * @return this
     */
    AtmosphereResource setSerializer(Serializer s);

    /**
     * Write the String. If {@link #resumeOnBroadcast()} is true, the underlying connection will be resumed (@link #resume());
     *
     * @param s
     * @return this
     */
    AtmosphereResource write(String s);

    /**
     * Write the bytes If {@link #resumeOnBroadcast()} is true, the underlying connection will be resumed (@link #resume());
     *
     * @param s
     * @return this
     */
    AtmosphereResource write(byte[] s);

    /**
     * Get the {@link Serializer} or null if not defined.
     *
     * @return the {@link Serializer} or null if not defined
     */
    Serializer getSerializer();

    /**
     * Return the current {@link AtmosphereResourceEvent}.
     */
    AtmosphereResourceEvent getAtmosphereResourceEvent();

    /**
     * Return the associated {@link AtmosphereHandler} associated with this resource.
     *
     * @return the associated {@link AtmosphereHandler} associated with this resource
     */
    AtmosphereHandler getAtmosphereHandler();

    /**
     * Set a message that will be written when the resource times out. Can be an {@link Object} or {@link java.util.concurrent.Callable}.
     *
     * @return this
     */
    AtmosphereResource writeOnTimeout(Object o);

    /**
     * Return the object that will be written when the resource times out.
     */
    Object writeOnTimeout();

    /**
     * Return the unique ID associated with this AtmosphereResource.
     *
     * @return the unique ID associated with this AtmosphereResource
     */
    String uuid();

    /**
     * Add a {@link AtmosphereResourceEventListener}.
     *
     * @param e an instance of AtmosphereResourceEventListener
     * @return this
     */
    AtmosphereResource addEventListener(AtmosphereResourceEventListener e);

    /**
     * Remove a {@link AtmosphereResourceEventListener}.
     *
     * @param e
     * @return this
     */
    AtmosphereResource removeEventListener(AtmosphereResourceEventListener e);

    /**
     * Remove all {@link AtmosphereResourceEventListener}s.
     *
     * @return this
     */
    AtmosphereResource removeEventListeners();

    /**
     * Notify all {@link AtmosphereResourceEventListener}.
     *
     * @param e an instance of AtmosphereResourceEvent
     * @return this
     */
    AtmosphereResource notifyListeners(AtmosphereResourceEvent e);

    /**
     * Notify all {@link AtmosphereResourceEventListener}s.
     *
     * @return this
     */
    AtmosphereResource notifyListeners();

    /**
     * Return the {@link HttpSession} if supported, null if not
     *
     * @return the {@link HttpSession} if supported, null if not
     */
    HttpSession session();

    /**
     * Return the {@link HttpSession} if supported, and creates it if not already created.
     *
     * @return the {@link HttpSession} if supported, and creates it if not already created
     */
    HttpSession session(boolean create);

    /**
     * Close the underlying connection. Invoking this method will close the underlying connection and resume the
     * {@link AtmosphereResource}}.
     */
    void close() throws IOException;

    /**
     * Force binary write and never write String value.
     * return this
     */
    AtmosphereResource forceBinaryWrite(boolean force);

    /**
     * Return true when binary write is forced.
     *
     * @return true when binary write is forced.
     */
    boolean forceBinaryWrite();

    /**
     * Initialize an {@link AtmosphereResource}.
     *
     * @param config            The {@link org.atmosphere.cpr.AtmosphereConfig}
     * @param broadcaster       The {@link org.atmosphere.cpr.Broadcaster}.
     * @param req               The {@link AtmosphereRequest}
     * @param response          The {@link AtmosphereResource}
     * @param asyncSupport      The {@link AsyncSupport}
     * @param atmosphereHandler The {@link AtmosphereHandler}
     * @return this
     */
    public AtmosphereResource initialize(AtmosphereConfig config, Broadcaster broadcaster,
                                         AtmosphereRequest req, AtmosphereResponse response,
                                         AsyncSupport asyncSupport, AtmosphereHandler atmosphereHandler);
}
