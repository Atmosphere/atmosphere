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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link AtmosphereResourceEvent} implementation for Servlet Container.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereResourceEventImpl implements AtmosphereResourceEvent<HttpServletRequest,
        HttpServletResponse> {

    // Was the remote connection closed.
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);

    // Is Resumed on Timeout?
    private final AtomicBoolean isResumedOnTimeout = new AtomicBoolean(false);

    private final Throwable throwable;

    // The current message
    protected Object message;

    protected final AtmosphereResourceImpl resource;

    public AtmosphereResourceEventImpl(AtmosphereResourceImpl resource) {
        this.resource = resource;
        this.throwable = null;
    }

    public AtmosphereResourceEventImpl(AtmosphereResourceImpl resource, boolean isCancelled,
                                       boolean isResumedOnTimeout) {
        this.isCancelled.set(isCancelled);
        this.isResumedOnTimeout.set(isResumedOnTimeout);
        this.resource = resource;
        this.throwable = null;
    }

    public AtmosphereResourceEventImpl(AtmosphereResourceImpl resource, boolean isCancelled,
                                       boolean isResumedOnTimeout,
                                       Throwable throwable) {
        this.isCancelled.set(isCancelled);
        this.isResumedOnTimeout.set(isResumedOnTimeout);
        this.resource = resource;
        this.throwable = throwable;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isResuming() {
        return resource.action().type == AtmosphereServlet.Action.TYPE.RESUME;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isSuspended() {
        return resource.action().type == AtmosphereServlet.Action.TYPE.SUSPEND;
    }

    /**
     * Return the object that were pass to {@link Broadcaster#broadcast(java.lang.Object)}
     *
     * @return the object that were pass to {@link Broadcaster#broadcast(java.lang.Object)}
     */
    public Object getMessage() {
        return message;
    }

    /**
     * Set the message broadcasted using {@link Broadcaster#broadcast(java.lang.Object)}
     *
     * @param message The message broadcasted using {@link Broadcaster#broadcast(java.lang.Object)}
     */
    public void setMessage(Object message) {
        this.message = message;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isResumedOnTimeout() {
        return isResumedOnTimeout.get();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isCancelled() {
        return isCancelled.get();
    }

    protected void setCancelled(boolean isCancelled) {
        this.isCancelled.set(isCancelled);
    }

    protected void setIsResumedOnTimeout(boolean isResumedOnTimeout) {
        this.isResumedOnTimeout.set(isResumedOnTimeout);
    }

    /**
     * {@inheritDoc}
     */
    public Throwable throwable() {
        return throwable;
    }

    /**
     * {@inheritDoc}
     */
    public AtmosphereResource<HttpServletRequest, HttpServletResponse> getResource() {
        return resource;
    }

    /**
     * {@inheritDoc}
     */
    public void write(OutputStream os, Object o) throws IOException {
        resource.write(os, o);
    }

    @Override
    public String toString() {
        return "AtmosphereResourceEventImpl{" + "isResuming=" + isResuming() + " isCancelled=" + isCancelled + ", isResumedOnTimeout=" +
                isResumedOnTimeout + ", message=" + message + ", resource=" + resource + ", throwable=" + throwable +
                '}';
    }
}
