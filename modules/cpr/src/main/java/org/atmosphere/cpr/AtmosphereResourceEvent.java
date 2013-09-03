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
 * General Public License Version 2 only ("GPL") or the Common Development
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

/**
 * An AtmosphereResourceEvent is created every time an event occurs like when a
 * {@link Broadcaster#broadcast(java.lang.Object)} is executed, when a Browser close
 * remotely close the connection or when a suspended times out or gets resumed. When
 * such events occurs, an instance of that class will be created and its associated
 * {@link AtmosphereHandler#onStateChange(org.atmosphere.cpr.AtmosphereResourceEvent)}
 * will be invoked.
 *
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereResourceEvent {

    /**
     * Return the object that were pass to {@link Broadcaster#broadcast(java.lang.Object)}
     *
     * @return the object that were pass to {@link Broadcaster#broadcast(java.lang.Object)}
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
     * Return <tt>true<//tt> if that {@link AtmosphereResource#suspend()} has been
     * invoked and set to <tt>true</tt>
     *
     * @return <tt>true<//tt> if that {@link AtmosphereResource#suspend()} has been
     *         invoked and set to <tt>true</tt>
     */
    public boolean isSuspended();

    /**
     * Return <tt>true<//tt> if that {@link AtmosphereResource#resume()} has been
     * invoked
     *
     * @return <tt>true<//tt> if that {@link AtmosphereResource#resume()} has been
     *         invoked and set to <tt>true</tt>
     */
    public boolean isResuming();

    /**
     * Return the {@link AtmosphereResource} associated with this event
     *
     * @return {@link AtmosphereResource}
     */
    public AtmosphereResource getResource();

    /**
     * Return true if the client closed the connection and send the Atmosphere close message. You must
     * use the {@link org.atmosphere.interceptor.OnDisconnectInterceptor} in order to receive the proper value,
     * and enableProtocol set to true on the client side (enabledProtocol is true by default).
     * @return
     */
    public boolean isClosedByClient();

    /**
     * Return true if the application closed the connection using {@link org.atmosphere.cpr.AtmosphereResource#close()}.
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
