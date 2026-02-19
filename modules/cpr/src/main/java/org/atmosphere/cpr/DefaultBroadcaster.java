/*
 * Copyright 2008-2026 Async-IO.org
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

package org.atmosphere.cpr;

import org.atmosphere.cache.BroadcastMessage;
import org.atmosphere.cache.CacheMessage;
import org.atmosphere.cpr.BroadcastFilter.BroadcastAction;
import org.atmosphere.lifecycle.LifecycleHandler;
import org.atmosphere.pool.PoolableBroadcasterFactory;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
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
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static org.atmosphere.cpr.ApplicationConfig.BACKWARD_COMPATIBLE_WEBSOCKET_BEHAVIOR;
import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_CACHE_STRATEGY;
import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_SHAREABLE_LISTENERS;
import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_WAIT_TIME;
import static org.atmosphere.cpr.ApplicationConfig.CACHE_MESSAGE_ON_IO_FLUSH_EXCEPTION;
import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;
import static org.atmosphere.cpr.ApplicationConfig.OUT_OF_ORDER_BROADCAST;
import static org.atmosphere.cpr.ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID;
import static org.atmosphere.cpr.ApplicationConfig.WRITE_TIMEOUT;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.NEVER;
import static org.atmosphere.cpr.FrameworkConfig.INJECTED_ATMOSPHERE_RESOURCE;

/**
 * The default {@link Broadcaster} implementation.
 * <p/>
 * Broadcast messages to suspended responses using the caller's Thread.
 * This basic {@link Broadcaster} use an {@link java.util.concurrent.ExecutorService}
 * to broadcast messages, hence the broadcast operation is asynchronous. Make sure
 * you block on {@link #broadcast(Object)}.get()} if you need synchronous operations.
 *
 * @author Jeanfrancois Arcand
 */
public class DefaultBroadcaster implements Broadcaster {
    public static final int POLLING_DEFAULT = 100;
    public static final String CACHED = DefaultBroadcaster.class.getName() + ".messagesCached";

    private static final Logger logger = LoggerFactory.getLogger(DefaultBroadcaster.class);
    private static final String DESTROYED = "This Broadcaster has been destroyed and cannot be used {} by invoking {}";
    private static final List<AtmosphereResourceEventListener> EMPTY_LISTENERS = new ArrayList<>();

    protected final ConcurrentLinkedQueue<AtmosphereResource> resources =
            new ConcurrentLinkedQueue<>();
    protected BroadcasterConfig bc;
    protected final BlockingQueue<Deliver> messages = new LinkedBlockingQueue<>();
    protected Collection<BroadcasterListener> broadcasterListeners;

    protected final AtomicBoolean started = new AtomicBoolean(false);
    protected final AtomicBoolean initialized = new AtomicBoolean(false);
    protected final AtomicBoolean destroyed = new AtomicBoolean(false);

    protected SCOPE scope = SCOPE.APPLICATION;
    protected String name = DefaultBroadcaster.class.getSimpleName();
    protected final ConcurrentLinkedQueue<Deliver> delayedBroadcast = new ConcurrentLinkedQueue<>();
    protected final ConcurrentLinkedQueue<Deliver> broadcastOnResume = new ConcurrentLinkedQueue<>();
    protected final ConcurrentLinkedQueue<BroadcasterLifeCyclePolicyListener> lifeCycleListeners = new ConcurrentLinkedQueue<>();
    protected final ConcurrentHashMap<String, WriteQueue> writeQueues = new ConcurrentHashMap<>();
    protected final WriteQueue uniqueWriteQueue = new WriteQueue("-1");
    protected final AtomicInteger dispatchThread = new AtomicInteger();
    
    private final ReentrantLock awaitLock = new ReentrantLock();
    private final Condition awaitCondition = awaitLock.newCondition();
    private final ReentrantLock lock = new ReentrantLock();
    private final ReentrantLock resourcesLock = new ReentrantLock();
    private final ConcurrentHashMap<AtmosphereResource, ReentrantLock> resourceLocks = new ConcurrentHashMap<>();

    protected Future<?>[] notifierFuture;
    protected Future<?>[] asyncWriteFuture;

    private POLICY policy = POLICY.FIFO;
    private final AtomicLong maxSuspendResource = new AtomicLong(-1);
    private final AtomicBoolean requestScoped = new AtomicBoolean(false);
    private final AtomicBoolean recentActivity = new AtomicBoolean(false);
    private BroadcasterLifeCyclePolicy lifeCyclePolicy = new BroadcasterLifeCyclePolicy.Builder()
            .policy(NEVER).build();
    protected URI uri;
    protected AtmosphereConfig config;
    private final AtomicBoolean outOfOrderBroadcastSupported = new AtomicBoolean(false);
    protected int writeTimeoutInSecond = -1;
    protected int waitTime = POLLING_DEFAULT;
    private boolean backwardCompatible;
    private LifecycleHandler lifecycleHandler;
    private Future<?> currentLifecycleTask;
    private boolean cacheOnIOFlushException = true;
    protected boolean sharedListeners;
    protected boolean candidateForPoolable;
    protected final String usingTokenIdForAttribute = UUID.randomUUID().toString();

    public DefaultBroadcaster() {
    }

    public Broadcaster initialize(String name, URI uri, AtmosphereConfig config) {
        this.name = name;
        this.uri = uri;
        this.config = config;

        bc = createBroadcasterConfig(config);
        String s = config.getInitParameter(BROADCASTER_CACHE_STRATEGY);
        if (s != null) {
            logger.warn("{} is no longer supported. Use BroadcastInterceptor instead. By default the original message will be cached.", BROADCASTER_CACHE_STRATEGY);
        }
        s = config.getInitParameter(OUT_OF_ORDER_BROADCAST);
        if (s != null) {
            outOfOrderBroadcastSupported.set(Boolean.parseBoolean(s));
        }

        s = config.getInitParameter(BROADCASTER_WAIT_TIME);
        if (s != null) {
            waitTime = Integer.parseInt(s);
        }

        s = config.getInitParameter(WRITE_TIMEOUT);
        if (s != null) {
            writeTimeoutInSecond = Integer.parseInt(s);
        }
        if (outOfOrderBroadcastSupported.get()) {
            logger.trace("{} supports Out Of Order Broadcast: {}", name, outOfOrderBroadcastSupported.get());
        }
        initialized.set(true);
        backwardCompatible = Boolean.parseBoolean(config.getInitParameter(BACKWARD_COMPATIBLE_WEBSOCKET_BEHAVIOR));
        cacheOnIOFlushException = config.getInitParameter(CACHE_MESSAGE_ON_IO_FLUSH_EXCEPTION, true);
        sharedListeners = config.getInitParameter(BROADCASTER_SHAREABLE_LISTENERS, false);

        if (sharedListeners) {
            broadcasterListeners = config.getBroadcasterFactory().broadcasterListeners();
        } else {
            broadcasterListeners = new ConcurrentLinkedQueue<>();
        }

        candidateForPoolable = config.getBroadcasterFactory() instanceof PoolableBroadcasterFactory;

        return this;
    }

