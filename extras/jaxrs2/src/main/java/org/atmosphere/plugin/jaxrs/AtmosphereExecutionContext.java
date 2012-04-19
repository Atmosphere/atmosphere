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
package org.atmosphere.plugin.jaxrs;

import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.jersey.util.JerseyBroadcasterUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.ExecutionContext;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AtmosphereExecutionContext implements ExecutionContext {

    private final Logger logger = LoggerFactory.getLogger(AtmosphereExecutionContext.class);
    private final AtmosphereResourceImpl resource;
    private Object response;
    private TimeUnit unit;
    private long time = -1;
    private AtomicBoolean resumed = new AtomicBoolean(false);

    public AtmosphereExecutionContext(AtmosphereResourceImpl r) {
        this.resource = r;
        resource.addEventListener(new AtmosphereResourceEventListenerAdapter() {
            @Override
            public void onResume(AtmosphereResourceEvent event) {
                if (!resumed.getAndSet(true)) {
                    try {
                        JerseyBroadcasterUtil.broadcast(resource,
                                new AtmosphereResourceEventImpl(resource, false, false, null).setMessage(response),
                                resource.getBroadcaster());
                    } catch (Throwable t) {
                        logger.trace("", t);
                        try {
                            resource.cancel();
                        } catch (IOException e) {
                            logger.trace("", t);
                        }
                    }
                }
            }
        });
    }

    @Override
    public void resume(Object response) throws IllegalStateException {
        JerseyBroadcasterUtil.broadcast(resource,
                new AtmosphereResourceEventImpl(resource, false, false, null).setMessage(response),
                resource.getBroadcaster());
        resource.resume();
    }

    @Override
    public void resume(Exception response) throws IllegalStateException {
        throw new IllegalStateException("Not supported");
    }

    @Override
    public void suspend() throws IllegalStateException {
        if (this.unit == null) {
            resource.suspend(-1, false);
        } else {
            resource.suspend(time, unit, false);
        }
    }

    @Override
    public void suspend(long millis) throws IllegalStateException {
        if (this.unit == null) {
            resource.suspend(millis);
        } else {
            resource.suspend(time, unit, false);
        }
    }

    @Override
    public void suspend(long time, TimeUnit unit) throws IllegalStateException {
        if (this.unit == null) {
            resource.suspend(time, unit);
        } else {
            resource.suspend(time, unit, false);
        }
    }

    @Override
    public void setSuspendTimeout(long time, TimeUnit unit) throws IllegalStateException {
        this.time = time;
        this.unit = unit;
    }

    @Override
    public void cancel() {
        try {
            resource.cancel();
        } catch (IOException e) {
            logger.debug("", e);
        }
    }

    @Override
    public boolean isSuspended() {
        return resource.isSuspended();
    }

    @Override
    public boolean isCancelled() {
        return resource.isCancelled();
    }

    @Override
    public boolean isDone() {
        return !isSuspended();
    }

    @Override
    public void setResponse(Object response) {
        this.response = response;
    }

    @Override
    public Response getResponse() {
        return Response.ok(response).build();
    }
}
