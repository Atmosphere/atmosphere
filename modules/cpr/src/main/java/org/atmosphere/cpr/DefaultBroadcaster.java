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

import org.atmosphere.cache.BroadcastMessage;
import org.atmosphere.cache.CacheMessage;
import org.atmosphere.cpr.BroadcastFilter.BroadcastAction;
import org.atmosphere.di.InjectorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_WAIT_TIME;
import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;
import static org.atmosphere.cpr.ApplicationConfig.OUT_OF_ORDER_BROADCAST;
import static org.atmosphere.cpr.ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.EMPTY;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.EMPTY_DESTROY;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE_DESTROY;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE_RESUME;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.NEVER;

/**
 * {@link Broadcaster} implementation.
 * <p/>
 * Broadcast messages to suspended response using the caller's Thread.
 * This basic {@link Broadcaster} use an {@link java.util.concurrent.ExecutorService}
 * to broadcast message, hence the broadcast operation is asynchronous. Make sure
 * you block on {@link #broadcast(Object)}.get()} if you need synchronous operations.
 *
 * @author Jeanfrancois Arcand
 */
public class DefaultBroadcaster implements Broadcaster {

    public static final String CACHED = DefaultBroadcaster.class.getName() + ".messagesCached";
    public static final String ASYNC_TOKEN = DefaultBroadcaster.class.getName() + ".token";

    private static final Logger logger = LoggerFactory.getLogger(DefaultBroadcaster.class);
    private static final String DESTROYED = "This Broadcaster has been destroyed and cannot be used {} by invoking {}";

    protected final ConcurrentLinkedQueue<AtmosphereResource> resources =
            new ConcurrentLinkedQueue<AtmosphereResource>();
    protected BroadcasterConfig bc;
    protected final BlockingQueue<Entry> messages = new LinkedBlockingQueue<Entry>();
    protected final ConcurrentLinkedQueue<BroadcasterListener> broadcasterListeners = new ConcurrentLinkedQueue<BroadcasterListener>();

    protected final AtomicBoolean started = new AtomicBoolean(false);
    protected final AtomicBoolean destroyed = new AtomicBoolean(false);

    protected SCOPE scope = SCOPE.APPLICATION;
    protected String name = DefaultBroadcaster.class.getSimpleName();
    protected final ConcurrentLinkedQueue<Entry> delayedBroadcast = new ConcurrentLinkedQueue<Entry>();
    protected final ConcurrentLinkedQueue<Entry> broadcastOnResume = new ConcurrentLinkedQueue<Entry>();
    protected final ConcurrentLinkedQueue<BroadcasterLifeCyclePolicyListener> lifeCycleListeners = new ConcurrentLinkedQueue<BroadcasterLifeCyclePolicyListener>();
    protected final ConcurrentHashMap<String, WriteQueue> writeQueues = new ConcurrentHashMap<String, WriteQueue>();
    protected final WriteQueue uniqueWriteQueue = new WriteQueue("-1");
    protected final AtomicInteger dispatchThread = new AtomicInteger();

    protected Future<?>[] notifierFuture;
    protected Future<?>[] asyncWriteFuture;

    private POLICY policy = POLICY.FIFO;
    private final AtomicLong maxSuspendResource = new AtomicLong(-1);
    private final AtomicBoolean requestScoped = new AtomicBoolean(false);
    private final AtomicBoolean recentActivity = new AtomicBoolean(false);
    private BroadcasterLifeCyclePolicy lifeCyclePolicy = new BroadcasterLifeCyclePolicy.Builder()
            .policy(NEVER).build();
    private Future<?> currentLifecycleTask;
    protected URI uri;
    protected AtmosphereConfig config;
    private final Object[] awaitBarrier = new Object[0];
    private final AtomicBoolean outOfOrderBroadcastSupported = new AtomicBoolean(false);
    protected int writeTimeoutInSecond = -1;
    protected final AtmosphereResource noOpsResource;
    protected int waitTime = 1000;

    public DefaultBroadcaster(String name, URI uri, AtmosphereConfig config) {
        this.name = name;
        this.uri = uri;
        this.config = config;

        bc = createBroadcasterConfig(config);
        String s = config.getInitParameter(ApplicationConfig.BROADCASTER_CACHE_STRATEGY);
        if (s != null) {
            logger.warn("{} is no longer supported. Use BroadcastInterceptor instead. By default the original message will be cached.", ApplicationConfig.BROADCASTER_CACHE_STRATEGY);
        }
        s = config.getInitParameter(OUT_OF_ORDER_BROADCAST);
        if (s != null) {
            outOfOrderBroadcastSupported.set(Boolean.valueOf(s));
        }

        s = config.getInitParameter(BROADCASTER_WAIT_TIME);
        if (s != null) {
            waitTime = Integer.valueOf(s);
        }

        s = config.getInitParameter(ApplicationConfig.WRITE_TIMEOUT);
        if (s != null) {
            writeTimeoutInSecond = Integer.valueOf(s);
        }
        noOpsResource = AtmosphereResourceFactory.getDefault().create(config, "-1");
        if (outOfOrderBroadcastSupported.get()) {
            logger.debug("{} supports Out Of Order Broadcast: {}", name, outOfOrderBroadcastSupported.get());
        }
    }

    public DefaultBroadcaster(String name, AtmosphereConfig config) {
        this(name, URI.create("http://localhost"), config);
    }

