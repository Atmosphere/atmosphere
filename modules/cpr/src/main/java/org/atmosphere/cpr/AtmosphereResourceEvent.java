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

import java.io.IOException;
import java.io.OutputStream;

/**
 * An AtmosphereResourceEvent is created every time an event occurs like when a
 * {@link Broadcaster#broadcast(java.lang.Object)} is executed, when a Browser close
 * remotly close the connection or when a suspended times out or gets resumed. When
 * such events occcurs, an instance of that class will be created and its associated
 * {@link AtmosphereHandler#onStateChange(org.atmosphere.cpr.AtmosphereResourceEvent)}
 * will be invoked.
 *
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereResourceEvent<E, F> {

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
    public void setMessage(Object o);

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
    public AtmosphereResource<E, F> getResource();

    /**
     * Write the {@link Object} using the {@link OutputStream} by invoking
     * the current {@link Serializer}. If {@link Serializer} is null, the {@link Object}
     * will be directly written using the {
     *
     * @param os {@link OutputStream}
     * @param o  {@link Object}
     * @throws java.io.IOException
     */
    public void write(OutputStream os, Object o) throws IOException;


}
