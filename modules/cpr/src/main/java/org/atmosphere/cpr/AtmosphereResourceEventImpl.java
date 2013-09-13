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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link AtmosphereResourceEvent} implementation.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereResourceEventImpl implements AtmosphereResourceEvent {

    // Was the remote connection closed.
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    // Is Resumed on Timeout?
    private final AtomicBoolean isResumedOnTimeout = new AtomicBoolean(false);
    private Throwable throwable;
    // The current message
    protected Object message;
    protected AtmosphereResourceImpl resource;
    private final AtomicBoolean isClosedByClient = new AtomicBoolean(false);
    private final String uuid;
    private final AtomicBoolean isClosedByApplication = new AtomicBoolean(false);

    public AtmosphereResourceEventImpl(AtmosphereResourceImpl resource) {
        this.resource = resource;
        this.throwable = null;
        uuid = resource.uuid();
    }

    public AtmosphereResourceEventImpl(AtmosphereResourceImpl resource, boolean isCancelled,
                                       boolean isResumedOnTimeout) {
        this.isCancelled.set(isCancelled);
        this.isResumedOnTimeout.set(isResumedOnTimeout);
        this.resource = resource;
        this.throwable = null;
        uuid = resource.uuid();
    }

    public AtmosphereResourceEventImpl(AtmosphereResourceImpl resource, boolean isCancelled,
                                       boolean isResumedOnTimeout,
                                       Throwable throwable) {
        this.isCancelled.set(isCancelled);
        this.isResumedOnTimeout.set(isResumedOnTimeout);
        this.resource = resource;
        this.throwable = throwable;
        uuid = resource.uuid();
    }

    public AtmosphereResourceEventImpl(AtmosphereResourceImpl resource,
                                       boolean isCancelled,
                                       boolean isResumedOnTimeout,
                                       boolean isClosedByClient,
                                       Throwable throwable) {
        this.isCancelled.set(isCancelled);
        this.isResumedOnTimeout.set(isResumedOnTimeout);
        this.resource = resource;
        this.throwable = throwable;
        this.isClosedByClient.set(isClosedByClient);
        uuid = resource.uuid();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isResuming() {
        return resource == null ? false : resource.action().type() == Action.TYPE.RESUME;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSuspended() {
        return resource == null ? false : resource.action().type() == Action.TYPE.SUSPEND;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isClosedByClient() {
        return isClosedByClient.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isClosedByApplication() {
        return isClosedByApplication.get();
    }

    public AtmosphereResourceEventImpl setCloseByApplication(boolean b) {
        isClosedByApplication.set(b);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object getMessage() {
        return message;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResourceEventImpl setMessage(Object message) {
        this.message = message;
        return this;
    }

    public AtmosphereResourceEventImpl isClosedByClient(boolean isClosedByClient) {
        this.isClosedByClient.set(isClosedByClient);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isResumedOnTimeout() {
        return isResumedOnTimeout.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCancelled() {
        return isCancelled.get();
    }

    protected AtmosphereResourceEventImpl setCancelled(boolean isCancelled) {
        if (check()) {
            resource.action().type(Action.TYPE.CANCELLED);
            this.isCancelled.set(isCancelled);
        }
        return this;
    }

    protected AtmosphereResourceEventImpl setIsResumedOnTimeout(boolean isResumedOnTimeout) {
        if (check()) {
            resource.action().type(Action.TYPE.TIMEOUT);
            this.isResumedOnTimeout.set(isResumedOnTimeout);
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AtmosphereResourceEventImpl that = (AtmosphereResourceEventImpl) o;

        if (isCancelled != null ? !isCancelled.equals(that.isCancelled) : that.isCancelled != null) return false;
        if (isResumedOnTimeout != null ? !isResumedOnTimeout.equals(that.isResumedOnTimeout) : that.isResumedOnTimeout != null)
            return false;
        if (message != null ? !message.equals(that.message) : that.message != null) return false;
        if (resource != null ? !resource.equals(that.resource) : that.resource != null) return false;
        if (throwable != null ? !throwable.equals(that.throwable) : that.throwable != null) return false;

        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        int result = isCancelled != null ? isCancelled.hashCode() : 0;
        result = 31 * result + (isResumedOnTimeout != null ? isResumedOnTimeout.hashCode() : 0);
        result = 31 * result + (throwable != null ? throwable.hashCode() : 0);
        result = 31 * result + (message != null ? message.hashCode() : 0);
        result = 31 * result + (resource != null ? resource.hashCode() : 0);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Throwable throwable() {
        return throwable;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Broadcaster broadcaster() {
        return resource.getBroadcaster();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResource getResource() {
        return resource;
    }

    private boolean check() {
        return resource == null ? false : true;
    }

    public AtmosphereResourceEvent setThrowable(Throwable t) {
        this.throwable = t;
        return this;
    }

    public AtmosphereResourceEvent destroy() {
        isCancelled.set(true);
        resource = null;
        message = null;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "AtmosphereResourceEventImpl{" +
                "\n\t message=" + message +
                "\n\t isCancelled=" + isCancelled +
                "\n\t isClosedByClient=" + isClosedByClient +
                "\n\t isClosedByApplication=" + isClosedByApplication +
                "\n\t isResumedOnTimeout=" + isResumedOnTimeout +
                "\n\t throwable=" + throwable +
                "\n\t resource=" + uuid +
                '}';
    }
}