    /**
     * Create {@link BroadcasterConfig}
     *
     * @param config the {@link AtmosphereConfig}
     * @return an instance of {@link BroadcasterConfig}
     */
    protected BroadcasterConfig createBroadcasterConfig(AtmosphereConfig config) {
        return new BroadcasterConfig(config.framework().broadcasterFilters, config, getID());
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void destroy() {

        if (notifyOnPreDestroy()) return;

        if (destroyed.getAndSet(true)) return;
        notifyDestroyListener();

        try {
            logger.trace("Broadcaster {} is being destroyed and cannot be re-used. Policy was {}", getID(), policy);
            logger.trace("Broadcaster {} is being destroyed and cannot be re-used. Resources are {}", getID(), resources);

            if (config.getBroadcasterFactory() != null) {
                config.getBroadcasterFactory().remove(this, this.getID());
            }

            if (currentLifecycleTask != null) {
                currentLifecycleTask.cancel(true);
            }
            started.set(false);

            releaseExternalResources();
            killReactiveThreads();

            if (bc != null) {
                bc.destroy();
            }

            resources.clear();
            broadcastOnResume.clear();
            messages.clear();
            delayedBroadcast.clear();
            broadcasterListeners.clear();
            writeQueues.clear();
        } catch (Throwable t) {
            logger.error("Unexpected exception during Broadcaster destroy {}", getID(), t);
        }
    }

    /**
     * {@inheritDoc}
     */
    public Collection<AtmosphereResource> getAtmosphereResources() {
        return Collections.unmodifiableCollection(resources);
    }

    /**
     * {@inheritDoc}
     */
    public void setScope(SCOPE scope) {
        if (destroyed.get()) {
            logger.debug(DESTROYED, getID(), "setScope");
            return;
        }

        this.scope = scope;
        if (scope != SCOPE.REQUEST) {
            return;
        }

        logger.debug("Changing broadcaster scope for {}. This broadcaster will be destroyed.", getID());
        synchronized (resources) {
            try {
                // Next, we need to create a new broadcaster per resource.
                for (AtmosphereResource resource : resources) {
                    Broadcaster b = config.getBroadcasterFactory()
                            .get(getClass(), getClass().getSimpleName() + "/" + UUID.randomUUID());

                    /**
                     * REQUEST_SCOPE means one BroadcasterCache per Broadcaster,
                     */
                    if (DefaultBroadcaster.class.isAssignableFrom(this.getClass())) {
                        BroadcasterCache cache = bc.getBroadcasterCache().getClass().newInstance();
                        InjectorProvider.getInjector().inject(cache);
                        b.getBroadcasterConfig().setBroadcasterCache(cache);
                    }

                    resource.setBroadcaster(b);
                    b.setScope(SCOPE.REQUEST);
                    if (resource.getAtmosphereResourceEvent().isSuspended()) {
                        b.addAtmosphereResource(resource);
                    }
                    logger.debug("Resource {} not using broadcaster {}", resource, b.getID());
                }

                // Do not destroy because this is a new Broadcaster
                if (resources.isEmpty()) {
                    return;
                }

                destroy();
            } catch (Exception e) {
                logger.error("Failed to set request scope for current resources", e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public SCOPE getScope() {
        return scope;
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void setID(String id) {
        if (id == null) {
            id = getClass().getSimpleName() + "/" + UUID.randomUUID();
        }

        if (config.getBroadcasterFactory() == null)
            return; // we are shutdown or destroyed, but someone still reference

        Broadcaster b = config.getBroadcasterFactory().lookup(this.getClass(), id);
        if (b != null && b.getScope() == SCOPE.REQUEST) {
            throw new IllegalStateException("Broadcaster ID already assigned to SCOPE.REQUEST. Cannot change the id");
        } else if (b != null) {
            return;
        }

        config.getBroadcasterFactory().remove(this, name);
        this.name = id;
        config.getBroadcasterFactory().add(this, name);

        bc.broadcasterID(name);
    }

    /**
     * {@inheritDoc}
     */
    public String getID() {
        return name;
    }

    /**
     * {@inheritDoc}
     */
    public void resumeAll() {
        synchronized (resources) {
            for (AtmosphereResource r : resources) {
                try {
                    r.resume();
                } catch (Throwable t) {
                    logger.trace("resumeAll", t);
                } finally {
                    removeAtmosphereResource(r);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void releaseExternalResources() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBroadcasterLifeCyclePolicy(final BroadcasterLifeCyclePolicy lifeCyclePolicy) {
        this.lifeCyclePolicy = lifeCyclePolicy;
        if (currentLifecycleTask != null) {
            currentLifecycleTask.cancel(false);
        }

        if (bc != null && bc.getScheduledExecutorService() == null) {
            logger.error("No Broadcaster's SchedulerExecutorService has been configured on {}. BroadcasterLifeCyclePolicy won't work.", getID());
            return;
        }

        if (lifeCyclePolicy.getLifeCyclePolicy() == IDLE
                || lifeCyclePolicy.getLifeCyclePolicy() == IDLE_RESUME
                || lifeCyclePolicy.getLifeCyclePolicy() == IDLE_DESTROY) {

            recentActivity.set(false);

            int time = lifeCyclePolicy.getTimeout();
            if (time == -1) {
                throw new IllegalStateException("BroadcasterLifeCyclePolicy time is not set");
            }

            final AtomicReference<Future<?>> ref = new AtomicReference<Future<?>>();
            currentLifecycleTask = bc.getScheduledExecutorService().scheduleAtFixedRate(new Runnable() {

                @Override
                public void run() {
                    try {

                        // Check for activity since the last execution.
                        if (recentActivity.getAndSet(false)) {
                            return;
                        } else if (resources.isEmpty()) {
                            if (lifeCyclePolicy.getLifeCyclePolicy() == IDLE) {
                                notifyEmptyListener();
                                notifyIdleListener();

                                releaseExternalResources();
                                logger.debug("Applying BroadcasterLifeCyclePolicy IDLE policy to Broadcaster {}", getID());
                            } else if (lifeCyclePolicy.getLifeCyclePolicy() == IDLE_DESTROY) {
                                notifyEmptyListener();
                                notifyIdleListener();

                                destroy(false);
                                logger.debug("Applying BroadcasterLifeCyclePolicy IDLE_DESTROY policy to Broadcaster {}", getID());
                            }
                        } else if (lifeCyclePolicy.getLifeCyclePolicy() == IDLE_RESUME) {
                            notifyIdleListener();

                            destroy(true);
                            logger.debug("Applying BroadcasterLifeCyclePolicy IDLE_RESUME policy to Broadcaster {}", getID());
                        }
                    } catch (Throwable t) {
                        if (destroyed.get()) {
                            logger.trace("Scheduled BroadcasterLifeCyclePolicy exception", t);
                        } else {
                            logger.warn("Scheduled BroadcasterLifeCyclePolicy exception", t);
                        }
                    }
                }

                void destroy(boolean resume) {

                    if (resume) {
                        logger.info("All AtmosphereResource will now be resumed from Broadcaster {}", getID());
                        resumeAll();
                    }

                    DefaultBroadcaster.this.destroy();
                    /**
                     * The value may be null if the timeout is too low. Hopefully next execution will
                     * cancel the task properly.
                     */
                    if (ref.get() != null) {
                        currentLifecycleTask.cancel(true);
                    }
                }

            }, time, time, lifeCyclePolicy.getTimeUnit());
            ref.set(currentLifecycleTask);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addBroadcasterLifeCyclePolicyListener(BroadcasterLifeCyclePolicyListener b) {
        lifeCycleListeners.add(b);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeBroadcasterLifeCyclePolicyListener(BroadcasterLifeCyclePolicyListener b) {
        lifeCycleListeners.remove(b);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDestroyed() {
        return destroyed.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Object> awaitAndBroadcast(Object t, long time, TimeUnit timeUnit) {
        if (resources.isEmpty()) {
            synchronized (awaitBarrier) {
                try {
                    logger.trace("Awaiting for AtmosphereResource for {} {}", time, timeUnit);
                    awaitBarrier.wait(translateTimeUnit(time, timeUnit));
                } catch (Throwable e) {
                    logger.warn("awaitAndBroadcast", e);
                    return null;
                }
            }
        }
        return broadcast(t);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Broadcaster addBroadcasterListener(BroadcasterListener b) {
        if (!broadcasterListeners.contains(b)) {
            broadcasterListeners.add(b);
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Broadcaster removeBroadcasterListener(BroadcasterListener b) {
        broadcasterListeners.remove(b);
        return this;
    }

    protected Runnable getBroadcastHandler() {
        return new Runnable() {
            public void run() {
                while (!isDestroyed()) {
                    Entry msg = null;
                    try {
                        msg = messages.poll(waitTime, TimeUnit.MILLISECONDS);
                        if (msg == null) {
                            dispatchThread.decrementAndGet();
                            return;
                        }
                    } catch (InterruptedException ex) {
                        logger.trace("{} got interrupted for Broadcaster {}", Thread.currentThread().getName(), getID());
                        logger.trace("", ex);
                        return;
                    } finally {
                        if (outOfOrderBroadcastSupported.get()) {
                            bc.getExecutorService().submit(this);
                        }
                    }

                    try {
                        logger.trace("{} is about to broadcast {}", getID(), msg);
                        push(msg);
                    } catch (Throwable ex) {
                        if (!started.get() || destroyed.get()) {
                            logger.trace("Failed to submit broadcast handler runnable on shutdown for Broadcaster {}", getID(), ex);
                            return;
                        } else {
                            logger.warn("This message {} will be lost", msg);
                            logger.debug("Failed to submit broadcast handler runnable to for Broadcaster {}", getID(), ex);
                        }
                    } finally {
                        if (outOfOrderBroadcastSupported.get()) {
                            return;
                        }
                    }
                }
            }
        };
    }

    protected Runnable getAsyncWriteHandler(final WriteQueue writeQueue) {
        return new Runnable() {
            public void run() {
                while (!isDestroyed()) {
                    AsyncWriteToken token = null;
                    try {
                        token = writeQueue.queue.poll(waitTime, TimeUnit.MILLISECONDS);
                        if (token == null && !outOfOrderBroadcastSupported.get()) {
                            synchronized (writeQueue) {
                                if (writeQueue.queue.size() == 0) {
                                    writeQueue.monitored.set(false);
                                    writeQueues.remove(writeQueue.uuid);
                                    return;
                                }
                            }
                        } else if (token == null) {
                            return;
                        }
                    } catch (InterruptedException ex) {
                        logger.trace("{} got interrupted for Broadcaster {}", Thread.currentThread().getName(), getID());
                        logger.trace("", ex);
                        return;
                    } finally {
                        if (!bc.getAsyncWriteService().isShutdown() && outOfOrderBroadcastSupported.get()) {
                            bc.getAsyncWriteService().submit(this);
                        }
                    }

                    // Shield us from https://github.com/Atmosphere/atmosphere/issues/1187
                    if (token != null) {
                        synchronized (token.resource) {
                            try {
                                logger.trace("About to write to {}", token.resource);
                                executeAsyncWrite(token);
                            } catch (Throwable ex) {
                                if (!started.get() || destroyed.get()) {
                                    logger.trace("Failed to execute a write operation. Broadcaster is destroyed or not yet started for Broadcaster {}", getID(), ex);
                                    return;
                                } else {
                                    if (token != null) {
                                        logger.warn("This message {} will be lost for AtmosphereResource {}, adding it to the BroadcasterCache",
                                                token.originalMessage, token.resource != null ? token.resource.uuid() : "null");
                                        cacheLostMessage(token.resource, token, true);
                                    }
                                    logger.debug("Failed to execute a write operation for Broadcaster {}", getID(), ex);
                                }
                            } finally {
                                if (!bc.getAsyncWriteService().isShutdown() && outOfOrderBroadcastSupported.get()) {
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        };
    }

    protected void start() {
        if (!started.getAndSet(true)) {
            bc.getBroadcasterCache().start();

            setID(name);
            // Only start if we know a child haven't started them.
            if (notifierFuture == null && asyncWriteFuture == null) {
                spawnReactor();
            }
        }
    }

    protected void spawnReactor() {
        killReactiveThreads();

        int threads = outOfOrderBroadcastSupported.get() ? reactiveThreadsCount() : 1;
        notifierFuture = new Future<?>[threads];

        if (outOfOrderBroadcastSupported.get()) {
            asyncWriteFuture = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                notifierFuture[i] = bc.getExecutorService().submit(getBroadcastHandler());
                asyncWriteFuture[i] = bc.getExecutorService().submit(getAsyncWriteHandler(uniqueWriteQueue));
            }
        } else {
            notifierFuture[0] = bc.getExecutorService().submit(getBroadcastHandler());
        }
        dispatchThread.set(threads);
    }

    protected void killReactiveThreads() {
        if (notifierFuture != null) {
            for (Future<?> f : notifierFuture) {
                if (f != null)
                    f.cancel(false);
            }
        }

        if (asyncWriteFuture != null) {
            for (Future<?> f : asyncWriteFuture) {
                if (f != null)
                    f.cancel(false);
            }
        }
    }

    /**
     * Return the default number of reactive threads that will be waiting for work when a broadcast operation
     * is executed.
     *
     * @return the default number of reactive threads
     */
    protected int reactiveThreadsCount() {
        return Runtime.getRuntime().availableProcessors() * 2;
    }

    protected void push(Entry entry) {
        if (destroyed.get()) {
            return;
        }

        deliverPush(entry, true);
    }

    protected void deliverPush(Entry entry, boolean rec) {
        recentActivity.set(true);

        String prevMessage = entry.message.toString();
        if (rec && !delayedBroadcast.isEmpty()) {
            Iterator<Entry> i = delayedBroadcast.iterator();
            StringBuilder b = new StringBuilder();
            while (i.hasNext()) {
                Entry e = i.next();
                e.future.cancel(true);
                try {
                    // Append so we do a single flush
                    if (e.message instanceof String
                            && entry.message instanceof String) {
                        b.append(e.message);
                    } else {
                        deliverPush(e, false);
                    }
                } finally {
                    i.remove();
                }
            }

            if (b.length() > 0) {
                entry.message = b.append(entry.message).toString();
            }
        }

        Object finalMsg = callable(entry.message);
        if (finalMsg == null) {
            logger.error("Callable exception. Please catch all exception from you callable. Message {} will be lost and all AtmosphereResource " +
                    "associated with this Broadcaster resumed.", entry.message);
            entryDone(entry.future);
            switch (entry.type) {
                case ALL:
                    synchronized (resources) {
                        for (AtmosphereResource r : resources) {
                            if (r.transport().equals(AtmosphereResource.TRANSPORT.JSONP) || r.transport().equals(AtmosphereResource.TRANSPORT.LONG_POLLING))
                                try {
                                    r.resume();
                                } catch (Throwable t) {
                                    logger.trace("resumeAll", t);
                                }
                        }
                    }
                    break;
                case RESOURCE:
                    entry.resource.resume();
                    break;
                case SET:
                    for (AtmosphereResource r : entry.resources) {
                        r.resume();
                    }
                    break;
            }
            return;
        }

        Object prevM = entry.originalMessage;
        entry.originalMessage = (entry.originalMessage != entry.message ? callable(entry.originalMessage) : finalMsg);

        if (entry.originalMessage == null) {
            logger.trace("Broadcast message was null {}", prevM);
            entryDone(entry.future);
            return;
        }

        entry.message = finalMsg;

        // We cache first, and if the broadcast succeed, we will remove it.
        AtmosphereResource cache = entry.type != Entry.TYPE.RESOURCE ? null : entry.resource;
        entry.cache = bc.getBroadcasterCache().addToCache(getID(), cache, new BroadcastMessage(entry.originalMessage));

        if (resources.isEmpty()) {
            entryDone(entry.future);
            return;
        }

        try {
            if (logger.isTraceEnabled()) {
                for (AtmosphereResource r : resources) {
                    logger.trace("AtmosphereResource {} available for {}", r.uuid(), entry.message);
                }
            }

            boolean hasFilters = bc.hasPerRequestFilters();
            Object beforeProcessingMessage = entry.message;
            switch (entry.type) {
                case ALL:
                    for (AtmosphereResource r : resources) {
                        entry.message = beforeProcessingMessage;
                        boolean deliverMessage = perRequestFilter(r, entry, false);

                        if (!deliverMessage || entry.message == null) {
                            logger.debug("Skipping broadcast delivery resource {} ", r.uuid());
                            continue;
                        }

                        if (entry.writeLocally) {
                            queueWriteIO(r, hasFilters ? new Entry(r, entry) : entry);
                        }
                    }
                    break;
                case RESOURCE:
                    boolean deliverMessage = perRequestFilter(entry.resource, entry, false);

                    if (!deliverMessage || entry.message == null) {
                        logger.debug("Skipping broadcast delivery resource {} ", entry.resource.uuid());
                        return;
                    }

                    if (entry.writeLocally) {
                        queueWriteIO(entry.resource, entry);
                    }
                    break;
                case SET:
                    for (AtmosphereResource r : entry.resources) {
                        entry.message = beforeProcessingMessage;
                        deliverMessage = perRequestFilter(r, entry, false);

                        if (!deliverMessage || entry.message == null) {
                            logger.debug("Skipping broadcast delivery resource {} ", r.uuid());
                            continue;
                        }

                        if (entry.writeLocally) {
                            queueWriteIO(r, hasFilters ? new Entry(r, entry) : entry);
                        }
                    }
                    break;
            }

            entry.message = prevMessage;
        } catch (InterruptedException ex) {
            logger.debug(ex.getMessage(), ex);
        }
    }

    protected void queueWriteIO(AtmosphereResource r, Entry entry) throws InterruptedException {
        // The onStateChange/onRequest may change the isResumed value, hence we need to make sure only one thread flip
        // the switch to garantee the Entry will be cached in the order it was broadcasted.
        // Without synchronizing we may end up with a out of order BroadcasterCache queue.
        if (!bc.getBroadcasterCache().getClass().equals(BroadcasterCache.DEFAULT.getClass().getName())) {
            if (r.isResumed() || r.isCancelled()) {
                logger.trace("AtmosphereResource {} has been resumed or cancelled, unable to Broadcast message {}", r.uuid(), entry.message);
                return;
            }
        }

        AsyncWriteToken w = new AsyncWriteToken(r, entry.message, entry.future, entry.originalMessage, entry.cache);
        if (!outOfOrderBroadcastSupported.get()) {
            WriteQueue writeQueue = writeQueues.get(r.uuid());
            if (writeQueue == null) {
                writeQueue = new WriteQueue(r.uuid());
                writeQueues.put(r.uuid(), writeQueue);
            }

            writeQueue.queue.put(w);
            synchronized (writeQueue) {
                if (!writeQueue.monitored.getAndSet(true)) {
                    logger.trace("Broadcaster {} is about to queueWriteIO for AtmosphereResource {}", name, r.uuid());
                    bc.getAsyncWriteService().submit(getAsyncWriteHandler(writeQueue));
                }
            }
        } else {
            uniqueWriteQueue.queue.offer(w);
        }
    }

    private final static class WriteQueue {
        final BlockingQueue<AsyncWriteToken> queue = new LinkedBlockingQueue<AsyncWriteToken>();
        final AtomicBoolean monitored = new AtomicBoolean();
        final String uuid;

        private WriteQueue(String uuid) {
            this.uuid = uuid;
        }
    }

    protected boolean perRequestFilter(AtmosphereResource r, Entry msg, boolean cache) {
        // A broadcaster#broadcast(msg,Set) may contains null value.
        if (r == null) {
            logger.trace("Null AtmosphereResource passed inside a Set");
            return false;
        }

        if (isAtmosphereResourceValid(r)) {
            if (bc.hasPerRequestFilters()) {
                BroadcastAction a = bc.filter(r, msg.message, msg.originalMessage);
                if (a.action() == BroadcastAction.ACTION.ABORT) {
                    return false;
                }
                msg.message = a.message();
            }
        } else {
            logger.warn("Request is no longer valid {}, Message {} will be cached", r.uuid(), msg.originalMessage);
            if (cache) {
                bc.getBroadcasterCache().addToCache(getID(), r, new BroadcastMessage(msg.originalMessage));
            }
            return false;
        }
        return true;
    }

    private Object callable(Object msg) {
        if (Callable.class.isAssignableFrom(msg.getClass())) {
            try {
                return Callable.class.cast(msg).call();
            } catch (Exception e) {
                logger.warn("Callable exception", e);
                return null;
            }
        }
        return msg;
    }

    protected void executeAsyncWrite(final AsyncWriteToken token) {
        boolean notifyListeners = true;
        boolean lostCandidate = false;

        final AtmosphereResourceEventImpl event = (AtmosphereResourceEventImpl) token.resource.getAtmosphereResourceEvent();
        final AtmosphereResourceImpl r = AtmosphereResourceImpl.class.cast(token.resource);
        try {
            event.setMessage(token.msg);

            // Make sure we cache the message in case the AtmosphereResource has been cancelled, resumed or the client disconnected.
            if (!isAtmosphereResourceValid(r)) {
                logger.debug("AtmosphereResource {} state is invalid for Broadcaster {}. ", r.uuid(), name);
                removeAtmosphereResource(r, false);
                return;
            }

            bc.getBroadcasterCache().clearCache(getID(), r, token.cache);
            try {
                r.getRequest().setAttribute(getID(), token.future);
                r.getRequest().setAttribute(MAX_INACTIVE, System.currentTimeMillis());
            } catch (Throwable t) {
                logger.debug("Invalid AtmosphereResource state {}. The connection has been remotely" +
                        " closed and message {} will be added to the configured BroadcasterCache for later retrieval", r.uuid(), event.getMessage());
                logger.trace("If you are using Tomcat 7.0.22 and lower, your most probably hitting http://is.gd/NqicFT");
                logger.trace("", t);
                // The Request/Response associated with the AtmosphereResource has already been written and commited
                removeAtmosphereResource(r, false);
                config.getBroadcasterFactory().removeAllAtmosphereResource(r);
                event.setCancelled(true);
                event.setThrowable(t);
                r.setIsInScope(false);
                lostCandidate = true;
                return;
            }

            r.getRequest().setAttribute(ASYNC_TOKEN, token);
            prepareInvokeOnStateChange(r, event);
            r.getRequest().setAttribute(FrameworkConfig.MESSAGE_WRITTEN, "true");
        } finally {
            if (notifyListeners) {
                r.notifyListeners();
            }

            entryDone(token.future);

            if (lostCandidate) {
                cacheLostMessage(r, token, true);
            }
            token.destroy();
        }
    }

    protected boolean checkCachedAndPush(final AtmosphereResource r, final AtmosphereResourceEvent e) {
        boolean cache = retrieveTrackedBroadcast(r, e);

        if (!cache) return false;

        if (!((List) e.getMessage()).isEmpty()) {
            logger.debug("Sending cached message {} to {}", e.getMessage(), r.uuid());

            List<Object> cacheMessages = (List) e.getMessage();
            BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(e.getMessage(), 1, this);
            LinkedList<Object> filteredMessage = new LinkedList<Object>();
            Entry entry;
            Object newMessage;
            for (Object o : cacheMessages) {
                newMessage = filter(o);
                if (newMessage == null) {
                    continue;
                }

                entry = new Entry(newMessage, r, f, o);
                // Can be aborted by a Filter
                if (!perRequestFilter(r, entry, true)) {
                    continue;
                }

                if (entry.message != null) {
                    filteredMessage.addLast(newMessage);
                }
            }

            if (filteredMessage.size() == 0) {
                return false;
            }
            e.setMessage(filteredMessage);

            r.getRequest().setAttribute(CACHED, "true");
            // Must make sure execute only one thread
            synchronized (r) {
                try {
                    prepareInvokeOnStateChange(r, e);
                } catch (Throwable t) {
                    // An exception occured
                    logger.error("Unable to write cached message {} for {}", e.getMessage(), r.uuid());
                    logger.error("", t);
                    for (Object o : (List)e.getMessage()) {
                        bc.getBroadcasterCache().addToCache(getID(), r, new BroadcastMessage(o));
                    }
                    return true;
                }

                // TODO: CAST is dangerous
                for (AtmosphereResourceEventListener l : AtmosphereResourceImpl.class.cast(r).atmosphereResourceEventListener()) {
                    l.onBroadcast(e);
                }

                switch (r.transport()) {
                    case UNDEFINED:
                    case JSONP:
                    case AJAX:
                    case LONG_POLLING:
                        return true;
                    case SSE:
                        break;
                    default:
                        try {
                            r.getResponse().flushBuffer();
                        } catch (IOException ioe) {
                            logger.trace("", ioe);
                            AtmosphereResourceImpl.class.cast(r)._destroy();
                        }
                        break;
                }
            }
        }
        return false;
    }

    protected boolean retrieveTrackedBroadcast(final AtmosphereResource r, final AtmosphereResourceEvent e) {
        logger.trace("Checking cached message for {}", r.uuid());
        List<?> missedMsg = bc.getBroadcasterCache().retrieveFromCache(getID(), r);
        if (missedMsg != null && !missedMsg.isEmpty()) {
            e.setMessage(missedMsg);
            return true;
        }
        return false;
    }

    protected void invokeOnStateChange(final AtmosphereResource r, final AtmosphereResourceEvent e) {
        try {
            logger.trace("{} is broadcasting to {}", name, r.uuid());
            r.getAtmosphereHandler().onStateChange(e);
        } catch (Throwable t) {
            if (!InterruptedException.class.isAssignableFrom(t.getClass())) {
                onException(t, r);
            }
        }
    }

    protected void prepareInvokeOnStateChange(final AtmosphereResource r, final AtmosphereResourceEvent e) {
        if (writeTimeoutInSecond != -1) {
            logger.trace("Registering Write timeout {} for {}", writeTimeoutInSecond, r.uuid());
            WriteOperation w = new WriteOperation(r, e, Thread.currentThread());
            bc.getScheduledExecutorService().schedule(w, writeTimeoutInSecond, TimeUnit.MILLISECONDS);

            try {
                w.call();
            } catch (Exception ex) {
                logger.warn("", ex);
            }
        } else {
            invokeOnStateChange(r, e);
        }
    }

    final class WriteOperation implements Callable<Object> {

        private final AtmosphereResource r;
        private final AtmosphereResourceEvent e;
        private AtomicBoolean completed = new AtomicBoolean();
        private AtomicBoolean executed = new AtomicBoolean();
        private final Thread ioThread;

        private WriteOperation(AtmosphereResource r, AtmosphereResourceEvent e, Thread ioThread) {
            this.r = r;
            this.e = e;
            this.ioThread = ioThread;
        }

        @Override
        public Object call() throws Exception {
            if (!completed.getAndSet(true)) {
                invokeOnStateChange(r, e);
                logger.trace("Cancelling Write timeout {} for {}", writeTimeoutInSecond, r.uuid());
                executed.set(true);
            } else if (!executed.get()) {
                // https://github.com/Atmosphere/atmosphere/issues/902
                try {
                    ioThread.interrupt();
                } catch (Throwable t) {
                    // Swallow, this is already enough embarrassing
                    logger.trace("I/O failure, unable to interrupt the thread", t);
                }

                logger.trace("Honoring Write timeout {} for {}", writeTimeoutInSecond, r.uuid());
                onException(new IOException("Unable to write after " + writeTimeoutInSecond), r);
                AtmosphereResourceImpl.class.cast(r).cancel();
            }
            return null;
        }
    }

    public void onException(Throwable t, final AtmosphereResource ar) {
        onException(t, ar, true);
    }

    public void onException(Throwable t, final AtmosphereResource ar, boolean notifyAndCache) {
        final AtmosphereResourceImpl r = AtmosphereResourceImpl.class.cast(ar);

        // Remove to prevent other broadcast to re-use it.
        removeAtmosphereResource(r);
        logger.debug("Unexpected exception for AtmosphereResource {} and Broadcaster " + name, ar.uuid(), t);

        if (notifyAndCache) {
            final AtmosphereResourceEventImpl event = r.getAtmosphereResourceEvent();
            event.setThrowable(t);

            r.notifyListeners(event);
            r.removeEventListeners();
        }

        if (notifyAndCache) {
            cacheLostMessage(r, (AsyncWriteToken) r.getRequest(false).getAttribute(ASYNC_TOKEN), notifyAndCache);
        }

        /**
         * Make sure we resume the connection on every IOException.
         */
        if (bc != null && bc.getAsyncWriteService() != null) {
            bc.getAsyncWriteService().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        r.resume();
                    } catch (Throwable t) {
                        logger.trace("Was unable to resume a corrupted AtmosphereResource {}", r);
                        logger.trace("Cause", t);
                    }
                }
            });
        } else {
            r.resume();
        }
    }

    /**
     * Cache the message because an unexpected exception occurred.
     *
     * @param r {@link AtmosphereResource}
     */
    public void cacheLostMessage(AtmosphereResource r) {
        // Quite ugly cast that need to be fixed all over the place
        cacheLostMessage(r, (AsyncWriteToken)
                AtmosphereResourceImpl.class.cast(r).getRequest(false).getAttribute(ASYNC_TOKEN));
    }

    /**
     * Cache the message because an unexpected exception occurred.
     *
     * @param r {@link AtmosphereResource}
     */
    public void cacheLostMessage(AtmosphereResource r, boolean force) {
        // Quite ugly cast that need to be fixed all over the place
        cacheLostMessage(r, (AsyncWriteToken)
                AtmosphereResourceImpl.class.cast(r).getRequest(false).getAttribute(ASYNC_TOKEN), force);
    }

    /**
     * Cache the message because an unexpected exception occurred.
     *
     * @param r {@link AtmosphereResource}
     */
    public void cacheLostMessage(AtmosphereResource r, AsyncWriteToken token) {
        cacheLostMessage(r, token, false);
    }

    /**
     * Cache the message because an unexpected exception occurred.
     *
     * @param r {@link AtmosphereResource}
     */
    public void cacheLostMessage(AtmosphereResource r, AsyncWriteToken token, boolean force) {
        if (!force) {
            return;
        }

        try {
            if (token != null && token.originalMessage != null) {
                bc.getBroadcasterCache().addToCache(getID(), r, new BroadcastMessage(String.valueOf(token.future.hashCode()), token.originalMessage));
                logger.trace("Lost message cached {}", token.originalMessage);
            }
        } catch (Throwable t2) {
            logger.error("Unable to cache message {} for AtmosphereResource {}", token.originalMessage, r != null ? r.uuid() : "");
            logger.error("Unable to cache message", t2);
        }
    }

    @Override
    public void setSuspendPolicy(long maxSuspendResource, POLICY policy) {
        this.maxSuspendResource.set(maxSuspendResource);
        this.policy = policy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Object> broadcast(Object msg) {

        if (destroyed.get()) {
            logger.debug(DESTROYED, getID(), "broadcast(T msg)");
            return futureDone(msg);
        }

        start();
        Object newMsg = filter(msg);
        if (newMsg == null) {
            logger.debug("Broadcast Interrupted {}", msg);
            return futureDone(msg);
        }

        int callee = resources.size() == 0 ? 1 : resources.size();

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg, callee, this);
        dispatchMessages(new Entry(newMsg, f, msg));
        return f;
    }

    protected BroadcasterFuture<Object> futureDone(Object msg) {
        notifyBroadcastListener();
        return (new BroadcasterFuture<Object>(msg, this)).done();
    }

    protected void dispatchMessages(Entry e) {
        messages.offer(e);

        if (dispatchThread.get() == 0) {
            dispatchThread.incrementAndGet();
            getBroadcasterConfig().getExecutorService().submit(getBroadcastHandler());
        }
    }

    /**
     * Invoke the {@link BroadcastFilter}
     *
     * @param msg
     * @return
     */
    protected Object filter(Object msg) {
        BroadcastAction a = bc.filter(msg);
        if (a.action() == BroadcastAction.ACTION.ABORT || msg == null)
            return null;
        else
            return a.message();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Object> broadcast(Object msg, AtmosphereResource r) {

        if (destroyed.get()) {
            logger.debug(DESTROYED, getID(), "broadcast(T msg, AtmosphereResource r");
            return futureDone(msg);
        }

        start();
        Object newMsg = filter(msg);
        if (newMsg == null) return futureDone(msg);

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg, 1, this);
        dispatchMessages(new Entry(newMsg, r, f, msg));
        return f;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Object> broadcastOnResume(Object msg) {

        if (destroyed.get()) {
            logger.debug(DESTROYED, getID(), "broadcastOnResume(T msg)");
            return futureDone(msg);
        }

        start();
        Object newMsg = filter(msg);
        if (newMsg == null) return futureDone(msg);

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg, resources.size(), this);
        broadcastOnResume.offer(new Entry(newMsg, f, msg));
        return f;
    }

    protected void broadcastOnResume(AtmosphereResource r) {
        Iterator<Entry> i = broadcastOnResume.iterator();
        while (i.hasNext()) {
            Entry e = i.next();
            push(new Entry(r, e));
        }

        if (resources.isEmpty()) {
            broadcastOnResume.clear();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Object> broadcast(Object msg, Set<AtmosphereResource> subset) {

        if (destroyed.get()) {
            logger.debug(DESTROYED, getID(), "broadcast(T msg, Set<AtmosphereResource> subset)");
            return futureDone(msg);
        }

        start();
        Object newMsg = filter(msg);
        if (newMsg == null) return futureDone(msg);

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(null, newMsg, subset.size(), this);
        dispatchMessages(new Entry(newMsg, subset, f, msg));
        return f;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Broadcaster addAtmosphereResource(AtmosphereResource r) {
        try {
            if (destroyed.get()) {
                logger.debug(DESTROYED, getID(), "addAtmosphereResource(AtmosphereResource r");
                return this;
            }

            start();
            if (scope == SCOPE.REQUEST && requestScoped.getAndSet(true)) {
                throw new IllegalStateException("Broadcaster " + this
                        + " cannot be used as its scope is set to REQUEST");
            }

            // To avoid excessive synchronization, we allow resources.size() to get larger that maxSuspendResource
            if (maxSuspendResource.get() > 0 && resources.size() >= maxSuspendResource.get()) {
                // Resume the first in.
                if (policy == POLICY.FIFO) {
                    // TODO handle null return from poll()
                    AtmosphereResource resource = resources.poll();
                    try {
                        logger.warn("Too many resource. Forcing resume of {} ", resource.uuid());
                        resource.resume();
                    } catch (Throwable t) {
                        logger.warn("failed to resume resource {} ", resource, t);
                    }
                } else if (policy == POLICY.REJECT) {
                    throw new RejectedExecutionException(String.format("Maximum suspended AtmosphereResources %s", maxSuspendResource));
                }
            }

            if (!r.isSuspended()) {
                logger.warn("AtmosphereResource {} is not suspended. If cached messages exists, this may cause unexpected situation. Suspend first", r.uuid());
            }

            if (resources.contains(r)) {
                logger.debug("Duplicate resource {}", r.uuid());
                return this;
            }

            // Only synchronize if we have a valid BroadcasterCache
            if (!bc.getBroadcasterCache().getClass().equals(BroadcasterCache.DEFAULT.getClass().getName())) {
                // In case we are adding messages to the cache, we need to make sure the operation is done before.
                synchronized (resources) {
                    cacheAndSuspend(r);
                }
            } else {
                cacheAndSuspend(r);
            }
        } finally {
            // OK reset
            if (resources.size() > 0) {
                synchronized (awaitBarrier) {
                    awaitBarrier.notifyAll();
                }
            }
        }
        return this;
    }

    /**
     * Look in the cache to see of there are messages available, and takes the appropriate actions.
     *
     * @param r AtmosphereResource
     */
    protected void cacheAndSuspend(AtmosphereResource r) {
        // In case the connection is closed, for whatever reason
        if (!isAtmosphereResourceValid(r)) {
            logger.debug("Unable to add AtmosphereResource {}", r.uuid());
            return;
        }

        if (resources.isEmpty()) {
            config.getBroadcasterFactory().add(this, name);
        }

        boolean wasResumed = checkCachedAndPush(r, r.getAtmosphereResourceEvent());
        if (!wasResumed && isAtmosphereResourceValid(r)) {
            logger.trace("Associating AtmosphereResource {} with Broadcaster {}", r.uuid(), getID());

            String parentUUID = (String) r.getRequest().getAttribute(SUSPENDED_ATMOSPHERE_RESOURCE_UUID);
            Boolean backwardCompatible = Boolean.parseBoolean(config.getInitParameter(ApplicationConfig.BACKWARD_COMPATIBLE_WEBSOCKET_BEHAVIOR));
            if (!backwardCompatible && parentUUID != null) {
                AtmosphereResource p = AtmosphereResourceFactory.getDefault().find(parentUUID);
                if (p != null && !resources.contains(p)) {
                    notifyAndAdd(p);
                } else if (p == null) {
                    notifyAndAdd(r);
                }
            } else {
                notifyAndAdd(r);
            }
        } else if (!wasResumed) {
            logger.debug("Unable to add AtmosphereResource {} to {}", r.uuid(), name);
        }
    }

    protected void notifyAndAdd(AtmosphereResource r) {
        resources.add(r);
        notifyOnAddAtmosphereResourceListener(r);
    }

    private boolean isAtmosphereResourceValid(AtmosphereResource r) {
        return !AtmosphereResourceImpl.class.cast(r).isResumed()
                && !AtmosphereResourceImpl.class.cast(r).isCancelled()
                && AtmosphereResourceImpl.class.cast(r).isInScope();
    }

    protected void entryDone(final BroadcasterFuture<?> f) {
        notifyBroadcastListener();
        if (f != null) f.done();
    }

    protected void notifyBroadcastListener() {
        for (BroadcasterListener b : broadcasterListeners) {
            try {
                b.onComplete(this);
            } catch (Exception ex) {
                logger.warn("", ex);
            }
        }
    }

    protected void notifyOnAddAtmosphereResourceListener(AtmosphereResource r) {
        for (BroadcasterListener b : broadcasterListeners) {
            try {
                b.onAddAtmosphereResource(this, r);
            } catch (Exception ex) {
                logger.warn("", ex);
            }
        }
    }

    protected void notifyOnRemoveAtmosphereResourceListener(AtmosphereResource r) {
        for (BroadcasterListener b : broadcasterListeners) {
            try {
                b.onRemoveAtmosphereResource(this, r);
            } catch (Exception ex) {
                logger.warn("", ex);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Broadcaster removeAtmosphereResource(AtmosphereResource r) {
        return removeAtmosphereResource(r, true);
    }

    protected Broadcaster removeAtmosphereResource(AtmosphereResource r, boolean executeDone) {
        if (destroyed.get()) {
            logger.debug(DESTROYED, getID(), "removeAtmosphereResource(AtmosphereResource r)");
            return this;
        }

        boolean removed;
        synchronized (resources) {
            removed = resources.remove(r);
            if (removed) {
                if (r.isSuspended()) {
                    logger.trace("Excluded from {} : {}", getID(), r.uuid());
                    bc.getBroadcasterCache().excludeFromCache(getID(), r);
                }
                notifyOnRemoveAtmosphereResourceListener(r);
            }
        }

        if (!removed) return this;

        logger.trace("Removing AtmosphereResource {} for Broadcaster {}", r.uuid(), name);
        writeQueues.remove(r.uuid());

        // Here we need to make sure we aren't in the process of broadcasting and unlock the Future.
        if (executeDone) {
            AtmosphereResourceImpl aImpl = AtmosphereResourceImpl.class.cast(r);
            BroadcasterFuture f = (BroadcasterFuture) aImpl.getRequest(false).getAttribute(getID());
            if (f != null && !f.isDone() && !f.isCancelled()) {
                aImpl.getRequest(false).removeAttribute(getID());
                entryDone(f);
            }
        }

        if (resources.isEmpty()) {
            notifyEmptyListener();
            if (scope != SCOPE.REQUEST && lifeCyclePolicy.getLifeCyclePolicy() == EMPTY) {
                releaseExternalResources();
            } else if (scope == SCOPE.REQUEST || lifeCyclePolicy.getLifeCyclePolicy() == EMPTY_DESTROY) {
                config.getBroadcasterFactory().remove(this, name);
                destroy();
            }
        }
        return this;
    }

    protected void notifyIdleListener() {
        for (BroadcasterLifeCyclePolicyListener b : lifeCycleListeners) {
            b.onIdle();
        }
    }

    protected void notifyDestroyListener() {
        for (BroadcasterLifeCyclePolicyListener b : lifeCycleListeners) {
            b.onDestroy();
        }
    }

    protected void notifyEmptyListener() {
        for (BroadcasterLifeCyclePolicyListener b : lifeCycleListeners) {
            b.onEmpty();
        }
    }

    /**
     * Set the {@link BroadcasterConfig} instance.
     *
     * @param bc
     */
    @Override
    public void setBroadcasterConfig(BroadcasterConfig bc) {
        this.bc = bc;
    }

    /**
     * Return the current {@link BroadcasterConfig}
     *
     * @return the current {@link BroadcasterConfig}
     */
    public BroadcasterConfig getBroadcasterConfig() {
        return bc;
    }

    /**
     * {@inheritDoc}
     */
    public Future<Object> delayBroadcast(Object o) {
        return delayBroadcast(o, 0, null);
    }

    /**
     * {@inheritDoc}
     */
    public Future<Object> delayBroadcast(final Object o, long delay, TimeUnit t) {

        if (destroyed.get()) {
            logger.debug(DESTROYED, getID(), "delayBroadcast(final T o, long delay, TimeUnit t)");
            return null;
        }

        start();
        final Object msg = filter(o);
        if (msg == null) return null;

        final BroadcasterFuture<Object> future = new BroadcasterFuture<Object>(msg, this);
        final Entry e = new Entry(msg, future, o);
        Future<Object> f;
        if (delay > 0) {
            f = bc.getScheduledExecutorService().schedule(new Callable<Object>() {

                public Object call() throws Exception {
                    delayedBroadcast.remove(e);
                    if (Callable.class.isAssignableFrom(o.getClass())) {
                        try {
                            Object r = Callable.class.cast(o).call();
                            final Object msg = filter(r);
                            if (msg != null) {
                                Entry entry = new Entry(msg, future, r);
                                push(entry);
                            }
                            return msg;
                        } catch (Exception e1) {
                            logger.error("", e);
                        }
                    }

                    final Object msg = filter(o);
                    final Entry e = new Entry(msg, future, o);
                    push(e);
                    return msg;
                }
            }, delay, t);

            e.future = new BroadcasterFuture<Object>(f, msg, this);
        }
        delayedBroadcast.offer(e);
        return future;
    }

    /**
     * {@inheritDoc}
     */
    public Future<Object> scheduleFixedBroadcast(final Object o, long period, TimeUnit t) {
        return scheduleFixedBroadcast(o, 0, period, t);
    }

    /**
     * {@inheritDoc}
     */
    public Future<Object> scheduleFixedBroadcast(final Object o, long waitFor, long period, TimeUnit t) {

        if (destroyed.get()) {
            logger.debug(DESTROYED, getID(), "scheduleFixedBroadcast(final Object o, long waitFor, long period, TimeUnit t)");
            return null;
        }

        start();
        if (period == 0 || t == null) {
            return null;
        }

        final Object msg = filter(o);
        if (msg == null) return null;

        final BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(msg, DefaultBroadcaster.this);

        return (Future<Object>) bc.getScheduledExecutorService().scheduleWithFixedDelay(new Runnable() {
            public void run() {
                if (Callable.class.isAssignableFrom(o.getClass())) {
                    try {
                        Object r = Callable.class.cast(o).call();
                        final Object msg = filter(r);
                        if (msg != null) {
                            Entry entry = new Entry(msg, f, r);
                            push(entry);
                        }
                        return;
                    } catch (Exception e) {
                        logger.error("", e);
                    }
                }
                final Object msg = filter(o);
                final Entry e = new Entry(msg, f, o);
                push(e);
            }
        }, waitFor, period, t);
    }

    public String toString() {
        return new StringBuilder().append("\n\tName: ").append(name)
                .append("\n\tAtmosphereResource: ").append(resources.size())
                .append("\n\tBroadcasterCache ").append(bc.getBroadcasterCache())
                .append(this.getClass().getName()).append("@").append(this.hashCode())
                .toString();
    }

    protected final static class AsyncWriteToken {

        AtmosphereResource resource;
        Object msg;
        BroadcasterFuture future;
        Object originalMessage;
        CacheMessage cache;

        public AsyncWriteToken(AtmosphereResource resource, Object msg, BroadcasterFuture future, Object originalMessage) {
            this.resource = resource;
            this.msg = msg;
            this.future = future;
            this.originalMessage = originalMessage;
        }

        public AsyncWriteToken(AtmosphereResource resource, Object msg, BroadcasterFuture future, Object originalMessage, CacheMessage cache) {
            this.resource = resource;
            this.msg = msg;
            this.future = future;
            this.originalMessage = originalMessage;
            this.cache = cache;
        }

        public void destroy() {
            this.resource = null;
            this.msg = null;
            this.future = null;
            this.originalMessage = null;
        }

        @Override
        public String toString() {
            return "AsyncWriteToken{" +
                    "resource=" + resource +
                    ", msg=" + msg +
                    ", future=" + future +
                    '}';
        }
    }

    private long translateTimeUnit(long period, TimeUnit tu) {
        if (period == -1) return period;

        switch (tu) {
            case SECONDS:
                return TimeUnit.MILLISECONDS.convert(period, TimeUnit.SECONDS);
            case MINUTES:
                return TimeUnit.MILLISECONDS.convert(period, TimeUnit.MINUTES);
            case HOURS:
                return TimeUnit.MILLISECONDS.convert(period, TimeUnit.HOURS);
            case DAYS:
                return TimeUnit.MILLISECONDS.convert(period, TimeUnit.DAYS);
            case MILLISECONDS:
                return period;
            case MICROSECONDS:
                return TimeUnit.MILLISECONDS.convert(period, TimeUnit.MICROSECONDS);
            case NANOSECONDS:
                return TimeUnit.MILLISECONDS.convert(period, TimeUnit.NANOSECONDS);
        }
        return period;
    }

    boolean notifyOnPreDestroy() {
        for (BroadcasterListener l : broadcasterListeners) {
            try {
                l.onPreDestroy(this);
            } catch (RuntimeException ex) {
                if (BroadcasterListener.BroadcastListenerException.class.isAssignableFrom(ex.getClass())) {
                    logger.trace("onPreDestroy", ex);
                    return true;
                }
                logger.warn("onPreDestroy", ex);
            }
        }
        return false;
    }
}
