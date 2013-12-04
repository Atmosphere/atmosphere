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
/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */

package org.atmosphere.cpr;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * An {@link AtmosphereResource} encapsulates the mechanism to {@link #suspend()}, {@link #resume()} and
 * broadcast ({@link #getBroadcaster()}) messages among suspended resources. {@link AtmosphereResource}s are passed to
 * an instance of {@link AtmosphereHandler} at runtime.
 *
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereResource {

    enum TRANSPORT {POLLING, LONG_POLLING, STREAMING, WEBSOCKET, JSONP, UNDEFINED, SSE, AJAX, HTMLFILE}

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
     * The Framework will output some HTML comments when suspending the response in order to make sure all browsers
     * work well with suspended responses. By default, the {@link AtmosphereResponse#getWriter} will be used. You can
     * change that behavior by setting a request attribute named org.atmosphere.useStream so the framework will
     * use {@link AtmosphereResponse#getOutputStream()}.
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
     * The Framework will output some HTML comments when suspending the response in order to make sure all browsers
     * work well with suspended responses. By default, the {@link AtmosphereResponse#getWriter} will be used. You can
     * change that behavior by setting a request attribute named org.atmosphere.useStream so the framework will
     * use {@link AtmosphereResponse#getOutputStream()}.
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
     * Return the current {@link Broadcaster}.
     *
     * @return the current {@link Broadcaster}
     */
    Broadcaster getBroadcaster();

    /**
     * Set the current {@link Broadcaster}. If null, a new Broadcaster will be created with {@link Broadcaster.SCOPE#REQUEST}
     * if that resource hasn't been suspended yet.
     *
     * @param broadcaster
     * @return this
     */
    AtmosphereResource setBroadcaster(Broadcaster broadcaster);

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