    public Broadcaster initialize(String name, AtmosphereConfig config) {
        return initialize(name, URI.create("http://localhost"), config);
    }

    /**
     * Create {@link BroadcasterConfig}.
     *
     * @param config the {@link AtmosphereConfig}
     * @return an instance of {@link BroadcasterConfig}
     */
    protected BroadcasterConfig createBroadcasterConfig(AtmosphereConfig config) {
        return new BroadcasterConfig(config.framework().broadcasterFilters, config, getID()).init();
    }

    @Override
    public void destroy() {
        lock.lock();
        try {
            logger.trace("Broadcaster {} will be pooled: {}", getID(), candidateForPoolable);
            if (!candidateForPoolable) {
                if (notifyOnPreDestroy()) return;

                logger.trace("Broadcaster {} is being destroyed and cannot be re-used. Policy was {}", getID(), policy);
                logger.trace("Broadcaster {} is being destroyed and cannot be re-used. Resources are {}", getID(), resources);

                if (destroyed.getAndSet(true)) return;

                started.set(false);

                releaseExternalResources();
                killReactiveThreads();

                if (bc != null) {
                    bc.destroy();
                }
                lifeCycleListeners.clear();
                delayedBroadcast.clear();
                if (!sharedListeners) {
                    broadcasterListeners.clear();
                }
            }

            resources.clear();
            broadcastOnResume.clear();
            messages.clear();
            writeQueues.clear();
            resourceLocks.clear();

            if (config.getBroadcasterFactory() != null) {
                config.getBroadcasterFactory().remove(this, this.getID());
            }
        } catch (Throwable t) {
            logger.error("Unexpected exception during Broadcaster destroy {}", getID(), t);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Collection<AtmosphereResource> getAtmosphereResources() {
        return Collections.unmodifiableCollection(resources);
    }

    @Override
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
        resourcesLock.lock();
        try {
            try {
                // Next, we need to create a new broadcaster per resource.
                for (AtmosphereResource resource : resources) {
                    Broadcaster b = config.getBroadcasterFactory()
                            .get(getClass(), getClass().getSimpleName() + "/" + config.uuidProvider().generateUuid());

                    /**
                     * REQUEST_SCOPE means one BroadcasterCache per Broadcaster,
                     */
                    if (this instanceof DefaultBroadcaster) {
                        BroadcasterCache cache = config.framework().newClassInstance(BroadcasterCache.class, bc.getBroadcasterCache().getClass());
                        cache.configure(config);
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
        } finally {
            resourcesLock.unlock();
        }
    }

    @Override
    public SCOPE getScope() {
        return scope;
    }

    @Override
    public void setID(String id) {
        lock.lock();
        try {
            if (id == null) {
                id = getClass().getSimpleName() + "/" + config.uuidProvider().generateUuid();
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
        } finally {
            lock.unlock();
        }
    }

    /**
     * Rename this Broadcaster without invoking it's associated {@link org.atmosphere.cpr.BroadcasterFactory}. This
     * method must be carefully used as it could easily create memory leak as the Broadcaster won't be removed
     * from its {@link org.atmosphere.cpr.BroadcasterFactory}.
     *
     * @param id the new name
     * @return this;
     */
    public Broadcaster rename(String id) {
        this.name = id;
        return this;
    }

    @Override
    public String getID() {
        return name;
    }

    @Override
    public void resumeAll() {
        resourcesLock.lock();
        try {
            for (AtmosphereResource r : resources) {
                try {
                    r.resume();
                } catch (Throwable t) {
                    logger.trace("resumeAll", t);
                } finally {
                    removeAtmosphereResource(r);
                }
            }
        } finally {
            resourcesLock.unlock();
        }
    }

    @Override
    public void releaseExternalResources() {
    }

    @Override
    public void setBroadcasterLifeCyclePolicy(final BroadcasterLifeCyclePolicy lifeCyclePolicy) {
        this.lifeCyclePolicy = lifeCyclePolicy;
        if (lifecycleHandler != null) lifecycleHandler.on(this);
    }

    @Override
    public BroadcasterLifeCyclePolicy getBroadcasterLifeCyclePolicy() {
        return lifeCyclePolicy;
    }

    @Override
    public void addBroadcasterLifeCyclePolicyListener(BroadcasterLifeCyclePolicyListener b) {
        lifeCycleListeners.add(b);
    }

    @Override
    public void removeBroadcasterLifeCyclePolicyListener(BroadcasterLifeCyclePolicyListener b) {
        lifeCycleListeners.remove(b);
    }

    @Override
    public boolean isDestroyed() {
        return destroyed.get();
    }

    @Override
    public Future<Object> awaitAndBroadcast(Object t, long time, TimeUnit timeUnit) {
        if (resources.isEmpty()) {
            awaitLock.lock();
            try {
                logger.trace("Awaiting for AtmosphereResource for {} {}", time, timeUnit);
                awaitCondition.await(translateTimeUnit(time, timeUnit), TimeUnit.MILLISECONDS);
            } catch (Throwable e) {
                logger.warn("awaitAndBroadcast", e);
                return null;
            } finally {
                awaitLock.unlock();
            }
        }
        return broadcast(t);
    }

    @Override
    public Broadcaster addBroadcasterListener(BroadcasterListener b) {
        if (!sharedListeners && !broadcasterListeners.contains(b)) {
            broadcasterListeners.add(b);
        }
        return this;
    }

    @Override
    public Broadcaster removeBroadcasterListener(BroadcasterListener b) {
        if (!sharedListeners) broadcasterListeners.remove(b);
        return this;
    }

    protected Runnable getBroadcastHandler() {
        return () -> {
            while (!isDestroyed()) {
                Deliver msg = null;
                try {
                    msg = messages.poll(waitTime, TimeUnit.MILLISECONDS);
                    if (msg == null) {
                        dispatchThread.decrementAndGet();
                        return;
                    }
                } catch (InterruptedException ex) {
                    logger.trace("{} got interrupted for Broadcaster {}", Thread.currentThread().getName(), getID());
                    logger.trace("", ex);
                    dispatchThread.decrementAndGet();
                    return;
                } finally {
                    if (outOfOrderBroadcastSupported.get()) {
                        bc.getExecutorService().submit(getBroadcastHandler());
                    }
                }

                try {
                    logger.trace("{} is about to broadcast {}", getID(), msg);
                    push(msg);
                } catch (Throwable ex) {
                    if (!started.get() || destroyed.get()) {
                        logger.trace("Failed to submit broadcast handler runnable on shutdown for Broadcaster {}", getID(), ex);
                        dispatchThread.decrementAndGet();
                        return;
                    } else {
                        logger.warn("This message {} will be lost", msg);
                        logger.warn("Failed to submit broadcast handler runnable for Broadcaster {}", getID(), ex);
                    }
                } finally {
                    if (outOfOrderBroadcastSupported.get()) {
                        dispatchThread.decrementAndGet();
                        return;
                    }
                }
            }
        };
    }

    protected Runnable getAsyncWriteHandler(final WriteQueue writeQueue) {
        return () -> {
            while (!isDestroyed()) {
                AsyncWriteToken token;
                try {
                    token = writeQueue.queue.poll(waitTime, TimeUnit.MILLISECONDS);
                    if (token == null && !outOfOrderBroadcastSupported.get()) {
                        writeQueue.lock.lock();
                        try {
                            if (writeQueue.queue.isEmpty()) {
                                writeQueue.monitored.set(false);
                                writeQueues.remove(writeQueue.uuid);
                                return;
                            }
                        } finally {
                            writeQueue.lock.unlock();
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
                        bc.getAsyncWriteService().submit(getAsyncWriteHandler(writeQueue));
                    }
                }

                // Shield us from https://github.com/Atmosphere/atmosphere/issues/1187
                if (token != null) {
                    ReentrantLock rLock = resourceLocks.computeIfAbsent(token.resource, k -> new ReentrantLock());
                    rLock.lock();
                    try {
                        try {
                            logger.trace("About to write to {}", token.resource);
                            executeAsyncWrite(token);
                        } catch (Throwable ex) {
                            if (!started.get() || destroyed.get()) {
                                logger.trace("Failed to execute a write operation. Broadcaster is destroyed or not yet started for Broadcaster {}", getID(), ex);
                                return;
                            } else {
                                try {
                                    logger.warn("This message {} will be lost for AtmosphereResource {}, adding it to the BroadcasterCache",
                                            token.originalMessage, token.resource != null ? token.resource.uuid() : "null");
                                    cacheLostMessage(token.resource, token, true);
                                } finally {
                                    removeAtmosphereResource(token.resource, false);
                                    logger.warn("Failed to execute a write operation for Broadcaster {}", getID(), ex);
                                }
                            }
                        } finally {
                            if (!bc.getAsyncWriteService().isShutdown() && outOfOrderBroadcastSupported.get()) {
                                return;
                            }
                        }
                    } finally {
                        rLock.unlock();
                    }
                }
            }
        };
    }

    protected void start() {
        if (!initialized.get()) {
            logger.warn("Broadcaster {} not initialized", getID());
        }

        if (!started.getAndSet(true)) {
            bc.getBroadcasterCache().start();

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
                asyncWriteFuture[i] = bc.getAsyncWriteService().submit(getAsyncWriteHandler(uniqueWriteQueue));
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

    protected void push(Deliver deliver) {
        if (destroyed.get()) {
            return;
        }

        deliverPush(deliver, true);
    }

    protected void deliverPush(Deliver deliver, boolean rec) {
        recentActivity.set(true);

        Object prevMessage = deliver.message;
        if (rec && !delayedBroadcast.isEmpty()) {
            var i = delayedBroadcast.iterator();
            var b = new StringBuilder();
            while (i.hasNext()) {
                Deliver e = i.next();
                e.future.cancel(true);
                try {
                    // Append so we do a single flush
                    if (e.message instanceof String
                            && deliver.message instanceof String) {
                        b.append(e.message);
                    } else {
                        deliverPush(e, false);
                    }
                } finally {
                    i.remove();
                }
            }

            if (b.length() > 0) {
                deliver.message = b.append(deliver.message).toString();
            }
        }

        Object finalMsg = callable(deliver.message);
        if (finalMsg == null) {
            logger.error("Callable exception. Please catch all exceptions from your callable. Message {} will be lost and all AtmosphereResource " +
                    "associated with this Broadcaster resumed.", deliver.message);
            entryDone(deliver.future);
            switch (deliver.type) {
                case ALL -> {
                    resourcesLock.lock();
                    try {
                        for (AtmosphereResource r : resources) {
                            if (Utils.resumableTransport(r.transport()))
                                try {
                                    r.resume();
                                } catch (Throwable t) {
                                    logger.trace("resumeAll", t);
                                }
                        }
                    } finally {
                        resourcesLock.unlock();
                    }
                }
                case RESOURCE -> deliver.resource.resume();
                case SET -> {
                    for (AtmosphereResource r : deliver.resources) {
                        r.resume();
                    }
                }
            }
            return;
        }

        Object prevM = deliver.originalMessage;
        deliver.originalMessage = (deliver.originalMessage != deliver.message ? callable(deliver.originalMessage) : finalMsg);

        if (deliver.originalMessage == null) {
            logger.trace("Broadcasted message was null {}", prevM);
            entryDone(deliver.future);
            return;
        }

        deliver.message = finalMsg;

        Map<String, CacheMessage> cacheForSet = deliver.type == Deliver.TYPE.SET ? new HashMap<>() : null;
        // We cache first, and if the broadcast succeed, we will remove it.
        switch (deliver.type) {
            case ALL ->
                deliver.cache = bc.getBroadcasterCache().addToCache(getID(), BroadcasterCache.NULL, new BroadcastMessage(deliver.originalMessage));
            case RESOURCE ->
                deliver.cache = bc.getBroadcasterCache().addToCache(getID(), deliver.resource.uuid(), new BroadcastMessage(deliver.originalMessage));
            case SET -> {
                for (AtmosphereResource r : deliver.resources) {
                    cacheForSet.put(r.uuid(), bc.getBroadcasterCache().addToCache(getID(), r.uuid(), new BroadcastMessage(deliver.originalMessage)));
                }
            }
        }

        notifyOnMessage(deliver);
        if (resources.isEmpty()) {
            logger.trace("No resource available for {} and message {}", getID(), finalMsg);
            entryDone(deliver.future);
            if (cacheForSet != null) {
                cacheForSet.clear();
            }
            return;
        }

        try {
            if (logger.isTraceEnabled()) {
                for (AtmosphereResource r : resources) {
                    logger.trace("AtmosphereResource {} available for {}", r.uuid(), deliver.message);
                }
            }

            boolean hasFilters = bc.hasPerRequestFilters();
            Object beforeProcessingMessage = deliver.message;
            switch (deliver.type) {
                case ALL -> {
                    var count = new AtomicInteger(resources.size());

                    for (AtmosphereResource r : resources) {
                        deliver.message = beforeProcessingMessage;
                        boolean deliverMessage = perRequestFilter(r, deliver);

                        if (endBroadcast(deliver, r, deliver.cache, deliverMessage)) continue;

                        if (deliver.writeLocally) {
                            queueWriteIO(r, hasFilters ? new Deliver(r, deliver) : deliver, count);
                        }
                    }
                }
                case RESOURCE -> {
                    boolean deliverMessage = perRequestFilter(deliver.resource, deliver);

                    if (endBroadcast(deliver, deliver.resource, deliver.cache, deliverMessage)) return;

                    if (deliver.writeLocally) {
                        queueWriteIO(deliver.resource, deliver, new AtomicInteger(1));
                    }
                }
                case SET -> {
                    var count = new AtomicInteger(deliver.resources.size());

                    for (AtmosphereResource r : deliver.resources) {
                        deliver.message = beforeProcessingMessage;
                        boolean deliverMessage = perRequestFilter(r, deliver);

                        CacheMessage cacheMsg = cacheForSet.remove(r.uuid());

                        if (endBroadcast(deliver, r, cacheMsg, deliverMessage)) continue;

                        if (deliver.writeLocally) {
                            queueWriteIO(r, new Deliver(r, deliver, cacheMsg), count);
                        }
                    }
                }
            }

            deliver.message = prevMessage;
        } catch (InterruptedException ex) {
            logger.debug(ex.getMessage(), ex);
            if (cacheForSet != null) {
                cacheForSet.clear();
            }
        }
    }

    protected boolean endBroadcast(Deliver deliver, AtmosphereResource r, CacheMessage cacheMsg, boolean deliverMessage) {
        if (!deliverMessage || deliver.message == null) {
            logger.debug("Skipping broadcast delivery {} for resource {} ", deliver.message, deliver.resource != null ? deliver.resource.uuid() : "null");
            bc.getBroadcasterCache().clearCache(getID(), r.uuid(), cacheMsg);
            entryDone(deliver.future);

            return true;
        }
        return false;
    }

    protected void queueWriteIO(AtmosphereResource r, Deliver deliver, AtomicInteger count) throws InterruptedException {
        if (deliver.async) {
            // The onStateChange/onRequest may change the isResumed value, hence we need to make sure only one thread flip
            // the switch to garantee the Entry will be cached in the order it was broadcasted.
            // Without synchronizing we may end up with a out of order BroadcasterCache queue.
            if (!bc.getBroadcasterCache().getClass().equals(BroadcasterCache.DEFAULT.getClass())) {
                if (r.isResumed() || r.isCancelled()) {
                    logger.trace("AtmosphereResource {} has been resumed or cancelled, unable to Broadcast message {}", r.uuid(), deliver.message);

                    /*
                     * https://github.com/Atmosphere/atmosphere/issues/1886
                     * Before caching the message, double check if the client has reconnected, and if true, send the
                     * cached message.
                     */
                    AtmosphereResource r2 = config.resourcesFactory().findResource(r.uuid()).orElse(null);
                    logger.trace("Found an AtmosphereResource {} in state {}", r2, r.isSuspended());
                    if (r2 != null && r2.isSuspended() && r.hashCode() != r2.hashCode()) {
                        // Prevent other Broadcast to happens
                        removeAtmosphereResource(r2);
                        checkCachedAndPush(r2, r2.getAtmosphereResourceEvent());
                    }
                    return;
                }
            }

            var w = new AsyncWriteToken(r, deliver.message, deliver.future, deliver.originalMessage, deliver.cache, count);
            if (!outOfOrderBroadcastSupported.get()) {
                WriteQueue writeQueue = writeQueues.get(r.uuid());
                if (writeQueue == null) {
                    writeQueue = new WriteQueue(r.uuid());
                    writeQueues.put(r.uuid(), writeQueue);
                }

                writeQueue.queue.put(w);
                writeQueue.lock.lock();
                try {
                    if (!writeQueue.monitored.getAndSet(true)) {
                        logger.trace("Broadcaster {} is about to queueWriteIO for AtmosphereResource {}", name, r.uuid());
                        bc.getAsyncWriteService().submit(getAsyncWriteHandler(writeQueue));
                    }
                } finally {
                    writeQueue.lock.unlock();
                }
            } else {
                uniqueWriteQueue.queue.offer(w);
            }
        } else {
            executeBlockingWrite(r, deliver, count);
        }
    }

    protected void executeBlockingWrite(AtmosphereResource r, Deliver deliver, AtomicInteger count) throws InterruptedException {
        // We deliver using the calling thread.
        ReentrantLock rLock = resourceLocks.computeIfAbsent(r, k -> new ReentrantLock());
        rLock.lock();
        try {
            executeAsyncWrite(new AsyncWriteToken(r, deliver.message, deliver.future, deliver.originalMessage, deliver.cache, count));
        } finally {
            rLock.unlock();
        }
    }

    public final static class WriteQueue {
        final BlockingQueue<AsyncWriteToken> queue = new LinkedBlockingQueue<>();
        final AtomicBoolean monitored = new AtomicBoolean();
        final ReentrantLock lock = new ReentrantLock();
        final String uuid;

        private WriteQueue(String uuid) {
            this.uuid = uuid;
        }

        public List<String> asString() {
            var l = new ArrayList<String>();
            for (AsyncWriteToken w : queue) {
                l.add(w.toString());
            }
            return l;
        }
    }

    protected boolean perRequestFilter(AtmosphereResource r, Deliver msg) {
        // A broadcaster#broadcast(msg,Set) may contains null value.
        if (r == null) {
            logger.trace("Null AtmosphereResource passed inside a Set");
            return false;
        }

        if (bc.hasPerRequestFilters()) {
            BroadcastAction a = bc.filter(r, msg.message, msg.originalMessage);
            if (a.action() == BroadcastAction.ACTION.ABORT) {
                return false;
            }
            msg.message = a.message();
        }
        return true;
    }

    private Object callable(Object msg) {
        if (msg instanceof Callable<?> c) {
            try {
                return c.call();
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

        if (token.resource == null) throw new NullPointerException();

        final AtmosphereResourceEventImpl event = (AtmosphereResourceEventImpl) token.resource.getAtmosphereResourceEvent();
        final AtmosphereResourceImpl r = (AtmosphereResourceImpl) token.resource;
        final boolean willBeResumed = Utils.resumableTransport(r.transport());
        List<AtmosphereResourceEventListener> listeners = willBeResumed ? new ArrayList<>() : EMPTY_LISTENERS;
        final AtmosphereRequest request = r.getRequest(false);
        try {

            event.setMessage(token.msg);

            // Make sure we cache the message in case the AtmosphereResource has been cancelled, resumed or the client disconnected.
            if (!isAtmosphereResourceValid(r)) {
                logger.trace("AtmosphereResource {} state is invalid for Broadcaster {}. Message will be cached", r.uuid(), name);
                removeAtmosphereResource(r, false);
                return;
            }

            bc.getBroadcasterCache().clearCache(getID(), r.uuid(), token.cache);
            try {
                request.setAttribute(getID(), token.future);
                request.setAttribute(MAX_INACTIVE, System.currentTimeMillis());
                request.setAttribute(usingTokenIdForAttribute, token);

                if (willBeResumed && !r.atmosphereResourceEventListener().isEmpty()) {
                    listeners.addAll(r.atmosphereResourceEventListener());
                }
                prepareInvokeOnStateChange(r, event);
            } catch (Throwable t) {
                logger.debug("Invalid AtmosphereResource state {}. The connection has been remotely" +
                        " closed and message {} will be added to the configured BroadcasterCache for later retrieval", r.uuid(), event.getMessage());
                logger.trace("If you are using Tomcat 7.0.22 and lower, you're most probably hitting http://is.gd/NqicFT");
                logger.trace("ApplicationConfig.CACHE_MESSAGE_ON_IO_FLUSH_EXCEPTION {}", cacheOnIOFlushException, t);

                lostCandidate = cacheOnIOFlushException ? cacheOnIOFlushException : cacheMessageOnIOException(t);
                // The Request/Response associated with the AtmosphereResource has already been written and commited
                removeAtmosphereResource(r, false);
                r.removeFromAllBroadcasters();
                event.setCancelled(true);
                event.setThrowable(t);
                r.setIsInScope(false);
                return;
            }
        } finally {
            if (notifyListeners) {
                // Long Polling listener will be cleared when the resume() is called.
                if (willBeResumed) {
                    event.setMessage(token.msg);
                    for (AtmosphereResourceEventListener e : listeners) {
                        e.onBroadcast(event);
                    }
                    // Listener wil be called later
                } else if (!event.isResumedOnTimeout()) {
                    r.notifyListeners();
                }
            }

            if (token.lastBroadcasted()) {
                notifyBroadcastListener();
            }

            if (token.future != null) token.future.done();

            if (lostCandidate) {
                cacheLostMessage(r, token, true);
            }

            try {
                request.removeAttribute(getID());
                request.removeAttribute(usingTokenIdForAttribute);
            } catch (NullPointerException ex) {
                logger.trace("NPE after the message has been written for {}", r.uuid());
            }
            token.destroy();
        }
    }

    protected boolean cacheMessageOnIOException(Throwable cause) {
        for (StackTraceElement element : cause.getStackTrace()) {
            if (element.getMethodName().equals("flush") || element.getMethodName().equals("flushBuffer")) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    protected boolean checkCachedAndPush(final AtmosphereResource r, final AtmosphereResourceEvent e) {
        boolean cache = retrieveTrackedBroadcast(r, e);

        if (!cache) return false;

        if (!((List<?>) e.getMessage()).isEmpty()) {
            logger.debug("Sending cached message {} to {}", e.getMessage(), r.uuid());

            List<Object> cacheMessages = (List<Object>) e.getMessage();
            var f = new BroadcasterFuture<>(e.getMessage(), 1);
            var filteredMessage = new LinkedList<Object>();
            LinkedList<Object> filteredMessageClone = null;
            Deliver deliver;
            Object newMessage;
            for (Object o : cacheMessages) {
                newMessage = filter(o);
                if (newMessage == null) {
                    continue;
                }

                deliver = new Deliver(newMessage, r, f, o);
                // Can be aborted by a Filter
                if (!perRequestFilter(r, deliver)) {
                    continue;
                }

                if (deliver.message != null) {
                    filteredMessage.addLast(deliver.message);  // SequencedCollection method
                }
            }

            if (filteredMessage.isEmpty()) {
                return false;
            }
            e.setMessage(filteredMessage);

            final boolean willBeResumed = Utils.resumableTransport(r.transport());

            if (willBeResumed) {
                filteredMessageClone = new LinkedList<>(filteredMessage);  // Copy constructor
            }

            List<AtmosphereResourceEventListener> listeners = willBeResumed ? new ArrayList<>() : EMPTY_LISTENERS;
            AtmosphereResourceImpl rImpl = (AtmosphereResourceImpl) r;
            if (willBeResumed && !rImpl.atmosphereResourceEventListener().isEmpty()) {
                listeners.addAll(rImpl.atmosphereResourceEventListener());
            }

            // Must make sure execute only one thread
            ReentrantLock rLock = resourceLocks.computeIfAbsent(rImpl, k -> new ReentrantLock());
            rLock.lock();
            try {
                try {
                    rImpl.getRequest().setAttribute(CACHED, "true");
                    prepareInvokeOnStateChange(r, e);
                } catch (Throwable t) {
                    // An exception occurred
                    logger.error("Unable to write cached message {} for {}", e.getMessage(), r.uuid());
                    logger.error("", t);
                    for (Object o : cacheMessages) {
                        bc.getBroadcasterCache().addToCache(getID(), r.uuid(), new BroadcastMessage(o));
                    }
                    return true;
                }

                // If long-polling or JSONP is used we need to set the messages for the event again, because onResume() have cleared them
                if (willBeResumed) {
                    e.setMessage(filteredMessageClone);
                }

                for (AtmosphereResourceEventListener l : willBeResumed ? listeners : rImpl.atmosphereResourceEventListener()) {
                    l.onBroadcast(e);
                }

                switch (r.transport()) {
                    case UNDEFINED, JSONP, AJAX, LONG_POLLING -> {
                        return true;
                    }
                    case SSE -> {}
                    default -> {
                        try {
                            r.getResponse().flushBuffer();
                        } catch (IOException ioe) {
                            logger.trace("", ioe);
                            ((AtmosphereResourceImpl) r)._destroy();
                        }
                    }
                }
            } finally {
                rLock.unlock();
            }
        }
        return false;
    }

    protected boolean retrieveTrackedBroadcast(final AtmosphereResource r, final AtmosphereResourceEvent e) {
        logger.trace("Checking cached message for {}", r.uuid());
        List<?> missedMsg = bc.getBroadcasterCache().retrieveFromCache(getID(), r.uuid());
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
            if (!(t instanceof InterruptedException)) {
                onException(t, r);
            }
        }
    }

    protected void prepareInvokeOnStateChange(final AtmosphereResource r, final AtmosphereResourceEvent e) {
        if (writeTimeoutInSecond != -1) {
            logger.trace("Registering Write timeout {} for {}", writeTimeoutInSecond, r.uuid());
            var w = new WriteOperation(r, e, Thread.currentThread());
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
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean executed = new AtomicBoolean();
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
                ((AtmosphereResourceImpl) r).cancel();
            }
            return null;
        }

        public void interrupt() {
        }
    }

    public void onException(Throwable t, final AtmosphereResource ar) {
        onException(t, ar, true);
    }

    public void onException(Throwable t, final AtmosphereResource ar, boolean notifyAndCache) {
        final AtmosphereResourceImpl r = AtmosphereResourceImpl.class.cast(ar);

        logger.trace("I/O Exception (or related) during execution of the write operation for " +
                        "AtmosphereResource {} and Broadcaster {}. Message will be cached {}",
                ar.uuid(), getID(), String.valueOf(notifyAndCache), t);

        // Remove to prevent other broadcast to re-use it.
        removeAtmosphereResource(r);

        if (notifyAndCache) {
            final AtmosphereResourceEventImpl event = r.getAtmosphereResourceEvent();
            event.setThrowable(t);

            r.notifyListeners(event);
            r.removeEventListeners();
        }

        if (notifyAndCache) {
            cacheLostMessage(r, (AsyncWriteToken) r.getRequest(false).getAttribute(usingTokenIdForAttribute), notifyAndCache);
        }

        /*
         * Make sure we resume the connection on every IOException.
         */
        if (bc != null && bc.getAsyncWriteService() != null) {
            bc.getAsyncWriteService().execute(() -> {
                try {
                    logger.trace("Forcing connection close {}", ar.uuid());
                    r.resume();
                    r.close();
                } catch (Throwable t1) {
                    logger.trace("Was unable to resume a corrupted AtmosphereResource {}", r);
                    logger.trace("Cause", t1);
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
    public void cacheLostMessage(AtmosphereResource r, boolean force) {
        AtmosphereRequest request = ((AtmosphereResourceImpl) r).getRequest(false);
        try {
            cacheLostMessage(r, (AsyncWriteToken) request.getAttribute(usingTokenIdForAttribute), force);
        } finally {
            request.removeAttribute(usingTokenIdForAttribute);
        }
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
                bc.getBroadcasterCache().addToCache(getID(), r != null ? r.uuid() : BroadcasterCache.NULL,
                        new BroadcastMessage(String.valueOf(token.future.hashCode()), token.originalMessage));
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

        int callee = resources.isEmpty() ? 1 : resources.size();

        var f = new BroadcasterFuture<>(newMsg, callee);
        dispatchMessages(new Deliver(newMsg, f, msg));
        return f;
    }

    protected BroadcasterFuture<Object> futureDone(Object msg) {
        notifyBroadcastListener();
        return (new BroadcasterFuture<>(msg)).done();
    }

    protected void dispatchMessages(Deliver e) {
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

    @Override
    public Future<Object> broadcast(Object msg, AtmosphereResource r) {

        if (destroyed.get()) {
            logger.debug(DESTROYED, getID(), "broadcast(T msg, AtmosphereResource r");
            return futureDone(msg);
        }

        start();
        Object newMsg = filter(msg);
        if (newMsg == null) return futureDone(msg);

        var f = new BroadcasterFuture<>(newMsg, 1);
        dispatchMessages(new Deliver(newMsg, r, f, msg));
        return f;
    }

    @Override
    public Future<Object> broadcastOnResume(Object msg) {

        if (destroyed.get()) {
            logger.debug(DESTROYED, getID(), "broadcastOnResume(T msg)");
            return futureDone(msg);
        }

        start();
        Object newMsg = filter(msg);
        if (newMsg == null) return futureDone(msg);

        var f = new BroadcasterFuture<>(newMsg, resources.size());
        broadcastOnResume.offer(new Deliver(newMsg, f, msg));
        return f;
    }

    protected void broadcastOnResume(AtmosphereResource r) {
        for (Deliver e : broadcastOnResume) {
            e.async = false;
            push(new Deliver(r, e));
        }

        if (resources.isEmpty()) {
            broadcastOnResume.clear();
        }
    }

    @Override
    public Future<Object> broadcast(Object msg, Set<AtmosphereResource> subset) {

        if (destroyed.get()) {
            logger.debug(DESTROYED, getID(), "broadcast(T msg, Set<AtmosphereResource> subset)");
            return futureDone(msg);
        }

        start();
        Object newMsg = filter(msg);
        if (newMsg == null) return futureDone(msg);

        var f = new BroadcasterFuture<>(null, newMsg, subset.size());
        dispatchMessages(new Deliver(newMsg, subset, f, msg));
        return f;
    }

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
                    AtmosphereResource resource = resources.poll();
                    if (resource != null) {
                        try {
                            logger.warn("Too many resource. Forcing resume of {} ", resource.uuid());
                            resource.resume();
                        } catch (Throwable t) {
                            logger.warn("failed to resume resource {} ", resource, t);
                        }
                    }
                } else if (policy == POLICY.REJECT) {
                    throw new RejectedExecutionException(String.format("Maximum suspended AtmosphereResources %s", maxSuspendResource));
                }
            }

            if (!r.isSuspended()) {
                logger.warn("AtmosphereResource {} is not suspended. If cached messages exists, this may cause unexpected situation. Suspend first", r.uuid());
            }

            if (!backwardCompatible && resources.contains(r)) {
                boolean duplicate = r.transport() != AtmosphereResource.TRANSPORT.WEBSOCKET
                        || ((AtmosphereResourceImpl) r).getRequest(false).getAttribute(INJECTED_ATMOSPHERE_RESOURCE) != null;

                if (duplicate) {
                    AtmosphereResourceImpl dup = (AtmosphereResourceImpl) config.resourcesFactory().findResource(r.uuid()).orElse(null);
                    if (dup != null && dup != r ) {
                        if ( ! dup.isPendingClose() ) {
                            logger.debug("Duplicate resource {}. Could be caused by a dead connection not detected by your server. " +
                                    "Replacing the old one with the fresh one {}", r.uuid(), r.transport());
                        } else {
                            logger.debug("Not yet closed resource still active {}", r.uuid());
                        }
                        dup.dirtyClose();
                    } else {
                        logger.debug("Duplicate resource {}", r.uuid());
                        return this;
                    }
                } else {
                    logger.debug("Duplicate resource {}", r.uuid());
                    return this;
                }
            }

            // Only synchronize if we have a valid BroadcasterCache
            if (!bc.getBroadcasterCache().getClass().equals(BroadcasterCache.DEFAULT.getClass())) {
                // In case we are adding messages to the cache, we need to make sure the operation is done before.
                resourcesLock.lock();
                try {
                    cacheAndSuspend(r);
                } finally {
                    resourcesLock.unlock();
                }
            } else {
                cacheAndSuspend(r);
            }
        } finally {
            // OK reset
            if (!resources.isEmpty()) {
                awaitLock.lock();
                try {
                    awaitCondition.signalAll();
                } finally {
                    awaitLock.unlock();
                }
            }
        }
        return this;
    }

    /**
     * Look in the cache to see if there are messages available, and take the appropriate actions.
     *
     * @param r AtmosphereResource
     */
    protected void cacheAndSuspend(AtmosphereResource r) {
        // In case the connection is closed, for whatever reason
        if (!isAtmosphereResourceValid(r)) {
            logger.debug("Unable to add AtmosphereResource {}", r.uuid());
            return;
        }

        boolean wasResumed = checkCachedAndPush(r, r.getAtmosphereResourceEvent());
        if (!wasResumed && isAtmosphereResourceValid(r)) {
            logger.trace("Associating AtmosphereResource {} with Broadcaster {}", r.uuid(), getID());

            String parentUUID = r.transport().equals(AtmosphereResource.TRANSPORT.WEBSOCKET) ?
                    (String) ((AtmosphereResourceImpl) r).getRequest(false).getAttribute(SUSPENDED_ATMOSPHERE_RESOURCE_UUID) :
                    null;
            if (!backwardCompatible && parentUUID != null) {
                AtmosphereResource p = config.resourcesFactory().findResource(parentUUID).orElse(null);
                if (p != null && !resources.contains(p)) {
                    notifyAndAdd(p);
                } else if (p == null) {
                    notifyAndAdd(r);
                } else {
                    logger.trace("AtmosphereResource {} was already mapped to {}", r.uuid(), parentUUID);
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
        r.addBroadcaster(this);
        notifyOnAddAtmosphereResourceListener(r);
    }

    private boolean isAtmosphereResourceValid(AtmosphereResource r) {
        return !r.isResumed()
                && !r.isCancelled()
                && ((AtmosphereResourceImpl) r).isInScope();
    }

    protected void entryDone(final BroadcasterFuture<?> f) {
        notifyBroadcastListener();
        if (f != null) f.done();
    }

    private void forEachListener(Consumer<BroadcasterListener> action) {
        for (BroadcasterListener b : broadcasterListeners) {
            try {
                action.accept(b);
            } catch (Exception ex) {
                logger.warn("", ex);
            }
        }
    }

    protected void notifyBroadcastListener() {
        forEachListener(b -> b.onComplete(this));
    }

    protected void notifyOnAddAtmosphereResourceListener(AtmosphereResource r) {
        forEachListener(b -> b.onAddAtmosphereResource(this, r));
    }

    protected void notifyOnRemoveAtmosphereResourceListener(AtmosphereResource r) {
        forEachListener(b -> b.onRemoveAtmosphereResource(this, r));
    }

    protected void notifyOnMessage(Deliver deliver) {
        forEachListener(b -> b.onMessage(this, deliver));
    }

    @Override
    public Broadcaster removeAtmosphereResource(AtmosphereResource r) {
        return removeAtmosphereResource(r, true);
    }

    protected Broadcaster removeAtmosphereResource(AtmosphereResource r, boolean executeDone) {
        if (destroyed.get()) {
            logger.debug(DESTROYED, getID(), "removeAtmosphereResource(AtmosphereResource r)");
            return this;
        }

        boolean removed = resources.remove(r);
        if (removed) {
            if (r.isSuspended()) {
                logger.trace("Excluded from {} : {}", getID(), r.uuid());
                bc.getBroadcasterCache().excludeFromCache(getID(), r);
            }
            notifyOnRemoveAtmosphereResourceListener(r);
        } else {
            logger.trace("Unable to remove {} from {}", r.uuid(), getID());
        }
        r.removeBroadcaster(this);

        if (!removed) return this;

        logger.trace("Removing AtmosphereResource {} for Broadcaster {}", r.uuid(), name);
        writeQueues.remove(r.uuid());
        resourceLocks.remove(r);

        // Here we need to make sure we aren't in the process of broadcasting and unlock the Future.
        if (executeDone) {
            AtmosphereResourceImpl aImpl = (AtmosphereResourceImpl) r;
            BroadcasterFuture<?> f = (BroadcasterFuture<?>) aImpl.getRequest(false).getAttribute(getID());
            if (f != null && !f.isDone() && !f.isCancelled()) {
                aImpl.getRequest(false).removeAttribute(getID());
                entryDone(f);
            }
        }

        return this;
    }

    @Override
    public void setBroadcasterConfig(BroadcasterConfig bc) {
        this.bc = bc;
    }

    @Override
    public BroadcasterConfig getBroadcasterConfig() {
        return bc;
    }

    @Override
    public Future<Object> delayBroadcast(Object o) {
        return delayBroadcast(o, 0, null);
    }

    @Override
    public Future<Object> delayBroadcast(final Object o, long delay, TimeUnit t) {

        if (destroyed.get()) {
            logger.debug(DESTROYED, getID(), "delayBroadcast(final T o, long delay, TimeUnit t)");
            return null;
        }

        start();
        final Object msg = filter(o);
        if (msg == null) return null;

        final var future = new BroadcasterFuture<>(msg);
        final var e = new Deliver(msg, future, o);
        Future<Object> f;
        if (delay > 0) {
            f = bc.getScheduledExecutorService().schedule(() -> {
                delayedBroadcast.remove(e);
                if (o instanceof Callable<?> c) {
                    try {
                        Object r = c.call();
                        final Object msg1 = filter(r);
                        if (msg1 != null) {
                            var deliver = new Deliver(msg1, future, r);
                            push(deliver);
                        }
                        return msg1;
                    } catch (Exception e1) {
                        logger.error("delayBroadcast", e);
                    }
                }

                final Object msg1 = filter(o);
                final var e12 = new Deliver(msg1, future, o);
                push(e12);
                return msg1;
            }, delay, t);

            e.future = new BroadcasterFuture<>(f, msg);
        }
        delayedBroadcast.offer(e);
        return future;
    }

    @Override
    public Future<Object> scheduleFixedBroadcast(final Object o, long period, TimeUnit t) {
        return scheduleFixedBroadcast(o, 0, period, t);
    }

    @Override
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

        final var f = new BroadcasterFuture<Object>(msg);

        @SuppressWarnings("unchecked")
        Future<Object> result = (Future<Object>) bc.getScheduledExecutorService().scheduleWithFixedDelay(() -> {
            if (o instanceof Callable<?> c) {
                try {
                    Object r = c.call();
                    final Object msg1 = filter(r);
                    if (msg1 != null) {
                        var deliver = new Deliver(msg1, f, r);
                        push(deliver);
                    }
                    return;
                } catch (Exception e) {
                    logger.error("", e);
                }
            }
            final Object msg1 = filter(o);
            final var e = new Deliver(msg1, f, o);
            push(e);
        }, waitFor, period, t);
        return result;
    }

    @Override
    public String toString() {
        return "\n\tName: " + name +
                "\n\tAtmosphereResource: " + resources.size() +
                "\n\tBroadcasterCache " + bc.getBroadcasterCache();
    }

    protected final static class AsyncWriteToken {

        AtmosphereResource resource;
        Object msg;
        BroadcasterFuture<?> future;
        Object originalMessage;
        CacheMessage cache;
        AtomicInteger count;

        public AsyncWriteToken(AtmosphereResource resource, Object msg, BroadcasterFuture<?> future, Object originalMessage, AtomicInteger count) {
            this.resource = resource;
            this.msg = msg;
            this.future = future;
            this.originalMessage = originalMessage;
            this.count = count;
        }

        public AsyncWriteToken(AtmosphereResource resource, Object msg, BroadcasterFuture<?> future, Object originalMessage, CacheMessage cache, AtomicInteger count) {
            this.resource = resource;
            this.msg = msg;
            this.future = future;
            this.originalMessage = originalMessage;
            this.cache = cache;
            this.count = count;
        }

        public void destroy() {
            this.resource = null;
            this.msg = null;
            this.future = null;
            this.originalMessage = null;
        }

        public boolean lastBroadcasted() {
            return count.decrementAndGet() == 0;
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
        return tu.toMillis(period);
    }

    boolean notifyOnPreDestroy() {
        for (BroadcasterListener b : broadcasterListeners) {
            try {
                b.onPreDestroy(this);
            } catch (RuntimeException ex) {
                if (ex instanceof BroadcasterListener.BroadcastListenerException) {
                    logger.trace("onPreDestroy", ex);
                    return true;
                }
                logger.warn("onPreDestroy", ex);
            }
        }
        return false;
    }

    public Collection<BroadcasterListener> broadcasterListeners() {
        return broadcasterListeners;
    }

    public BroadcasterLifeCyclePolicy lifeCyclePolicy() {
        return lifeCyclePolicy;
    }

    public ConcurrentLinkedQueue<BroadcasterLifeCyclePolicyListener> lifeCycleListeners() {
        return lifeCycleListeners;
    }

    public BlockingQueue<Deliver> messages() {
        return messages;
    }

    public ConcurrentHashMap<String, WriteQueue> writeQueues() {
        return writeQueues;
    }

    public POLICY policy() {
        return policy;
    }

    public boolean outOfOrderBroadcastSupported() {
        return outOfOrderBroadcastSupported.get();
    }

    public AtomicBoolean recentActivity() {
        return recentActivity;
    }

    public LifecycleHandler lifecycleHandler() {
        return lifecycleHandler;
    }

    public DefaultBroadcaster lifecycleHandler(LifecycleHandler lifecycleHandler) {
        this.lifecycleHandler = lifecycleHandler;
        return this;
    }

    public Future<?> currentLifecycleTask() {
        return currentLifecycleTask;
    }

    public DefaultBroadcaster currentLifecycleTask(Future<?> currentLifecycleTask) {
        this.currentLifecycleTask = currentLifecycleTask;
        return this;
    }
}
