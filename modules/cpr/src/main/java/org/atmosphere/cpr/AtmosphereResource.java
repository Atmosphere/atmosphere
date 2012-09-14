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
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;


/**
 * A {@link AtmosphereResource} encapsulates the mechanism to {@link #suspend()},
 * {@link #resume()} and broadcast ({@link #getBroadcaster()}) messages among
 * suspended response. {@link AtmosphereResource} are passed at runtime to an
 * instance of {@link AtmosphereHandler}.
 *
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereResource {

    enum TRANSPORT {POLLING, LONG_POLLING, STREAMING, WEBSOCKET, JSONP, UNDEFINED, SSE, AJAX}

    /**
     * Return the current {@link TRANSPORT}. The transport value is retrieved using the {@link HeaderConfig#X_ATMOSPHERE_TRANSPORT}
     * header value.
     */
    TRANSPORT transport();

    /**
     * Set to true to resume the response once after the first broadcast. False by default.
     *
     * @param resumeOnBroadcast
     */
    AtmosphereResource resumeOnBroadcast(boolean resumeOnBroadcast);

    /**
     * Return true is the {@link org.atmosphere.cpr.AtmosphereResource#suspend()} has been invoked.
     *
     * @return true is the {@link org.atmosphere.cpr.AtmosphereResource#suspend()} has been invoked.
     */
    boolean isSuspended();

    /**
     * Return the is the {@link org.atmosphere.cpr.AtmosphereResource#resumeOnBroadcast()}
     */
    boolean resumeOnBroadcast();

    /**
     * Return true if this object has been resumed.
     *
     * @return true if this object has been resumed.
     */
    boolean isResumed();

    /**
     * Return true if this object has been cancelled.
     *
     * @return true if this object has been cancelled.
     */
    boolean isCancelled();

    /**
     * Complete the {@link AtmosphereResponse} and finish/commit it. If the
     * {@link AtmosphereResponse} is in the process of being resumed, invoking
     * that method has no effect.
     */
    AtmosphereResource resume();

    /**
     * Suspend the {@link AtmosphereResponse} indefinitely.
     * Suspending a {@link AtmosphereResponse} will
     * tell the underlying container to avoid recycling objects associated with
     * the current instance, and also to avoid commiting response.
     * <p/>
     * The Framework will output some HTML comments when suspending the response
     * in order to make sure all Browser works well with suspended response.
     */
    AtmosphereResource suspend();

    /**
     * Suspend the {@link AtmosphereResponse}. Suspending a {@link AtmosphereResponse} will
     * tell the underlying container to avoid recycling objects associated with
     * the current instance, and also to avoid commiting response. Invoking
     * this method when a request is being timed out, e.g. {@link AtmosphereResourceEvent#isResumedOnTimeout} return true,
     * has no effect.
     * <p/>
     * The Framework will output some HTML comments when suspending the response
     * in order to make sure all Browser works well with suspended response. By default,
     * the {@link AtmosphereResponse#getWriter} will be used. You can change that
     * behavior by setting a request attribute named org.atmosphere.useStream to
     * so the framework will use {@link AtmosphereResponse#getOutputStream()}
     *
     * @param timeout The maximum amount of time, in milliseconds,
     *                a {@link AtmosphereResponse} can be suspended. When the timeout expires,
     *                the {@link AtmosphereResponse} will be automatically resumed and committed.
     *                Usage of any methods of a {@link AtmosphereResponse} that
     *                times out will throw an {@link IllegalStateException}.
     */
    AtmosphereResource suspend(long timeout);

    /**
     * Suspend the {@link AtmosphereResponse}. Suspending a {@link AtmosphereResponse} will
     * tell the underlying container to avoid recycling objects associated with
     * the current instance, and also to avoid commiting response. Invoking
     * this method when a request is being timed out, e.g. {@link AtmosphereResourceEvent#isResumedOnTimeout} return true,
     * has no effect.
     * <p/>
     * The Framework will output some HTML comments when suspending the response
     * in order to make sure all Browser works well with suspended response. By default,
     * the {@link AtmosphereResponse#getWriter} will be used. You can change that
     * behavior by setting a request attribute named org.atmosphere.useStream to
     * so the framework will use {@link AtmosphereResponse#getOutputStream()}
     *
     * @param timeout The maximum amount of time, in milliseconds,
     *                a {@link AtmosphereResponse} can be suspended. When the timeout expires,
     *                the {@link AtmosphereResponse} will be automatically resumed and committed.
     *                Usage of any methods of a {@link AtmosphereResponse} that
     *                times out will throw an {@link IllegalStateException}.
     * @param timeunit The time unit of the timeout value
     */

    AtmosphereResource suspend(long timeout, TimeUnit timeunit);

    /**
     * Suspend the {@link AtmosphereResponse}. Suspending a {@link AtmosphereResponse} will
     * tell the underlying container to avoid recycling objects associated with
     * the current instance, and also to avoid commiting response. Invoking
     * this method when a request is being timed out, e.g. {@link AtmosphereResourceEvent#isResumedOnTimeout} return true,
     * has no effect.
     * <p/>
     * The Framework will output some HTML comments when suspending the response
     * in order to make sure all Browser works well with suspended response. By default,
     * the {@link AtmosphereResponse#getWriter} will be used. You can change that
     * behavior by setting a request attribute named org.atmosphere.useStream to
     * so the framework will use {@link AtmosphereResponse#getOutputStream()}
     *
     * @param timeout The maximum amount of time, in milliseconds,
     *                a {@link AtmosphereResponse} can be suspended. When the timeout expires,
     *                the {@link AtmosphereResponse} will be automatically resumed and committed.
     *                Usage of any methods of a {@link AtmosphereResponse} that
     *                times out will throw an {@link IllegalStateException}.
     * @param flushComment By default, Atmosphere will output some comments to make WebKit based
     *                     browser working. Set it to false if you want to remove it.
     */
    AtmosphereResource suspend(long timeout, boolean flushComment);

    /**
     * Suspend the {@link AtmosphereResponse}. Suspending a {@link AtmosphereResponse} will
     * tell the underlying container to avoid recycling objects associated with
     * the current instance, and also to avoid commiting response. Invoking
     * this method when a request is being timed out, e.g. {@link AtmosphereResourceEvent#isResumedOnTimeout} return true,
     * has no effect.
     * <p/>
     * The Framework will output some HTML comments when suspending the response
     * in order to make sure all Browser works well with suspended response. By default,
     * the {@link AtmosphereResponse#getWriter} will be used. You can change that
     * behavior by setting a request attribute named org.atmosphere.useStream to
     * so the framework will use {@link AtmosphereResponse#getOutputStream()}
     *
     * @param timeout The maximum amount of time, in milliseconds,
     *                a {@link AtmosphereResponse} can be suspended. When the timeout expires,
     *                the {@link AtmosphereResponse} will be automatically resumed and committed.
     *                Usage of any methods of a {@link AtmosphereResponse} that
     *                times out will throw an {@link IllegalStateException}.
     * @param timeunit     The time unit of the timeout value
     * @param flushComment By default, Atmosphere will output some comments to make WebKit based
     *                     browser working. Set it to false if you want to remove it.
     */

    AtmosphereResource suspend(long timeout, TimeUnit timeunit, boolean flushComment);

    /**
     * Return the underlying {@link AtmosphereRequest} Request.
     *
     * @return {@link AtmosphereRequest} the underlying Request.
     */
    AtmosphereRequest getRequest();

    /**
     * Return the {@link AtmosphereResponse}
     *
     * @return {@link AtmosphereResponse} the underlying Response.
     */
    AtmosphereResponse getResponse();

    /**
     * Return the {@link AtmosphereConfig}
     *
     * @return the {@link AtmosphereConfig}
     */
    AtmosphereConfig getAtmosphereConfig();

    /**
     * Return the current {@link Broadcaster}
     *
     * @return the current {@link Broadcaster}
     */
    Broadcaster getBroadcaster();

    /**
     * Set the current {@link Broadcaster}. If null, a new Broadcaster will be created with {@link Broadcaster.SCOPE#REQUEST}
     * will be created if that resource hasn't been yet suspended.
     *
     * @param broadcaster
     */
    AtmosphereResource setBroadcaster(Broadcaster broadcaster);

    /**
     * Set the {@link Serializer} to use when {@link AtmosphereResource#write}
     * execute the operation. By default, the {@link Serializer} is null.
     *
     * @param s the {@link Serializer}
     */
    AtmosphereResource setSerializer(Serializer s);

    /**
     * Write the {@link Object} using the {@link OutputStream} by invoking
     * the current {@link Serializer}. If {@link Serializer} is null, the {@link Object}
     * will be directly written using the {
     *
     * @param os {@link OutputStream}
     * @param o  {@link Object}
     * @throws java.io.IOException
     */
    AtmosphereResource write(OutputStream os, Object o) throws IOException;

    /**
     * Get the {@link Serializer} or null if not defined.
     *
     * @return the {@link Serializer} or null if not defined.
     */
    Serializer getSerializer();

    /**
     * Return the current {@link AtmosphereResourceEvent}.
     */
    AtmosphereResourceEvent getAtmosphereResourceEvent();

    /**
     * Return the associated {@link AtmosphereHandler} associated with this resource.
     *
     * @return the associated {@link AtmosphereHandler} associated with this resource.
     */
    AtmosphereHandler getAtmosphereHandler();

    /**
     * Set a message that will be written when the resource times out. Cab be an {@link Object} or {@link java.util.concurrent.Callable}
     */
    AtmosphereResource writeOnTimeout(Object o);

    /**
     * Return the object that will be written when the resource times out;
     */
    Object writeOnTimeout();

    /**
     * Return the unique ID associated with this AtmosphereResource.
     *
     * @return the unique ID associated with this AtmosphereResource.
     */
    String uuid();

    /**
     * Add a {@link AtmosphereResourceEventListener}.
     *
     * @param e an instance of AtmosphereResourceEventListener
     */
    AtmosphereResource addEventListener(AtmosphereResourceEventListener e);

    /**
     * Remove a{@link AtmosphereResourceEventListener}.
     *
     * @param e
     */
    AtmosphereResource removeEventListener(AtmosphereResourceEventListener e);

    /**
     * Remove all {@link AtmosphereResourceEventListener}.
     */
    AtmosphereResource removeEventListeners();

    /**
     * Notify {@link AtmosphereResourceEventListener}.
     *
     * @param e an instance of AtmosphereResourceEvent
     */
    AtmosphereResource notifyListeners(AtmosphereResourceEvent e);

    /**
     * Notify All {@link AtmosphereResourceEventListener}.
     */
    AtmosphereResource notifyListeners();

    /**
     * Return the {@link HttpSession} is supported, null if not
     * @return the {@link HttpSession} is supported, null if not
     */
    HttpSession session();

     /**
     * Return the {@link HttpSession} is supported, and creates it if not already created.
     * @return the {@link HttpSession} is supported, and creates it if not already created
     */
    HttpSession session(boolean create);

    /**
     * Set the padding to use when flushing the response when transport equals 'streaming' See {@link org.atmosphere.cpr.ApplicationConfig#STREAMING_PADDING_MODE}
     * for more info.
     */
    public AtmosphereResource padding(String padding);
}
