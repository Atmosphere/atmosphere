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
package org.atmosphere.util;

import org.atmosphere.runtime.ApplicationConfig;
import org.atmosphere.runtime.AtmosphereConfig;
import org.atmosphere.runtime.AtmosphereResource;
import org.atmosphere.runtime.AtmosphereResourceEvent;
import org.atmosphere.runtime.Broadcaster;
import org.atmosphere.runtime.BroadcasterConfig;
import org.atmosphere.runtime.BroadcasterFuture;
import org.atmosphere.runtime.DefaultBroadcaster;
import org.atmosphere.runtime.Deliver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple {@link org.atmosphere.runtime.Broadcaster} implementation that use the calling thread when broadcasting events.
 *
 * @author Jeanfrancois Arcand
 */
public class SimpleBroadcaster extends DefaultBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(SimpleBroadcaster.class);

    public SimpleBroadcaster(){};

    public Broadcaster initialize(String id, AtmosphereConfig config) {
        return super.initialize(id, config);
    }

    @Override
    protected BroadcasterConfig createBroadcasterConfig(AtmosphereConfig config) {
        BroadcasterConfig bc = (BroadcasterConfig) config.properties().get(BroadcasterConfig.class.getName());
        if (bc == null) {
            bc = new BroadcasterConfig(config.framework().broadcasterFilters(), config, false, getID())
                    .init()
                    .setScheduledExecutorService(ExecutorsFactory.getScheduler(config));
        }
        return bc;
    }

    @Override
    protected void start() {
        if (!started.getAndSet(true)) {
            bc.getBroadcasterCache().start();
        }
    }

    @Override
    public void setBroadcasterConfig(BroadcasterConfig bc) {
        this.bc = bc;
        bc.setExecutorService(null, false).setAsyncWriteService(null, false)
                .setScheduledExecutorService(ExecutorsFactory.getScheduler(config));
    }

    @Override
    public Future<Object> broadcast(Object msg) {

        if (destroyed.get()) {
            logger.warn("This Broadcaster has been destroyed and cannot be used");
            return futureDone(msg);
        }

        start();

        Object newMsg = filter(msg);
        if (newMsg == null) return null;
        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        push(new Deliver(newMsg, f, msg));
        return f;
    }

    @Override
    public Future<Object> broadcast(Object msg, AtmosphereResource r) {

        if (destroyed.get()) {
            logger.warn("This Broadcaster has been destroyed and cannot be used");
            return futureDone(msg);
        }

        start();

        Object newMsg = filter(msg);
        if (newMsg == null) return null;
        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        push(new Deliver(newMsg, r, f, msg));
        return f;
    }

    @Override
    public Future<Object> broadcast(Object msg, Set<AtmosphereResource> subset) {

        if (destroyed.get()) {
            logger.warn("This Broadcaster has been destroyed and cannot be used");
            return futureDone(msg);
        }

        start();

        Object newMsg = filter(msg);
        if (newMsg == null) return null;

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        push(new Deliver(newMsg, subset, f, msg));
        return f;
    }

    @Override
    protected void prepareInvokeOnStateChange(final AtmosphereResource r, final AtmosphereResourceEvent e) {
        if (writeTimeoutInSecond != -1) {
            logger.warn("{} not supported with this broadcaster.", ApplicationConfig.WRITE_TIMEOUT);
        }
        invokeOnStateChange(r, e);
    }

    @Override
    protected void queueWriteIO(AtmosphereResource r, Deliver deliver, AtomicInteger count) throws InterruptedException {
        executeBlockingWrite(r, deliver, count);
    }
}