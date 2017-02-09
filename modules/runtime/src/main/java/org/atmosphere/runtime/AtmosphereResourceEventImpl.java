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

    @Override
    public boolean isResuming() {
        return resource == null ? true : resource.isResumed();
    }

    @Override
    public boolean isSuspended() {
        return resource == null ? false : resource.isSuspended();
    }

    @Override
    public boolean isClosedByClient() {
        return isClosedByClient.get();
    }

    @Override
    public boolean isClosedByApplication() {
        return isClosedByApplication.get();
    }

    public AtmosphereResourceEventImpl setCloseByApplication(boolean b) {
        isClosedByApplication.set(b);
        return this;
    }

    @Override
    public Object getMessage() {
        return message;
    }

    @Override
    public AtmosphereResourceEventImpl setMessage(Object message) {
        this.message = message;
        return this;
    }

    public AtmosphereResourceEventImpl isClosedByClient(boolean isClosedByClient) {
        this.isClosedByClient.set(isClosedByClient);
        return this;
    }

    @Override
    public boolean isResumedOnTimeout() {
        return isResumedOnTimeout.get();
    }

    @Override
    public boolean isCancelled() {
        return isCancelled.get();
    }

    public AtmosphereResourceEventImpl setCancelled(boolean isCancelled) {
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

    @Override
    public int hashCode() {
        int result = isCancelled != null ? isCancelled.hashCode() : 0;
        result = 31 * result + (isResumedOnTimeout != null ? isResumedOnTimeout.hashCode() : 0);
        result = 31 * result + (throwable != null ? throwable.hashCode() : 0);
        result = 31 * result + (message != null ? message.hashCode() : 0);
        result = 31 * result + (resource != null ? resource.hashCode() : 0);
        return result;
    }

    @Override
    public Throwable throwable() {
        return throwable;
    }

    @Override
    public Broadcaster broadcaster() {
        return resource.getBroadcaster();
    }

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

    @Override
    public String toString() {
        return "AtmosphereResourceEventImpl{" +
                " isCancelled=" + isCancelled +
                " isClosedByClient=" + isClosedByClient +
                " isClosedByApplication=" + isClosedByApplication +
                " isResumedOnTimeout=" + isResumedOnTimeout +
                " throwable=" + throwable +
                " resource=" + uuid +
                '}';
    }
}
